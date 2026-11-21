package dev.mjwatch.playback

import android.app.PictureInPictureParams
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import okhttp3.HttpUrl.Companion.toHttpUrl
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.Futures
import androidx.room.Room
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_OFFLINE="offlinePlayback"

        fun offlineIntent(
            context:Context,
            episode:PlaybackEpisode,
            title:String,
            releaseId:String,
            mobile:Boolean,
            episodes:List<PlaybackEpisode> = emptyList()
        ):Intent = Intent(context,PlaybackActivity::class.java)
            .putExtra(EXTRA_OFFLINE,true)
            .putExtra("uri",episode.uri)
            .putExtra("path",episode.path)
            .putExtra("fileId",episode.fileId)
            .putExtra("contentKey",episode.contentKey)
            .putExtra("title",title)
            .putExtra("releaseId",releaseId)
            .putExtra("mobile",mobile)
            .putExtra("episodes",PlaybackEpisode.encode(episodes))

        private fun isLocalFile(uri:String):Boolean = Uri.parse(uri).let {
            it.scheme==ContentResolver.SCHEME_FILE && it.authority.isNullOrEmpty() && it.path?.startsWith('/')==true
        }

        private var active:java.lang.ref.WeakReference<PlaybackActivity>?=null
        fun hasActivePlayback()=active?.get()?.let {!it.released&&!it.isFinishing}==true
        fun remoteVideo():RemoteVideo?=active?.get()?.takeIf {!it.released&&it::player.isInitialized}?.let {a->
            if(a.offline||a.intent.getBooleanExtra("mobile",true))return@let null
            val p=a.player;val audio=a.getSystemService(android.media.AudioManager::class.java)
            RemoteVideo(a.playbackSession,a.remoteContentId,a.intent.getStringExtra("title").orEmpty(),when {a.terminalError||p.playerError!=null->"error";p.playbackState==Player.STATE_ENDED->"ended";!a.firstFrame||p.playbackState==Player.STATE_BUFFERING->"loading";p.isPlaying->"playing";else->"paused"},p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),p.isCurrentMediaItemSeekable,audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),if(audio.isVolumeFixed)0 else audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),a.releaseId,a.intent.getStringExtra("metadataId").orEmpty()).let {if(it.volumeMax==0)it.copy(volume=0) else it}
        }
        fun remoteCommand(command:org.json.JSONObject) {
            val a=active?.get()?.takeIf {!it.released&&it::player.isInitialized&&!it.offline&&!it.intent.getBooleanExtra("mobile",true)} ?: return
            if(command.optString("session")!=a.playbackSession)return
            when(command.optString("action")) {
                "play"->a.player.play()
                "pause"->a.player.pause()
                "seek"->if(a.player.isCurrentMediaItemSeekable)a.player.seekTo(command.optLong("position").coerceIn(0,a.player.duration.coerceAtLeast(0)))
                "volume"->{val audio=a.getSystemService(android.media.AudioManager::class.java);if(!audio.isVolumeFixed)audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,command.optInt("volume").coerceIn(0,audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)),0)}
                "stop"->stopActivePlayback()
            }
        }
        fun stopActivePlayback() {active?.get()?.let {it.save();it.releasePlayer();it.finish()}}
    }
    private val offline get()=intent.getBooleanExtra(EXTRA_OFFLINE,false)
    private var episodes=emptyList<PlaybackEpisode>()
    private var episodeIndex by androidx.compose.runtime.mutableIntStateOf(-1)
    private var episodeLoad:Job?=null
    private var mediaInteraction by androidx.compose.runtime.mutableIntStateOf(0)
    private var panelOpen by androidx.compose.runtime.mutableStateOf(false)
    private var tvBackHandler:(()->Unit)?=null
    private var controlsVisible by androidx.compose.runtime.mutableStateOf(true)
    private lateinit var overlay:androidx.compose.ui.platform.ComposeView
    private var released=false
    private var lastProgressWrite:Job?=null
    private var episodeCompleted=false
    private val decoderFallback=DecoderFallback()
    private var bufferPlan=PlaybackBuffer(4*1048576,4*1048576)
    private var bufferTargetBytes=4*1048576
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private var session: MediaSession?=null
    private var tvSessionPlayer:TvSessionPlayer?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val saveMutex=kotlinx.coroutines.sync.Mutex()
    private val handler=Handler(Looper.getMainLooper())
    var firstFrameSeen=false; private set
    private var terminalError=false
    private var firstFrame=false
    private var recoveryJob:Job?=null
    private var stablePlayback:Job?=null
    private var retries=0
    private var panel: android.app.AlertDialog?=null
    private var startedAt=0L
    private var playbackConfig:Config?=null
    private var playbackSession=java.util.UUID.randomUUID().toString()
    private var remoteContentId=""
    private var playbackSequence=0L
    private var contentIdentity=""
    private var releaseId=""; private var path=""; private var fileId=0
    private var status by androidx.compose.runtime.mutableStateOf<PlaybackStatus?>(PlaybackStatus("Готовим видео","Загружаем первые кадры",loading=true))
    private lateinit var db: WatchDatabase
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active?.get()?.takeIf {it!==this}?.let {previous -> previous.save();previous.releasePlayer();previous.finish()}
        active=java.lang.ref.WeakReference(this)
        if(intent.getBooleanExtra("mobile",true)&&resources.configuration.smallestScreenWidthDp<600) requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        playbackSession=intent.getStringExtra("remoteSession") ?: playbackSession
        remoteContentId=intent.getStringExtra("remoteContentId").orEmpty()
        contentIdentity=intent.getStringExtra("contentKey") ?: ""
        releaseId=intent.getStringExtra("releaseId") ?: ""; path=intent.getStringExtra("path") ?: ""; fileId=intent.getIntExtra("fileId",0)
        episodes=PlaybackEpisode.decode(intent.getStringExtra("episodes"))
        if(offline)episodes=episodes.filter {isLocalFile(it.uri)}
        episodeIndex=episodes.indexOfFirst {it.path==path&&it.fileId==fileId}
        db=Room.databaseBuilder(applicationContext, WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).enableMultiInstanceInvalidation().build()
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        val root=FrameLayout(this).apply {setBackgroundColor(android.graphics.Color.BLACK)}
        val backdrop=VideoBackdrop(this)
        root.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
        view=PlayerView(this).apply {setBackgroundColor(android.graphics.Color.TRANSPARENT);setShutterBackgroundColor(android.graphics.Color.BLACK);setShowSubtitleButton(true); setShowNextButton(false); setShowPreviousButton(false); setShowFastForwardButton(true); setShowRewindButton(true); controllerShowTimeoutMs=if((getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled) 0 else 4000 }
        root.addView(view,FrameLayout.LayoutParams(-1,-1))
        view.useController=false
        overlay=androidx.compose.ui.platform.ComposeView(this).apply {setContent {
            PlayerControls(player={if(::player.isInitialized&&!released) player else null},title=intent.getStringExtra("title") ?: path,visible=controlsVisible,mobile=intent.getBooleanExtra("mobile",true),status=status?.let {if(offline)it.copy(backLabel="К загрузкам") else it},onRetry={retryPlayback()},keepVisible=(getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled,onVisibility={controlsVisible=it},onBack={exitPlayback()},onTracks={tracks()},onFit={view.resizeMode=if(view.resizeMode==androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT},onPip={enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())},episodeCount=episodes.size,episodeIndex=episodeIndex,episodeLabel=episodes.getOrNull(episodeIndex)?.path.orEmpty(),onEpisode={switchEpisode(it)},episodes=episodes,externalPanel=panelOpen,onTvBackHandler={tvBackHandler=it},onVideo={videoSettings()},mediaInteraction=mediaInteraction)
        }}
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
        scope.launch {while(isActive) {delay(1000);if(!released&&firstFrame&&lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))backdrop.sample(view.videoSurfaceView)}}
        androidx.core.view.WindowCompat.getInsetsController(window,root).apply {hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE}
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) { override fun handleOnBackPressed() = handlePlaybackBack() })
        scope.launch {
            val config=Settings(applicationContext).config.first();playbackConfig=config
            val uri=intent.getStringExtra("uri") ?: return@launch finish()
            val dataSource:DataSource.Factory
            if(offline) {
                if(!isLocalFile(uri)) {
                    showPlaybackFailure()
                    return@launch
                }
                dataSource=DataSource.Factory {FileDataSource()}
            } else {
                if(!validStreamUri(uri,config)) {
                    status=PlaybackStatus("Подключение изменено","Вернитесь к карточке и выберите раздачу заново.");return@launch
                }
                if(config.managed&&!uri.startsWith("asset:")) {
                    val checked=runCatching {withContext(Dispatchers.IO){ManagedApi(config,SecretStore(applicationContext)).authorized("me")}}
                    if(checked.isFailure){status=PlaybackStatus("Сессия недоступна","Вернитесь в аккаунт и проверьте подключение.");return@launch}
                    scope.launch(Dispatchers.IO) {while(isActive){delay(60_000);runCatching {ManagedApi(config,SecretStore(applicationContext)).authorized("me")}}}
                }
                val headers=mutableMapOf<String,String>()
                val auth=if(config.managed) "" else SecretStore(applicationContext).get("torrAuth:${config.torrServer}")
                if(!uri.startsWith("asset:")&&auth.isNotBlank())headers["Authorization"]=auth
                val client=if(config.managed)managedClient(config,SecretStore(applicationContext))
                    else guardedClient(config.copy(jackett="",indexers="",metadataProvider="none",torznabPaths=emptyMap()))
                val http=OkHttpDataSource.Factory(client.newBuilder().callTimeout(0,java.util.concurrent.TimeUnit.MILLISECONDS).build()).setDefaultRequestProperties(headers)
                dataSource=DefaultDataSource.Factory(this@PlaybackActivity,http)
            }
            val memory=android.app.ActivityManager.MemoryInfo()
            val manager=getSystemService(android.app.ActivityManager::class.java)
            manager.getMemoryInfo(memory)
            val runtime=Runtime.getRuntime()
            bufferPlan=playbackBuffer(runtime.maxMemory(),runtime.totalMemory()-runtime.freeMemory(),memory.availMem,manager.isLowRamDevice)
            val requested=getSharedPreferences("playback-preferences",MODE_PRIVATE).getInt("buffer-mib",0)
            bufferTargetBytes=if(requested<=0)bufferPlan.recommendedBytes else (requested.toLong()*1048576).coerceIn(4L*1048576,bufferPlan.maximumBytes.toLong()).toInt()
            val loadControl=androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000,120000,1500,3000)
                .setTargetBufferBytes(bufferTargetBytes).setPrioritizeTimeOverSizeThresholds(false).build()
            player=ExoPlayer.Builder(this@PlaybackActivity,decoderFallback.renderers(this@PlaybackActivity)).setLoadControl(loadControl).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(StreamLoadErrorPolicy()))
                .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            player.setHandleAudioBecomingNoisy(true)
            val preferences=getSharedPreferences("playback-preferences",MODE_PRIVATE)
            player.trackSelectionParameters=player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(preferences.getString("audio-language",null))
                .setPreferredTextLanguage(preferences.getString("subtitle-language",null))
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,preferences.getBoolean("subtitles-off",false)).build()
            player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setMaxVideoSize(Int.MAX_VALUE,preferences.getInt("max-video-height",Int.MAX_VALUE)).build()
            player.setPlaybackSpeed(savedInstanceState?.getFloat("speed",preferences.getFloat("speed",1f)) ?: preferences.getFloat("speed",1f))
            view.resizeMode=savedInstanceState?.getInt("resize-mode") ?: preferences.getInt("resize-mode",androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
            view.player=player
            if(!intent.getBooleanExtra("mobile",true)) tvSessionPlayer=TvSessionPlayer(player)
            session=MediaSession.Builder(this@PlaybackActivity,tvSessionPlayer ?: player).setCallback(object:MediaSession.Callback {
                override fun onMediaButtonEvent(session:MediaSession,controllerInfo:MediaSession.ControllerInfo,intent:android.content.Intent):Boolean {
                    @Suppress("DEPRECATION") val event=intent.getParcelableExtra<KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT) ?: return false
                    return handleMediaKey(event)
                }
            }).build()
            player.addListener(object: Player.Listener {
                override fun onEvents(player:Player,events:Player.Events) {
                    view.keepScreenOn=player.isPlaying||(player.playWhenReady&&player.playbackState==Player.STATE_BUFFERING)
                }
                override fun onRenderedFirstFrame() { firstFrame=true; firstFrameSeen=true; view.setKeepContentOnPlayerReset(true); status=null }
                override fun onPlaybackStateChanged(state: Int) {
                    if(state==Player.STATE_BUFFERING) { status=bufferingStatus(firstFrame); startedAt=System.currentTimeMillis() }
                    if(state==Player.STATE_READY&&firstFrame) status=null
                    if(state==Player.STATE_ENDED) { save(true); status=PlaybackStatus("Просмотр завершён") }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    stablePlayback?.cancel()
                    if(!isPlaying)save() else stablePlayback=scope.launch {delay(20_000);retries=0}
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason:Int) { save() }
                override fun onPlayerError(error: PlaybackException) {
                    save()
                    if(!offline&&streamLimitScope(error)!=null){showPlaybackFailure(error);return}
                    if(!released&&decoderFallback.recover(this@PlaybackActivity,player,error)) {
                        terminalError=false;startedAt=System.currentTimeMillis()
                        status=PlaybackStatus("Восстанавливаем декодер","Пробуем совместимый режим или другой декодер.",loading=true,compact=firstFrame)
                        return
                    }
                    val http=generateSequence<Throwable>(error) {it.cause}.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                    if(!offline&&recoverablePlaybackError(error.errorCode,http,config.managed)&&retries<3) recoverPlayback(automatic=true)
                    else showPlaybackFailure(error)
                }
            })
            val progress=if(config.historyEnabled) db.syncDao().entity(config.scope,"progress",contentIdentity.ifBlank {"$releaseId|$path"})?.takeUnless {it.deleted}?.progress() else null
            player.setMediaItem(mediaItem(uri),savedInstanceState?.getLong("position") ?: if(intent.hasExtra("remotePosition"))intent.getLongExtra("remotePosition",0).coerceAtLeast(0) else progress?.takeUnless { it.completed }?.positionMs ?: 0)
            startedAt=System.currentTimeMillis(); player.prepare(); player.playWhenReady=savedInstanceState?.getBoolean("playing",true) ?: true
            view.requestFocus()
            while(isActive) { delay(15000); save()
                if(!terminalError&&(player.playbackState==Player.STATE_BUFFERING || !firstFrame)) {
                    val elapsed=System.currentTimeMillis()-startedAt
                    if(elapsed>180_000) {terminalError=true;recoveryJob?.cancel();player.stop();status=if(offline) localFailureStatus(retry=true) else PlaybackStatus("Не удалось загрузить видео","Повторите попытку или выберите другую раздачу.",retry=true)}
                    else if(!offline&&elapsed>=30_000) status=bufferingStatus(firstFrame,elapsed)
                }
            }
        }
    }
    private fun validStreamUri(uri:String,config:Config):Boolean = uri.startsWith("asset:") ||
        runCatching {uri.toHttpUrl().origin()==endpoint(if(config.managed) config.managedUrl else config.torrServer).origin()}.getOrDefault(false)

    private fun mediaItem(uri:String)=MediaItem.Builder().setUri(uri)
        .apply {if(path.substringAfterLast('.').equals("mkv",ignoreCase=true))setMimeType(MimeTypes.VIDEO_MATROSKA)}.setMediaId(contentIdentity.ifBlank {"$releaseId|$path"})
        .setMediaMetadata(MediaMetadata.Builder().setTitle(intent.getStringExtra("title") ?: path).setSubtitle(path.substringAfterLast('/')).build()).build()
    /** ExoPlayer owns one stream at a time. The session also exposes the activity's episode queue. */
    private inner class TvSessionPlayer(player:Player):ForwardingSimpleBasePlayer(player) {
        override fun getState():State {
            val state=super.getState()
            val commands=state.availableCommands.buildUpon()
            if(episodeIndex>=0 && episodeIndex<episodes.lastIndex) {
                commands.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            return state.buildUpon().setAvailableCommands(commands.build()).build()
        }
        override fun handleSeek(mediaItemIndex:Int,positionMs:Long,seekCommand:Int):com.google.common.util.concurrent.ListenableFuture<*> {
            if(seekCommand==Player.COMMAND_SEEK_TO_NEXT || seekCommand==Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                switchEpisode(episodeIndex+1)
                return Futures.immediateVoidFuture()
            }
            return super.handleSeek(mediaItemIndex,positionMs,seekCommand)
        }
        fun episodeChanged()=invalidateState()
    }
    private fun switchEpisode(index:Int) {
        val episode=episodes.getOrNull(index) ?: return
        val config=playbackConfig ?: return
        if(!::player.isInitialized||released||index==episodeIndex||episodeLoad?.isActive==true)return
        if(!offline&&!validStreamUri(episode.uri,config))return
        save()
        episodeLoad?.cancel();recoveryJob?.cancel();stablePlayback?.cancel();handler.removeCallbacksAndMessages(null)
        firstFrame=false;firstFrameSeen=false;episodeCompleted=false;terminalError=false;retries=0
        view.setKeepContentOnPlayerReset(false)
        player.stop();decoderFallback.reset()
        episodeIndex=index;path=episode.path;fileId=episode.fileId;contentIdentity=episode.contentKey
        tvSessionPlayer?.episodeChanged()
        remoteContentId=remoteContentId.substringBeforeLast(':',"")+":"+fileId
        playbackSession=java.util.UUID.randomUUID().toString()
        intent.putExtra("uri",episode.uri).putExtra("path",path).putExtra("fileId",fileId).putExtra("contentKey",contentIdentity)
        controlsVisible=true;status=PlaybackStatus("Готовим видео","Загружаем серию",loading=true)
        episodeLoad=scope.launch {
            val progress=if(config.historyEnabled)withContext(Dispatchers.IO) {db.syncDao().entity(config.scope,"progress",contentIdentity.ifBlank {"$releaseId|$path"})?.takeUnless {it.deleted}?.progress()} else null
            player.setMediaItem(mediaItem(episode.uri),progress?.takeUnless {it.completed}?.positionMs ?: 0)
            startedAt=System.currentTimeMillis();player.prepare();player.play()
        }
    }
    private fun retryPlayback() {
        if(!offline) {
            recoverPlayback(automatic=false)
        } else if(::player.isInitialized&&!released) {
            terminalError=false
            startedAt=System.currentTimeMillis()
            player.prepare()
        }
    }

    private fun localFailureStatus(error:PlaybackException?=null,retry:Boolean=false):PlaybackStatus {
        val code=error?.errorCode?.let {"\nКод: $it"}.orEmpty()
        return PlaybackStatus("Не удалось открыть загрузку","Проверьте файл в загрузках и скачайте его заново при необходимости.$code",retry=retry)
    }
    private fun showPlaybackFailure(error:PlaybackException?=null,limitScope:String?=null) {
        terminalError=true
        if(offline&&error?.errorCode !in 4000..5999) {
            status=localFailureStatus(error,retry=error!=null)
            return
        }
        (limitScope ?: streamLimitScope(error))?.let {status=streamLimitStatus(it);return}
        val format=(error as? ExoPlaybackException)?.rendererFormat
        val details=format?.let {f->listOfNotNull(f.codecs,if(f.width>0&&f.height>0) "${f.width}×${f.height}" else null,if(f.channelCount>0) "Каналов: ${f.channelCount}" else null,error?.failedMediaCodec()?.name?.let {"Декодер: $it"}).joinToString(" · ")}.orEmpty()
        status=playbackFailureStatus(error?.errorCode,format?.sampleMimeType,details,offline)
    }
    private fun recoverPlayback(automatic:Boolean) {
        if(!::player.isInitialized||released||recoveryJob?.isActive==true)return
        val config=playbackConfig ?: return
        if(!automatic)retries=0
        val position=player.currentPosition.coerceAtLeast(0)
        val playing=player.playWhenReady
        terminalError=false;startedAt=System.currentTimeMillis()
        recoveryJob=scope.launch {
            while(isActive&&!released) {
                status=PlaybackStatus("Восстанавливаем соединение",if(automatic)"Попытка ${retries+1} из 3" else "Подключаемся к потоку",loading=true,compact=firstFrame)
                if(automatic||retries>0)delay(listOf(1000L,3000L,8000L)[retries.coerceAtMost(2)])
                retries++
                try {
                    val source=withContext(Dispatchers.IO) {
                        val secrets=SecretStore(applicationContext)
                        if(Settings(applicationContext).config.first().profileKey!=config.profileKey)error("Подключение изменено")
                        if(config.managed)ManagedApi(config,secrets).authorized("me")
                        val raw=intent.getStringExtra("recoveryRelease")
                        if(raw==null)null else {
                            val json=org.json.JSONObject(raw)
                            val hash=json.getString("hash")
                            val release=Release(json.getString("id"),json.getString("title"),json.getString("source"),hash=hash,
                                magnet=json.optString("magnet").takeIf {it.isNotBlank()},download=json.optString("download").takeIf {it.isNotBlank()})
                            val client=if(config.managed&&endpoint(config.jackett).origin()==endpoint(config.managedUrl).origin())managedClient(config,secrets) else guardedClient(config)
                            val torrents=TorznabRepository(config,secrets.get("jackett:${config.jackett}"),client)
                            val gateway=if(config.managed)ManagedGateway(ManagedApi(config,secrets),torrents) else TorrServerGateway(config,secrets.get("torrAuth:${config.torrServer}"),client,torrents)
                            val handle=recoverTorrent(gateway,release,intent.getStringExtra("torrentHandle") ?: hash,hash,path)
                            handle to handle.files.associate {it.path to gateway.stream(handle,it).uri}
                        }
                    }
                    if(released)return@launch
                    if(source!=null) {
                        intent.putExtra("torrentHandle",source.first.hash)
                        val file=source.first.files.single {it.path==path};fileId=file.serverFileId
                        intent.putExtra("fileId",fileId).putExtra("uri",source.second.getValue(path))
                        episodes=episodes.map {episode->source.first.files.singleOrNull {it.path==episode.path}?.let {episode.copy(uri=source.second.getValue(it.path),fileId=it.serverFileId)} ?: episode}
                        intent.putExtra("episodes",PlaybackEpisode.encode(episodes))
                    }
                    startedAt=System.currentTimeMillis()
                    player.setMediaItem(mediaItem(intent.getStringExtra("uri")!!),position)
                    player.prepare();player.playWhenReady=playing
                    return@launch
                } catch(e:TimeoutCancellationException) {
                    if(!automatic||retries>=3) {showPlaybackFailure();return@launch}
                } catch(e:CancellationException) {throw e} catch(e:Exception) {
                    streamLimitScope(e)?.let {showPlaybackFailure(limitScope=it);return@launch}
                    if(!automatic||retries>=3||e is HttpFailure&&e.status in listOf(400,401,403)||e is IllegalStateException) {showPlaybackFailure();return@launch}
                }
            }
        }
    }
    private fun save(ended:Boolean=false) {
        val config=playbackConfig ?: return
        if(!::player.isInitialized || released || !firstFrame || !config.historyEnabled) return
        val duration=player.duration.takeIf { it>0 }
        val position=player.currentPosition.coerceAtLeast(0)
        episodeCompleted=episodeCompleted || ended || duration?.let { position>=it*0.95 }==true
        val progress=Progress(contentIdentity.ifBlank {"$releaseId|$path"},releaseId,fileId,path,position,duration,System.currentTimeMillis(),episodeCompleted)
        val sessionId=playbackSession
        val sequence=++playbackSequence
        lastProgressWrite=scope.launch {
            saveMutex.lock()
            try {
                withContext(Dispatchers.IO) {
                    if(Settings(applicationContext).config.first().profileKey!=config.profileKey) return@withContext
                    val engine=SyncEngine(ManagedApi(config,SecretStore(applicationContext)),db.syncDao())
                    engine.enqueue("progress",progress.key,progress.syncJson(),playSession=sessionId,sequence=sequence)
                    if(!offline) runCatching {engine.sync()}
                }
            } finally {
                saveMutex.unlock()
            }
        }
    }
    private fun tracks() {
        if(!::player.isInitialized) return
        val names=mutableListOf("Субтитры выключены"); val choices=mutableListOf<Pair<Tracks.Group,Int>?>(null)
        player.currentTracks.groups.filter { it.type==C.TRACK_TYPE_AUDIO || it.type==C.TRACK_TYPE_TEXT }.forEach { group ->
            for(i in 0 until group.length) if(group.isTrackSupported(i)) {
                val f=group.getTrackFormat(i); names+="${if(group.type==C.TRACK_TYPE_AUDIO) "Аудио" else "Субтитры"}: ${trackName(f.label,f.language)}${if(group.isTrackSelected(i)) " ✓" else ""}"; choices+=group to i
            }
        }
        panelOpen=true
        panel=android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Dialog_Alert)).setTitle("Дорожки").setItems(names.toTypedArray()) { _,which ->
            val builder=player.trackSelectionParameters.buildUpon(); val selected=choices[which]
            val preferences=getSharedPreferences("playback-preferences",MODE_PRIVATE)
            if(selected==null) {builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);preferences.edit().putBoolean("subtitles-off",true).apply()} else {
                val language=selected.first.getTrackFormat(selected.second).language
                if(selected.first.type==C.TRACK_TYPE_AUDIO) preferences.edit().putString("audio-language",language).apply()
                else preferences.edit().putString("subtitle-language",language).putBoolean("subtitles-off",false).apply()
                builder.setTrackTypeDisabled(selected.first.type,false).setOverrideForType(TrackSelectionOverride(selected.first.mediaTrackGroup, selected.second))
            }; save(); player.trackSelectionParameters=builder.build()
            val c=playbackConfig
            if(c?.historyEnabled==true)scope.launch(Dispatchers.IO){
                if(Settings(applicationContext).config.first().profileKey!=c.profileKey)return@launch
                val value=org.json.JSONObject().put("quality",preferences.getString("quality","Все")).put("audioLanguage",preferences.getString("audio-language",null)).put("subtitleLanguage",if(preferences.getBoolean("subtitles-off",false))"off" else preferences.getString("subtitle-language",null))
                runCatching {val engine=SyncEngine(ManagedApi(c,SecretStore(applicationContext)),db.syncDao());engine.enqueue("preferences","profile",value);if(!offline)engine.sync()}
            }
        }.setOnDismissListener { panel=null; panelOpen=false; if(intent.getBooleanExtra("mobile",true))overlay.requestFocus() }.show().also {styleTvPanel(it)}
    }
    private fun styleTvPanel(dialog:android.app.AlertDialog) {
        if(intent.getBooleanExtra("mobile",true))return
        val density=resources.displayMetrics.density
        val focus=android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.argb(102,42,48,58))
            cornerRadius=11*density
            setStroke(kotlin.math.round(1.5f*density).toInt(),android.graphics.Color.rgb(240,242,236))
        }
        dialog.listView.selector=android.graphics.drawable.InsetDrawable(focus,(2*density).toInt())
    }
    private fun handlePlaybackBack() {
        panel?.let { it.dismiss(); return }
        val handleTvBack = tvBackHandler
        // The activity updates the status before the TV controls can recompose for the new episode.
        if (!intent.getBooleanExtra("mobile", true) && status?.compact != false && handleTvBack != null) {
            handleTvBack()
        } else {
            exitPlayback()
        }
    }

    private fun exitPlayback() {save();finish()}
    private fun videoSettings() {
        if(!::player.isInitialized||released)return
        val preferences=getSharedPreferences("playback-preferences",MODE_PRIVATE)
        val names=arrayOf("Качество", "Скорость · ${player.playbackParameters.speed}×", "Масштаб", "Автопереход · ${if(preferences.getBoolean("auto-next",true))"Вкл" else "Выкл"}", "Автопропуск опенинга/эндинга · ${if(preferences.getBoolean("auto-skip",false))"Вкл" else "Выкл"}", "Буфер · ${bufferTargetBytes/1048576} МиБ")
        showVideoPanel("Параметры видео",names) {which -> when(which) {
            0 -> {
                val heights=player.currentTracks.groups.filter {it.type==C.TRACK_TYPE_VIDEO}.flatMap {g->(0 until g.length).filter {g.isTrackSupported(it)}.map {g.getTrackFormat(it).height}}.filter {it>0}.distinct().sortedDescending()
                showVideoPanel("Качество",(listOf("Авто")+heights.map {"${it}p"}).toTypedArray()) {selection ->
                    save()
                    player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).setMaxVideoSize(Int.MAX_VALUE,if(selection==0)Int.MAX_VALUE else heights[selection-1]).build()
                    preferences.edit().putInt("max-video-height",if(selection==0)Int.MAX_VALUE else heights[selection-1]).apply()
                }
            }
            1 -> {val speeds=listOf(.5f,.75f,1f,1.25f,1.5f,2f);showVideoPanel("Скорость",speeds.map {"${it}×"}.toTypedArray()) {player.setPlaybackSpeed(speeds[it]);preferences.edit().putFloat("speed",speeds[it]).apply()}}
            2 -> showVideoPanel("Масштаб",arrayOf("Вписать","Заполнить","Растянуть")) {selection ->
                view.resizeMode=listOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL)[selection]
                preferences.edit().putInt("resize-mode",view.resizeMode).apply()
            }
            3 -> preferences.edit().putBoolean("auto-next",!preferences.getBoolean("auto-next",true)).apply()
            4 -> preferences.edit().putBoolean("auto-skip",!preferences.getBoolean("auto-skip",false)).apply()
            5 -> {
                val maximum=bufferPlan.maximumBytes/1048576
                val choices=(listOf(16,32,64,128).filter{it<=maximum}+maximum).distinct().sorted()
                val labels=listOf("Авто · рекомендуется ${bufferPlan.recommendedBytes/1048576} МиБ")+choices.map{if(it==maximum)"$it МиБ · оценочный максимум" else "$it МиБ"}
                val buffered=((player.bufferedPosition-player.currentPosition).coerceAtLeast(0)/1000)
                showVideoPanel("Буфер: загружено $buffered с · для следующего видео",labels.toTypedArray()){selected->
                    preferences.edit().putInt("buffer-mib",if(selected==0)0 else choices[selected-1]).apply()
                    Toast.makeText(this,"Буфер сохранён для следующего запуска видео",Toast.LENGTH_SHORT).show()
                }
            }
        }}
    }
    private fun showVideoPanel(title:String,names:Array<String>,choose:(Int)->Unit) {
        val dialog=android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Dialog_Alert)).setTitle(title).setItems(names) {_,which->choose(which)}.create()
        panel=dialog;panelOpen=true
        dialog.setOnDismissListener {if(panel===dialog){panel=null;panelOpen=false}}
        dialog.show();styleTvPanel(dialog)
    }
    override fun onSaveInstanceState(outState: Bundle) { if(::player.isInitialized&&!released) { outState.putLong("position",player.currentPosition); outState.putBoolean("playing",player.playWhenReady); outState.putFloat("speed",player.playbackParameters.speed);outState.putInt("resize-mode",view.resizeMode) }; super.onSaveInstanceState(outState) }
    override fun onStop() { if(::player.isInitialized&&!released&&!isInPictureInPictureMode) { player.pause(); save() }; super.onStop() }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode:Boolean,newConfig:Configuration) { super.onPictureInPictureModeChanged(inPictureInPictureMode,newConfig); overlay.visibility=if(inPictureInPictureMode) android.view.View.GONE else android.view.View.VISIBLE }
    private fun releasePlayer() {if(!released) {released=true;episodeLoad?.cancel();recoveryJob?.cancel();stablePlayback?.cancel();session?.release();if(::player.isInitialized) player.release()}}
    override fun onDestroy() {
        save()
        val pending=lastProgressWrite; handler.removeCallbacksAndMessages(null); releasePlayer()
        if(active?.get()===this) active=null
        // Finish the final atomic Room write before closing the database.
        scope.launch { pending?.join(); db.close(); scope.cancel() }
        super.onDestroy()
    }
    private fun handleMediaKey(event:KeyEvent):Boolean {
        if(!::player.isInitialized||released)return false
        if(event.keyCode !in listOf(KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_REWIND,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))return false
        if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0) {
            mediaInteraction++
            when(event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> switchEpisode(episodeIndex+1)
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> switchEpisode(episodeIndex-1)
                KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if(player.playWhenReady)player.pause() else player.play()
                KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekBack()
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekForward()
            }
        }
        return true
    }
    // AndroidX exposes the platform Activity override through a restricted bridge.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(handleMediaKey(event))return true
        if(!intent.getBooleanExtra("mobile",true)&&event.keyCode==KeyEvent.KEYCODE_BACK) {
            if(event.action==KeyEvent.ACTION_UP&&!event.isCanceled) {
                handlePlaybackBack()
            }
            return true
        }
        if(intent.getBooleanExtra("mobile",true)&&::player.isInitialized&&!released&&event.action==KeyEvent.ACTION_DOWN&&!controlsVisible) when(event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {player.seekBack();controlsVisible=true;return true}
            KeyEvent.KEYCODE_DPAD_RIGHT -> {player.seekForward();controlsVisible=true;return true}
            KeyEvent.KEYCODE_DPAD_CENTER -> {if(player.playWhenReady)player.pause() else player.play();controlsVisible=true;return true}
        }
        return super.dispatchKeyEvent(event)
    }
}

internal fun trackName(label:String?,language:String?):String {
    val name=language?.takeIf {it.isNotBlank()&&it!="und"}?.let {java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.forLanguageTag("ru"))}?.takeIf {it.isNotBlank()}
    return listOfNotNull(label?.takeIf {it.isNotBlank()&&it!="und"},name).distinct().joinToString(" · ").ifBlank {"язык не указан"}
}
