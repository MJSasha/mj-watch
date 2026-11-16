package dev.mjwatch.playback

import android.app.PictureInPictureParams
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.room.Room
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackActivity : ComponentActivity() {
    companion object {
        private var active:java.lang.ref.WeakReference<PlaybackActivity>?=null
        fun hasActivePlayback()=active?.get()?.let {!it.released&&!it.isFinishing}==true
        fun stopActivePlayback() {active?.get()?.let {it.finalWrite=it.save();it.releasePlayer();it.finish()}}
    }
    private var episodes=emptyList<PlaybackEpisode>()
    private var episodeIndex by androidx.compose.runtime.mutableIntStateOf(-1)
    private var episodeLoad:Job?=null
    private var mediaInteraction by androidx.compose.runtime.mutableIntStateOf(0)
    private var panelOpen by androidx.compose.runtime.mutableStateOf(false)
    private var tvBackHandler:(()->Unit)?=null
    private var controlsVisible by androidx.compose.runtime.mutableStateOf(true)
    private lateinit var overlay:androidx.compose.ui.platform.ComposeView
    private var released=false
    private var finalWrite:Job?=null
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private var session: MediaSession?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val saveMutex=kotlinx.coroutines.sync.Mutex()
    private val handler=Handler(Looper.getMainLooper())
    var firstFrameSeen=false; private set
    private var terminalError=false
    private var firstFrame=false
    private var retries=0
    private var panel: android.app.AlertDialog?=null
    private var startedAt=0L
    private var playbackConfig:Config?=null
    private val playbackSession=java.util.UUID.randomUUID().toString()
    private var playbackSequence=0L
    private var contentIdentity=""
    private var releaseId=""; private var path=""; private var fileId=0
    private lateinit var dao: WatchDao
    private var status by androidx.compose.runtime.mutableStateOf<PlaybackStatus?>(PlaybackStatus("Готовим видео","Загружаем первые кадры",loading=true))
    private lateinit var db: WatchDatabase
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active?.get()?.takeIf {it!==this}?.let {previous -> previous.finalWrite=previous.save();previous.releasePlayer();previous.finish()}
        active=java.lang.ref.WeakReference(this)
        if(intent.getBooleanExtra("mobile",true)&&resources.configuration.smallestScreenWidthDp<600) requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        contentIdentity=intent.getStringExtra("contentKey") ?: ""
        releaseId=intent.getStringExtra("releaseId") ?: ""; path=intent.getStringExtra("path") ?: ""; fileId=intent.getIntExtra("fileId",0)
        episodes=PlaybackEpisode.decode(intent.getStringExtra("episodes"))
        episodeIndex=episodes.indexOfFirst {it.path==path&&it.fileId==fileId}
        db=Room.databaseBuilder(applicationContext, WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).enableMultiInstanceInvalidation().build(); dao=db.dao()
        val root=FrameLayout(this); view=PlayerView(this).apply { setShowSubtitleButton(true); setShowNextButton(false); setShowPreviousButton(false); setShowFastForwardButton(true); setShowRewindButton(true); controllerShowTimeoutMs=if((getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled) 0 else 4000 }
        root.addView(view,FrameLayout.LayoutParams(-1,-1))
        view.useController=false
        overlay=androidx.compose.ui.platform.ComposeView(this).apply {setContent {
            PlayerControls(player={if(::player.isInitialized&&!released) player else null},title=intent.getStringExtra("title") ?: path,visible=controlsVisible,mobile=intent.getBooleanExtra("mobile",true),status=status,onRetry={retryPlayback()},keepVisible=(getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled,onVisibility={controlsVisible=it},onBack={exitPlayback()},onTracks={tracks()},onFit={view.resizeMode=if(view.resizeMode==androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT},onPip={enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())},episodeCount=episodes.size,episodeIndex=episodeIndex,episodeLabel=episodes.getOrNull(episodeIndex)?.path.orEmpty(),onEpisode={switchEpisode(it)},episodes=episodes,externalPanel=panelOpen,onTvBackHandler={tvBackHandler=it},onVideo={videoSettings()},mediaInteraction=mediaInteraction)
        }}
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
        androidx.core.view.WindowCompat.getInsetsController(window,root).apply {hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE}
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) { override fun handleOnBackPressed() { if(panel!=null) { panel?.dismiss() } else if(!intent.getBooleanExtra("mobile",true)&&tvBackHandler!=null) tvBackHandler?.invoke() else exitPlayback() } })
        scope.launch {
            val config=Settings(applicationContext).config.first();playbackConfig=config
            val uri=intent.getStringExtra("uri") ?: return@launch finish()
            if(!uri.startsWith("asset:")&&runCatching {uri.toHttpUrl().origin()==endpoint(if(config.managed) config.managedUrl else config.torrServer).origin()}.getOrDefault(false)!=true) {
                status=PlaybackStatus("Подключение изменено","Вернитесь к карточке и выберите раздачу заново.");return@launch
            }
            if(config.managed&&!uri.startsWith("asset:")) {
                val checked=runCatching {withContext(Dispatchers.IO){ManagedApi(config,SecretStore(applicationContext)).authorized("me")}}
                if(checked.isFailure){status=PlaybackStatus("Сессия недоступна","Вернитесь в аккаунт и проверьте подключение.");return@launch}
                scope.launch(Dispatchers.IO) {while(isActive){delay(60_000);runCatching {ManagedApi(config,SecretStore(applicationContext)).authorized("me")}}}
            }
            val headers=mutableMapOf<String,String>()
            val auth=if(config.managed) "" else SecretStore(applicationContext).get("torrAuth:${config.torrServer}"); if(!uri.startsWith("asset:")&&auth.isNotBlank()) headers["Authorization"]=auth
            val http=OkHttpDataSource.Factory((if(config.managed) managedClient(config,SecretStore(applicationContext)) else guardedClient(config.copy(jackett="",indexers="",metadataProvider="none",torznabPaths=emptyMap()))).newBuilder().callTimeout(0,java.util.concurrent.TimeUnit.MILLISECONDS).build()).setDefaultRequestProperties(headers)
            player=ExoPlayer.Builder(this@PlaybackActivity).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this@PlaybackActivity,http)).setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(0)))
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
            view.player=player; session=MediaSession.Builder(this@PlaybackActivity,player).setCallback(object:MediaSession.Callback {
                override fun onMediaButtonEvent(session:MediaSession,controllerInfo:MediaSession.ControllerInfo,intent:android.content.Intent):Boolean {
                    @Suppress("DEPRECATION") val event=intent.getParcelableExtra<KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT) ?: return false
                    return handleMediaKey(event)
                }
            }).build()
            player.addListener(object: Player.Listener {
                override fun onEvents(player:Player,events:Player.Events) {
                    view.keepScreenOn=player.isPlaying||(player.playWhenReady&&player.playbackState==Player.STATE_BUFFERING)
                }
                override fun onRenderedFirstFrame() { firstFrame=true; firstFrameSeen=true; status=null }
                override fun onPlaybackStateChanged(state: Int) {
                    if(state==Player.STATE_BUFFERING) { status=if(firstFrame)PlaybackStatus("Буферизация","Видео продолжится автоматически",loading=true) else PlaybackStatus("Готовим видео","Загружаем первые кадры",loading=true); startedAt=System.currentTimeMillis() }
                    if(state==Player.STATE_READY&&firstFrame) status=null
                    if(state==Player.STATE_ENDED) { save(true); status=PlaybackStatus("Просмотр завершён") }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { if(!isPlaying) save() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason:Int) { save() }
                override fun onPlayerError(error: PlaybackException) {
                    save(); val network=error.errorCode in 2000..2999 && error.errorCode!=PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                    if(network&&retries<2) { val delay=if(retries++==0) 1000L else 3000L;status=PlaybackStatus("Восстанавливаем соединение","Попытка $retries из 2",loading=true); handler.postDelayed({ if(!isFinishing&&!released) { player.prepare(); player.play() } },delay) }
                    else { terminalError=true;status=if(error.errorCode in 4000..5999) PlaybackStatus("Формат не поддерживается","Выберите другую дорожку или раздачу.") else PlaybackStatus("Видео недоступно","Проверьте соединение или выберите другую раздачу.",retry=true) }
                }
            })
            val progress=if(config.historyEnabled) db.syncDao().entity(config.scope,"progress",contentIdentity.ifBlank {"$releaseId|$path"})?.takeUnless {it.deleted}?.progress() else null
            player.setMediaItem(MediaItem.fromUri(uri),savedInstanceState?.getLong("position") ?: progress?.takeUnless { it.completed }?.positionMs ?: 0)
            startedAt=System.currentTimeMillis(); player.prepare(); player.playWhenReady=savedInstanceState?.getBoolean("playing",true) ?: true
            view.requestFocus()
            while(isActive) { delay(15000); save()
                if(!terminalError&&(player.playbackState==Player.STATE_BUFFERING || !firstFrame)) {
                    val elapsed=System.currentTimeMillis()-startedAt
                    if(elapsed>180_000) {terminalError=true;player.stop();status=PlaybackStatus("Не удалось загрузить видео","Повторите попытку или выберите другую раздачу.",retry=true)}
                    else if(elapsed>30_000) status=PlaybackStatus(if(firstFrame)"Буферизация" else "Готовим видео","Загрузка занимает больше времени. Можно подождать или выбрать другую раздачу.",loading=true)
                }
            }
        }
    }
    private fun switchEpisode(index:Int) {
        val episode=episodes.getOrNull(index) ?: return
        val config=playbackConfig ?: return
        if(!::player.isInitialized||released||index==episodeIndex||episodeLoad?.isActive==true)return
        if(!episode.uri.startsWith("asset:")&&runCatching {episode.uri.toHttpUrl().origin()==endpoint(if(config.managed)config.managedUrl else config.torrServer).origin()}.getOrDefault(false)!=true)return
        save()
        episodeLoad?.cancel();handler.removeCallbacksAndMessages(null)
        firstFrame=false;firstFrameSeen=false;terminalError=false;retries=0
        player.stop()
        episodeIndex=index;path=episode.path;fileId=episode.fileId;contentIdentity=episode.contentKey
        intent.putExtra("uri",episode.uri).putExtra("path",path).putExtra("fileId",fileId).putExtra("contentKey",contentIdentity)
        controlsVisible=true;status=PlaybackStatus("Готовим видео","Загружаем серию",loading=true)
        episodeLoad=scope.launch {
            val progress=if(config.historyEnabled)withContext(Dispatchers.IO) {db.syncDao().entity(config.scope,"progress",contentIdentity.ifBlank {"$releaseId|$path"})?.takeUnless {it.deleted}?.progress()} else null
            player.setMediaItem(MediaItem.fromUri(episode.uri),progress?.takeUnless {it.completed}?.positionMs ?: 0)
            startedAt=System.currentTimeMillis();player.prepare();player.play()
        }
    }
    private fun retryPlayback() {
        if(!::player.isInitialized||released)return
        terminalError=false;retries=0;startedAt=System.currentTimeMillis()
        status=PlaybackStatus("Повторная загрузка","Подключаемся к потоку",loading=true)
        player.prepare();player.play()
    }
    private fun save(ended:Boolean=false): Job? {
        if(!::player.isInitialized||released||!firstFrame||playbackConfig?.historyEnabled!=true) return null
        val duration=player.duration.takeIf { it>0 }; val position=player.currentPosition.coerceAtLeast(0)
        val p=Progress(contentIdentity.ifBlank {"$releaseId|$path"},releaseId,fileId,path,position,duration,System.currentTimeMillis(),ended || duration?.let { position>=it*0.95 }==true)
        return scope.launch { saveMutex.lock(); try { withContext(Dispatchers.IO) {val config=playbackConfig!!;if(Settings(applicationContext).config.first().profileKey!=config.profileKey)return@withContext;val engine=SyncEngine(ManagedApi(config,SecretStore(applicationContext)),db.syncDao());engine.enqueue("progress",p.key,p.syncJson(),playSession=playbackSession,sequence=++playbackSequence);runCatching {engine.sync()}} } finally {saveMutex.unlock()} }
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
                runCatching {val engine=SyncEngine(ManagedApi(c,SecretStore(applicationContext)),db.syncDao());engine.enqueue("preferences","profile",value);engine.sync()}
            }
        }.setOnDismissListener { panel=null; panelOpen=false; if(intent.getBooleanExtra("mobile",true))overlay.requestFocus() }.show()
    }
    private fun exitPlayback() {finalWrite=save();finish()}
    private fun videoSettings() {
        if(!::player.isInitialized||released)return
        val preferences=getSharedPreferences("playback-preferences",MODE_PRIVATE)
        val names=arrayOf("Качество", "Скорость · ${player.playbackParameters.speed}×", "Масштаб", "Автопереход · ${if(preferences.getBoolean("auto-next",true))"Вкл" else "Выкл"}", "Автопропуск опенинга/эндинга · ${if(preferences.getBoolean("auto-skip",false))"Вкл" else "Выкл"}")
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
        }}
    }
    private fun showVideoPanel(title:String,names:Array<String>,choose:(Int)->Unit) {
        val dialog=android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Dialog_Alert)).setTitle(title).setItems(names) {_,which->choose(which)}.create()
        panel=dialog;panelOpen=true
        dialog.setOnDismissListener {if(panel===dialog){panel=null;panelOpen=false}}
        dialog.show()
    }
    override fun onSaveInstanceState(outState: Bundle) { if(::player.isInitialized&&!released) { outState.putLong("position",player.currentPosition); outState.putBoolean("playing",player.playWhenReady); outState.putFloat("speed",player.playbackParameters.speed);outState.putInt("resize-mode",view.resizeMode) }; super.onSaveInstanceState(outState) }
    override fun onStop() { if(::player.isInitialized&&!released&&!isInPictureInPictureMode) { player.pause(); save() }; super.onStop() }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode:Boolean,newConfig:Configuration) { super.onPictureInPictureModeChanged(inPictureInPictureMode,newConfig); overlay.visibility=if(inPictureInPictureMode) android.view.View.GONE else android.view.View.VISIBLE }
    private fun releasePlayer() {if(!released) {released=true;session?.release();if(::player.isInitialized) player.release()}}
    override fun onDestroy() {
        val pending=save() ?: finalWrite; handler.removeCallbacksAndMessages(null); releasePlayer()
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
                if(panel!=null)panel?.dismiss() else tvBackHandler?.invoke() ?: exitPlayback()
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
