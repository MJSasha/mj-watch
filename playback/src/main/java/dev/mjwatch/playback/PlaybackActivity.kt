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

@UnstableApi
class PlaybackActivity : ComponentActivity() {
    companion object {
        private var active:java.lang.ref.WeakReference<PlaybackActivity>?=null
        fun hasActivePlayback()=active?.get()?.let {!it.released&&!it.isFinishing}==true
        fun stopActivePlayback() {active?.get()?.let {it.finalWrite=it.save();it.releasePlayer();it.finish()}}
    }
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
    private var releaseId=""; private var path=""; private var fileId=0
    private lateinit var dao: WatchDao
    private lateinit var status: TextView
    private lateinit var db: WatchDatabase
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active?.get()?.takeIf {it!==this}?.let {previous -> previous.finalWrite=previous.save();previous.releasePlayer();previous.finish()}
        active=java.lang.ref.WeakReference(this)
        if(intent.getBooleanExtra("mobile",true)&&resources.configuration.smallestScreenWidthDp<600) requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        releaseId=intent.getStringExtra("releaseId") ?: ""; path=intent.getStringExtra("path") ?: ""; fileId=intent.getIntExtra("fileId",0)
        db=Room.databaseBuilder(applicationContext, WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2).enableMultiInstanceInvalidation().build(); dao=db.dao()
        val root=FrameLayout(this); view=PlayerView(this).apply { setShowSubtitleButton(true); setShowNextButton(false); setShowPreviousButton(false); setShowFastForwardButton(true); setShowRewindButton(true); controllerShowTimeoutMs=if((getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled) 0 else 4000 }
        root.addView(view,FrameLayout.LayoutParams(-1,-1))
        status=TextView(this).apply { text="Подготовка видео…"; setTextColor(-1); setBackgroundColor(0xDD0C0E15.toInt()); setPadding(24,24,24,24) }
        root.addView(status, FrameLayout.LayoutParams(-1,-2,android.view.Gravity.CENTER))
        view.useController=false
        overlay=androidx.compose.ui.platform.ComposeView(this).apply {setContent {
            PlayerControls(player={if(::player.isInitialized&&!released) player else null},title=intent.getStringExtra("title") ?: path,visible=controlsVisible,mobile=intent.getBooleanExtra("mobile",true),keepVisible=(getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled,onVisibility={controlsVisible=it},onBack={finish()},onTracks={tracks(this)},onFit={view.resizeMode=if(view.resizeMode==0) 4 else 0},onPip={enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())})
        }}
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
        androidx.core.view.WindowCompat.getInsetsController(window,root).apply {hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE}
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) { override fun handleOnBackPressed() { if(panel!=null) { panel?.dismiss(); panel=null } else finish() } })
        scope.launch {
            val config=Settings(applicationContext).config.first()
            val uri=intent.getStringExtra("uri") ?: return@launch finish()
            if(!uri.startsWith("asset:")&&runCatching {uri.toHttpUrl().origin()==endpoint(config.torrServer).origin()}.getOrDefault(false)!=true) {
                status.text="Адрес сервера изменён. Вернитесь к карточке и выберите раздачу заново.";return@launch
            }
            val headers=mutableMapOf<String,String>()
            val auth=SecretStore(applicationContext).get("torrAuth:${config.torrServer}"); if(!uri.startsWith("asset:")&&auth.isNotBlank()) headers["Authorization"]=auth
            val http=OkHttpDataSource.Factory(guardedClient(config.copy(jackett="",indexers="",metadataProvider="none",torznabPaths=emptyMap())).newBuilder().callTimeout(0,java.util.concurrent.TimeUnit.MILLISECONDS).build()).setDefaultRequestProperties(headers)
            player=ExoPlayer.Builder(this@PlaybackActivity).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this@PlaybackActivity,http)).setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(0)))
                .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            player.setHandleAudioBecomingNoisy(true)
            val preferences=getSharedPreferences("playback-preferences",MODE_PRIVATE)
            player.trackSelectionParameters=player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(preferences.getString("audio-language",null))
                .setPreferredTextLanguage(preferences.getString("subtitle-language",null))
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,preferences.getBoolean("subtitles-off",false)).build()
            view.player=player; session=MediaSession.Builder(this@PlaybackActivity,player).build()
            player.addListener(object: Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame=true; firstFrameSeen=true; status.visibility=android.view.View.GONE }
                override fun onPlaybackStateChanged(state: Int) {
                    if(state==Player.STATE_BUFFERING) { status.text="Буферизация… · Назад — отменить"; status.visibility=android.view.View.VISIBLE; startedAt=System.currentTimeMillis() }
                    if(state==Player.STATE_READY&&firstFrame) status.visibility=android.view.View.GONE
                    if(state==Player.STATE_ENDED) { save(true); status.text="Просмотр завершён"; status.visibility=android.view.View.VISIBLE }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { if(!isPlaying) save() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason:Int) { save() }
                override fun onPlayerError(error: PlaybackException) {
                    save(); val network=error.errorCode in 2000..2999 && error.errorCode!=PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                    if(network&&retries<2) { val delay=if(retries++==0) 1000L else 3000L; handler.postDelayed({ if(!isFinishing&&!released) { player.prepare(); player.play() } },delay) }
                    else { terminalError=true;status.text=if(error.errorCode in 4000..5999) "Устройство не поддерживает этот формат. Выберите другую дорожку или раздачу." else "Видео недоступно. Позиция сохранена. Назад — другая раздача."; status.visibility=android.view.View.VISIBLE }
                }
            })
            val progress=dao.progress().first().firstOrNull { it.key=="$releaseId|$path" }
            player.setMediaItem(MediaItem.fromUri(uri),savedInstanceState?.getLong("position") ?: progress?.takeUnless { it.completed }?.positionMs ?: 0)
            startedAt=System.currentTimeMillis(); player.prepare(); player.playWhenReady=savedInstanceState==null
            view.requestFocus()
            while(isActive) { delay(5000); save()
                if(!terminalError&&(player.playbackState==Player.STATE_BUFFERING || !firstFrame)) {
                    val elapsed=System.currentTimeMillis()-startedAt
                    if(elapsed>180_000) {terminalError=true;player.stop();status.text="Подготовка остановлена. Выберите другую раздачу."}
                    else if(elapsed>60_000) { player.pause(); status.text="Видео не отвечает. Назад — другая раздача. Позиция сохранена." }
                    else if(elapsed>30_000) status.text="Подготовка занимает больше времени · Назад — отменить"
                }
            }
        }
    }
    private fun save(ended:Boolean=false): Job? {
        if(!::player.isInitialized||released) return null
        val duration=player.duration.takeIf { it>0 }; val position=player.currentPosition.coerceAtLeast(0)
        val p=Progress("$releaseId|$path",releaseId,fileId,path,position,duration,System.currentTimeMillis(),ended || duration?.let { position>=it*0.95 }==true)
        return scope.launch { saveMutex.lock(); try { withContext(Dispatchers.IO) {dao.put(p)} } finally {saveMutex.unlock()} }
    }
    private fun tracks(anchor:android.view.View) {
        if(!::player.isInitialized) return
        val names=mutableListOf("Субтитры выключены"); val choices=mutableListOf<Pair<Tracks.Group,Int>?>(null)
        player.currentTracks.groups.filter { it.type==C.TRACK_TYPE_AUDIO || it.type==C.TRACK_TYPE_TEXT }.forEach { group ->
            for(i in 0 until group.length) if(group.isTrackSupported(i)) {
                val f=group.getTrackFormat(i); names+="${if(group.type==C.TRACK_TYPE_AUDIO) "Аудио" else "Субтитры"}: ${f.label ?: f.language ?: "Не указано"}${if(group.isTrackSelected(i)) " ✓" else ""}"; choices+=group to i
            }
        }
        panel=android.app.AlertDialog.Builder(android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Dialog_Alert)).setTitle("Дорожки").setItems(names.toTypedArray()) { _,which ->
            val builder=player.trackSelectionParameters.buildUpon(); val selected=choices[which]
            val preferences=getSharedPreferences("playback-preferences",MODE_PRIVATE)
            if(selected==null) {builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);preferences.edit().putBoolean("subtitles-off",true).apply()} else {
                val language=selected.first.getTrackFormat(selected.second).language
                if(selected.first.type==C.TRACK_TYPE_AUDIO) preferences.edit().putString("audio-language",language).apply()
                else preferences.edit().putString("subtitle-language",language).putBoolean("subtitles-off",false).apply()
                builder.setTrackTypeDisabled(selected.first.type,false).setOverrideForType(TrackSelectionOverride(selected.first.mediaTrackGroup, selected.second))
            }; player.trackSelectionParameters=builder.build(); panel=null
        }.setOnDismissListener { panel=null; anchor.requestFocus() }.show()
    }
    override fun onSaveInstanceState(outState: Bundle) { if(::player.isInitialized) { outState.putLong("position",player.currentPosition); outState.putBoolean("playing",player.playWhenReady) }; super.onSaveInstanceState(outState) }
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
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(::player.isInitialized&&event.action==KeyEvent.ACTION_DOWN&&(!controlsVisible||event.keyCode==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) when(event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { player.seekBack(); controlsVisible=true; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { player.seekForward(); controlsVisible=true; return true }
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if(player.isPlaying) player.pause() else player.play(); controlsVisible=true; return true }
        }
        return super.dispatchKeyEvent(event)
    }
}
