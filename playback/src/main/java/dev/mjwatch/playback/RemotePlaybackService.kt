package dev.mjwatch.playback

import android.app.*
import android.content.*
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

/** A remote-only Android session. No local player or local audio fallback exists. */
class RemotePlaybackService:Service() {
    companion object {
        private val mutable=MutableStateFlow(RemotePlaybackState())
        val state=mutable.asStateFlow()
        private var current:RemotePlaybackService?=null
        fun connect(context:Context,id:String,name:String?=state.value.target?.takeIf {it.id==id}?.name) {context.startForegroundService(Intent(context,RemotePlaybackService::class.java).putExtra("target",id).putExtra("targetName",name))}
        fun disconnect(context:Context) {context.stopService(Intent(context,RemotePlaybackService::class.java));mutable.value=RemotePlaybackState()}
        fun mediaController():android.media.session.MediaController?=current?.session?.controller?.takeIf {state.value.connected&&state.value.target?.video?.active==true}
        fun load(media:RemoteMedia) {current?.loadMedia(media)}
        fun confirm(replacement:RemoteReplacement) {current?.loadMedia(replacement.media,replacement)}
        fun dismissConflict() {mutable.update {it.copy(conflict=null)}}
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var polling:Job?=null
    private var watchdog:Job?=null
    private var sending:Job?=null
    private val queued=ArrayDeque<Pair<String,()->Unit>>()
    private var requestedVolume:Int?=null
    private lateinit var session:MediaSession
    private var api:ManagedApi?=null
    private var volumeSignature=""
    private var volumeProvider:VolumeProvider?=null
    private var epoch=0
    private var artwork:android.graphics.Bitmap?=null
    private var artworkKey=""
    private var artworkJob:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate();current=this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("remote-video","Просмотр на ТВ",NotificationManager.IMPORTANCE_LOW))
        session=MediaSession(this,"MJWatch TV").apply {
            setCallback(object:MediaSession.Callback() {
                override fun onPlay() {command("play")}
                override fun onPause() {command("pause")}
                override fun onStop() {command("stop")}
                override fun onSeekTo(pos:Long) {command("seek",JSONObject().put("position",pos))}
            },Handler(Looper.getMainLooper()))
        }
        startForeground(2601,notification("Подключаемся к ТВ"))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        intent?.action?.takeIf {it in setOf("play","pause","stop")}?.let {command(it);return START_NOT_STICKY}
        val id=intent?.getStringExtra("target") ?: return START_NOT_STICKY
        startForeground(2601,notification("Подключаемся к ТВ"))
        epoch++;val generation=epoch;queued.clear();requestedVolume=null;polling?.cancel();sending?.cancel();watchdog?.cancel();session.isActive=false
        mutable.value=RemotePlaybackState(target=state.value.target?.takeIf {it.id==id} ?: RemoteTarget(id,intent.getStringExtra("targetName") ?: id.take(6),"",RemoteVideo()),busy=true)
        watchdog=scope.launch {
            while(isActive&&epoch==generation) {
                delay(1000)
                if(state.value.connected&&System.nanoTime()-state.value.observedAt>=6_000_000_000L){lost("Нет связи с ТВ. Видео могло продолжиться.");return@launch}
            }
        }
        polling=scope.launch {
            try {
                val config=Settings(applicationContext).config.first();check(config.historyEnabled){"Для просмотра на ТВ подключите аккаунт MJWatch"}
                val client=ManagedApi(config,SecretStore(applicationContext));api=client
                val connected=withContext(Dispatchers.IO){client.authorized("remote/$id/connect","POST")}
                if(epoch!=generation)return@launch
                mutable.value=RemotePlaybackState(target=RemoteTarget.parse(connected),connected=true,profileKey=config.profileKey,observedAt=System.nanoTime())
                // A connection switch invalidates the receiver lease.
                launch {Settings(applicationContext).config.collect {if(it.profileKey!=config.profileKey||it.managedUrl!=config.managedUrl){disconnect(applicationContext)}}}
                while(isActive&&epoch==generation) {
                    publish();delay(1000)
                    val target=state.value.target ?: break
                    val fresh=withContext(Dispatchers.IO){client.authorized("remote/${target.id}?instance=${target.instance}")}
                    if(epoch==generation)mutable.update {it.copy(target=RemoteTarget.parse(fresh),observedAt=System.nanoTime())}
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(epoch==generation)lost("Нет связи с ТВ. Видео могло продолжиться.")}
        }
        return START_NOT_STICKY
    }
    private fun lost(message:String) {
        mutable.update {it.copy(connected=false,busy=false,conflict=null,message=message)}
        epoch++;queued.clear();requestedVolume=null;polling?.cancel();sending?.cancel();watchdog?.cancel();api?.client?.dispatcher?.cancelAll()
        session.isActive=false;stopForeground(STOP_FOREGROUND_REMOVE)
    }
    private fun loadMedia(media:RemoteMedia,replacement:RemoteReplacement?=null) {
        val target=state.value.target ?: return
        if(!state.value.canLaunch)return
        if(replacement!=null&&(target.id!=replacement.targetId||target.instance!=replacement.instance))return
        if(target.video.active&&target.video.contentId!=media.contentId&&replacement==null) {
            mutable.update {it.copy(conflict=RemoteReplacement(target.id,target.instance,target.video.session,target.name,target.video.title,media))};return
        }
        command("load",JSONObject().put("handle",media.handle).put("fileId",media.fileId).put("title",media.title).put("releaseId",media.releaseId).put("metadataId",media.metadataId).put("position",media.position),replacement?.session ?: target.video.session,media)
    }
    private fun command(action:String,body:JSONObject=JSONObject(),expectedSession:String?=null,media:RemoteMedia?=null) {
        val target=state.value.target ?: return;val client=api ?: return
        if(!state.value.connected)return
        val generation=epoch
        if(sending?.isActive==true) {
            if(action=="load")return
            if(action in setOf("volume","seek"))queued.removeAll {it.first==action}
            if(queued.size>=32)queued.removeFirst()
            val session=expectedSession ?: target.video.session
            queued.addLast(action to {
                val current=state.value.target
                if(epoch==generation&&current?.id==target.id&&current.instance==target.instance&&current.video.session==session)command(action,body,session)
            })
            return
        }
        val payload=body.put("id",UUID.randomUUID().toString()).put("instance",target.instance).put("session",expectedSession ?: target.video.session).put("action",action)
        mutable.update {it.copy(busy=true,message=if(action=="load")"Отправляем видео на ТВ…" else "",conflict=null)}
        sending=scope.launch {
            try {
                withTimeout(8000) {
                    while(true) {
                        try {
                            withContext(Dispatchers.IO){
                                check(Settings(applicationContext).config.first().profileKey==client.config.profileKey){"Account changed"}
                                try {client.authorized("remote/${target.id}/commands","POST",payload)}
                                catch(e:java.io.IOException){if(e is HttpFailure)throw e;client.authorized("remote/${target.id}/commands","POST",payload)}
                            }
                            break
                        } catch(e:HttpFailure) {
                            if(runCatching {JSONObject(e.responseBody ?: "{}").optString("code")}.getOrNull()!="command_pending")throw e
                            delay(250)
                        }
                    }
                }
                // Wait for an actual receiver observation. The accepted load alone proves nothing.
                withTimeout(10000) {
                    while(isActive&&epoch==generation) {
                        val fresh=RemoteTarget.parse(withContext(Dispatchers.IO){client.authorized("remote/${target.id}?instance=${target.instance}")})
                        if(epoch!=generation)return@withTimeout
                        mutable.update {it.copy(target=fresh,observedAt=System.nanoTime())}
                        if(fresh.pendingId!=payload.getString("id")&&(action!="load"||fresh.video.contentId==media?.contentId&&fresh.video.active))break
                        delay(500)
                    }
                }
                if(epoch==generation)mutable.update {it.copy(busy=false,message="")}
            } catch(e:CancellationException){if(e is TimeoutCancellationException&&epoch==generation)mutable.update {it.copy(busy=false,message="ТВ не подтвердил команду. Повторите попытку.")}else throw e}
            catch(e:HttpFailure) {
                if(epoch!=generation)return@launch
                val response=runCatching{JSONObject(e.responseBody ?: "{}")} .getOrNull()
                if(response?.optString("code")=="session_changed"&&media!=null) {
                    val video=response.optJSONObject("state")?.let {RemoteVideo.parse(it)}
                    if(video!=null)mutable.update {it.copy(busy=false,target=target.copy(video=video),conflict=RemoteReplacement(target.id,target.instance,video.session,target.name,video.title,media),message="")}
                    else mutable.update {it.copy(busy=false,message="Просмотр на ТВ изменился. Повторите запуск.")}
                } else if(response?.optString("code")=="session_changed") {
                    response.optJSONObject("state")?.let {video->mutable.update {it.copy(busy=false,target=target.copy(video=RemoteVideo.parse(video)),observedAt=System.nanoTime(),message="")}}
                } else if(response?.optString("code")=="command_pending")mutable.update {it.copy(busy=false,message="ТВ выполняет предыдущую команду. Повторите попытку.")}
                else lost("Нет связи с ТВ. Проверьте доступ и повторите подключение.")
            } catch(e:Exception){if(epoch==generation)lost("Нет связи с ТВ. Видео могло продолжиться.")}
            finally {
                if(epoch==generation) {
                    sending=null
                    if(action=="volume"&&requestedVolume==body.optInt("volume"))requestedVolume=null
                    while(queued.isNotEmpty()&&sending?.isActive!=true)queued.removeFirst().second.invoke()
                }
            }
        }
    }
    // MediaStyle notifications with a session are exempt from POST_NOTIFICATIONS:
    // https://developer.android.com/develop/ui/views/notifications/notification-permission#exemptions
    private fun publish() {
        val target=state.value.target ?: return;val video=target.video
        val key=target.instance+":"+video.metadataId
        if(artworkKey!=key) {
            artworkKey=key;artwork=null;artworkJob?.cancel()
            if(video.metadataId.matches(Regex("tmdb:(movie|tv):[0-9]+")))artworkJob=scope.launch {
                val bitmap=withContext(Dispatchers.IO){runCatching {
                    val client=api ?: return@runCatching null
                    val poster=ManagedMetadata(client).details(video.metadataId)?.poster ?: return@runCatching null
                    val bytes=client.client.bytes(okhttp3.Request.Builder().url(poster).build(),4L*1024*1024)
                    val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    val options=android.graphics.BitmapFactory.Options().apply {inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/512).coerceAtLeast(1)}
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                }.getOrNull()}
                if(artworkKey==key)artwork=bitmap
            }
        }
        val actions=PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or if(video.seek)PlaybackState.ACTION_SEEK_TO else 0L
        val status=when(video.status){"playing"->PlaybackState.STATE_PLAYING;"paused"->PlaybackState.STATE_PAUSED;"loading"->PlaybackState.STATE_BUFFERING;"error"->PlaybackState.STATE_ERROR;else->PlaybackState.STATE_STOPPED}
        session.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(status,video.position,if(video.status=="playing")1f else 0f,SystemClock.elapsedRealtime()).build())
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,video.title).putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,target.name).putString(MediaMetadata.METADATA_KEY_ARTIST,target.name).putLong(MediaMetadata.METADATA_KEY_DURATION,video.duration).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,artwork).build())
        val signature="${target.id}:${video.volumeMax}"
        if(volumeSignature!=signature) {
            volumeSignature=signature
            volumeProvider=object:VolumeProvider(if(video.volumeMax>0)VolumeProvider.VOLUME_CONTROL_ABSOLUTE else VolumeProvider.VOLUME_CONTROL_FIXED,video.volumeMax,video.volume) {
                override fun onSetVolumeTo(volume:Int){requestedVolume=volume.coerceIn(0,maxVolume);command("volume",JSONObject().put("volume",requestedVolume))}
                override fun onAdjustVolume(direction:Int){if(direction==android.media.AudioManager.ADJUST_RAISE||direction==android.media.AudioManager.ADJUST_LOWER)onSetVolumeTo((requestedVolume ?: state.value.target?.video?.volume ?: currentVolume)+direction)}
            }
            session.setPlaybackToRemote(volumeProvider!!)
        } else volumeProvider?.setCurrentVolume(video.volume)
        session.isActive=video.active
        getSystemService(NotificationManager::class.java).notify(2601,notification(if(video.active)video.title else "Подключено · ${target.name}"))
    }
    private fun notification(title:String):Notification {
        val launch=(packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).putExtra("remoteReleaseId",state.value.target?.video?.releaseId).putExtra("remoteTitle",state.value.target?.video?.title).putExtra("remoteMetadataId",state.value.target?.video?.metadataId)
        val pending=PendingIntent.getActivity(this,26,launch,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session.setSessionActivity(pending)
        val action=if(state.value.target?.video?.status=="playing")"pause" else "play"
        val transport=PendingIntent.getService(this,27,Intent(this,RemotePlaybackService::class.java).setAction(action),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder=Notification.Builder(this,"remote-video").setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(state.value.target?.name ?: "MJWatch").setContentIntent(pending).setOngoing(state.value.target?.video?.active==true).setVisibility(Notification.VISIBILITY_PUBLIC)
        if(state.value.target?.video?.active==true)builder.addAction(Notification.Action.Builder(if(action=="pause")android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,if(action=="pause")"Пауза" else "Воспроизвести",transport).build()).setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
        return builder.build()
    }
    override fun onDestroy() {
        epoch++;scope.cancel();session.release();current=null;mutable.update {it.copy(connected=false,busy=false,conflict=null)};super.onDestroy()
    }
}
