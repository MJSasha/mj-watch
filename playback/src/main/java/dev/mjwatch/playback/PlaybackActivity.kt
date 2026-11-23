package dev.mjwatch.playback

import android.app.PictureInPictureParams
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
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
import coil.ImageLoader
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

private const val INITIAL_BUFFER_MS = 1500

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_OFFLINE="offlinePlayback"
        const val EXTRA_SEGMENT_QUEUE_SEASON = "segmentQueueSeason"

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
    private val queue by lazy {
        intent.getStringExtra(PlaybackQueue.EXTRA)?.let {PlaybackQueue.open(cacheDir,it)}
    }
    private var episodes by androidx.compose.runtime.mutableStateOf(emptyList<PlaybackEpisode>())
    private var episodeIndex by androidx.compose.runtime.mutableIntStateOf(-1)
    private var startupJob:Job?=null
    private var episodeLoad:Job?=null
    private var segmentLookup:Job?=null
    private var skipSegments by androidx.compose.runtime.mutableStateOf(emptyList<SkipSegment>())
    private var inPip by androidx.compose.runtime.mutableStateOf(false)
    private var mediaInteraction by androidx.compose.runtime.mutableIntStateOf(0)
    private var panelOpen by androidx.compose.runtime.mutableStateOf(false)
    private var tvBackHandler:(()->Unit)?=null
    private var controlsVisible by androidx.compose.runtime.mutableStateOf(true)
    private lateinit var overlay:androidx.compose.ui.platform.ComposeView
    private var released=false
    private var lastProgressWrite:Job?=null
    private var progressSync:Job?=null
    private var episodeCompleted=false
    private val decoderFallback=DecoderFallback()
    private var bufferPlan=PlaybackBuffer(4*1048576,4*1048576)
    private var bufferTargetBytes=4*1048576
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var frozenFrame: VideoFreezeFrame
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
    private var rememberedAudio:RememberedAudio?=null
    private var lastKnownTracks = Tracks.EMPTY
    private var status by androidx.compose.runtime.mutableStateOf<PlaybackStatus?>(bufferingStatus(false))
    private var artwork by androidx.compose.runtime.mutableStateOf<PlaybackArtwork?>(null)
    private var artworkJob: Job? = null
    private val networkBytes = AtomicLong()
    private var streamBytesPerSecond by androidx.compose.runtime.mutableStateOf<Double?>(null)
    private var torrentStats by androidx.compose.runtime.mutableStateOf<TorrentPlaybackStats?>(null)
    private var startupProgress by androidx.compose.runtime.mutableStateOf<Float?>(null)

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
        db=Room.databaseBuilder(applicationContext, WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).enableMultiInstanceInvalidation().build()
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        val root=FrameLayout(this).apply {setBackgroundColor(android.graphics.Color.BLACK)}
        val backdrop=VideoBackdrop(this)
        root.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
        view=PlayerView(this).apply {setBackgroundColor(android.graphics.Color.TRANSPARENT);setShutterBackgroundColor(android.graphics.Color.BLACK);setShowSubtitleButton(true); setShowNextButton(false); setShowPreviousButton(false); setShowFastForwardButton(true); setShowRewindButton(true); controllerShowTimeoutMs=if((getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled) 0 else 4000 }
        val surface = view.videoSurfaceView as SurfaceView
        val content = surface.parent as FrameLayout
        val shutter = content.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)
        frozenFrame = VideoFreezeFrame(this)
        content.addView(frozenFrame, content.indexOfChild(shutter) + 1, FrameLayout.LayoutParams(-1, -1))
        root.addView(view,FrameLayout.LayoutParams(-1,-1))
        view.useController=false
        overlay=androidx.compose.ui.platform.ComposeView(this).apply {setContent {
            PlayerControls(player={if(::player.isInitialized&&!released) player else null},title=intent.getStringExtra("title") ?: path,visible=controlsVisible,mobile=intent.getBooleanExtra("mobile",true),status=status?.let {if(offline)it.copy(backLabel="К загрузкам") else it},onRetry={retryPlayback()},keepVisible=(getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled,onVisibility={controlsVisible=it},onBack={exitPlayback()},onTracks={tracks()},onPip={enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build())},episodeCount=episodes.size,episodeIndex=episodeIndex,episodeLabel=episodes.getOrNull(episodeIndex)?.path.orEmpty(),onEpisode={switchEpisode(it)},advanceAfterCredits=::advanceAfterCredits,episodes=episodes,externalPanel=panelOpen||inPip,skipSegments=skipSegments,onTvBackHandler={tvBackHandler=it},onVideo={videoSettings()},mediaInteraction=mediaInteraction,beforeFirstFrame=!firstFrameSeen,artwork=artwork,metrics=if(intent.getStringExtra("torrentHandle")==null) null else PlaybackLoadingMetrics(
                torrentStats,streamBytesPerSecond,intent.getIntExtra("trackerSeeders",-1).takeIf {it>=0}),progress=startupProgress)
        }}
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
        scope.launch {while(isActive) {delay(1000);if(!released&&firstFrame&&lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))backdrop.sample(view.videoSurfaceView)}}
        scope.launch {
            while (isActive) {
                delay(250)
                if (!released && firstFrame && player.isPlaying) frozenFrame.sample(surface)
            }
        }
        androidx.core.view.WindowCompat.getInsetsController(window,root).apply {hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE}
        onBackPressedDispatcher.addCallback(this,object: OnBackPressedCallback(true) { override fun handleOnBackPressed() = handlePlaybackBack() })
        startupJob=scope.launch {
            episodes=withContext(Dispatchers.IO) {
                val stored=queue
                val loaded=if(stored==null) PlaybackEpisode.decode(intent.getStringExtra("episodes")) else try {
                    stored.read()
                } catch(e:IOException) {
                    android.util.Log.w("MJWatch","Unable to read playback queue",e)
                    emptyList()
                }
                if(offline)loaded.filter {isLocalFile(it.uri)} else loaded
            }
            episodeIndex=episodes.indexOfFirst {it.path==path&&it.fileId==fileId}
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
                val managedApi=if(config.managed&&!uri.startsWith("asset:")) {
                    try {
                        withContext(Dispatchers.IO) {ManagedApi(config,SecretStore(applicationContext)).also {it.authorized("me")}}
                    } catch(e:CancellationException) {throw e}
                    catch(e:Exception) {
                        status=PlaybackStatus("Сессия недоступна","Вернитесь в аккаунт и проверьте подключение.")
                        return@launch
                    }
                } else null
                if(managedApi!=null) launch(Dispatchers.IO) {
                    while(isActive) {delay(60_000);runCatching {managedApi.authorized("me")}}
                }
                val http=withContext(Dispatchers.IO) {
                    val auth=if(!config.managed&&!uri.startsWith("asset:"))SecretStore(applicationContext).get("torrAuth:${config.torrServer}") else ""
                    val client=managedApi?.client ?: guardedClient(config.copy(jackett="",indexers="",metadataProvider="none",torznabPaths=emptyMap()))
                    OkHttpDataSource.Factory(client.newBuilder().callTimeout(0,java.util.concurrent.TimeUnit.MILLISECONDS).build())
                        .setDefaultRequestProperties(if(auth.isNotBlank())mapOf("Authorization" to auth) else emptyMap())
                        .setTransferListener(streamTransferListener)
                }
                dataSource=DefaultDataSource.Factory(this@PlaybackActivity,http)
            }
            if(!offline) { loadArtwork(config); trackLoading(config) }
            val memory=android.app.ActivityManager.MemoryInfo()
            val manager=getSystemService(android.app.ActivityManager::class.java)
            manager.getMemoryInfo(memory)
            val runtime=Runtime.getRuntime()
            bufferPlan=playbackBuffer(runtime.maxMemory(),runtime.totalMemory()-runtime.freeMemory(),memory.availMem,manager.isLowRamDevice)
            val requested=getSharedPreferences("playback-preferences",MODE_PRIVATE).getInt("buffer-mib",0)
            bufferTargetBytes=if(requested<=0)bufferPlan.recommendedBytes else (requested.toLong()*1048576).coerceIn(4L*1048576,bufferPlan.maximumBytes.toLong()).toInt()
            val loadControl=androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000,120000,INITIAL_BUFFER_MS,3000)
                .setTargetBufferBytes(bufferTargetBytes).setPrioritizeTimeOverSizeThresholds(false).build()
            player=ExoPlayer.Builder(this@PlaybackActivity,decoderFallback.renderers(this@PlaybackActivity)).setLoadControl(loadControl).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(StreamLoadErrorPolicy()))
                .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            player.setHandleAudioBecomingNoisy(true)
            val preferences=getSharedPreferences("playback-preferences",MODE_PRIVATE)
            rememberedAudio=audioPreferenceKey(config)?.let {RememberedAudio.decode(preferences.getString(it,null))}
            player.trackSelectionParameters=player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(rememberedAudio?.language ?: preferences.getString("audio-language",null))
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
                override fun onTracksChanged(tracks:Tracks) {
                    if (!tracks.isEmpty) lastKnownTracks = tracks
                    restoreAudio(tracks)
                }
                override fun onEvents(player:Player,events:Player.Events) {
                    view.keepScreenOn=player.isPlaying||(player.playWhenReady&&player.playbackState==Player.STATE_BUFFERING)
                }
                override fun onRenderedFirstFrame() {
                    firstFrame=true; firstFrameSeen=true; if(startupProgress!=null) startupProgress=1f; artworkJob?.cancel(); view.setKeepContentOnPlayerReset(true); status=null
                    frozenFrame.showLiveVideo()
                    surface.postOnAnimation { if (!released && firstFrame) frozenFrame.sample(surface) }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if(state==Player.STATE_BUFFERING) {
                        if (firstFrame) frozenFrame.hold()
                        status=bufferingStatus(firstFrame); startedAt=System.currentTimeMillis()
                    }
                    if(state==Player.STATE_READY&&firstFrame) {
                        frozenFrame.onReady()
                        status=null
                    }
                    if(state==Player.STATE_ENDED) {
                        save(true)
                        val hasMarkedTail=skipSegments.any {it.kind==SegmentKind.OUTRO||it.kind==SegmentKind.PREVIEW}
                        if(!intent.getBooleanExtra("mobile",true)&&hasMarkedTail&&player.playWhenReady&&episodeIndex>=0&&episodeIndex<episodes.lastIndex&&
                            preferences.getBoolean("auto-next",true)) {
                            // Leave the listener callback before replacing the current media item.
                            val next=episodeIndex+1
                            val sessionId=playbackSession
                            handler.post {if(sessionId==playbackSession&&player.playbackState==Player.STATE_ENDED)switchEpisode(next)}
                        }
                        status=PlaybackStatus("Просмотр завершён")
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    stablePlayback?.cancel()
                    if(!isPlaying)save() else stablePlayback=scope.launch {delay(20_000);retries=0}
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason:Int) { save() }
                override fun onPlayerError(error: PlaybackException) {
                    if (firstFrame) frozenFrame.hold(waitForFirstFrame = true)
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
            val progress=if(config.historyEnabled) db.syncDao().resumeProgress(config.scope,releaseId,contentIdentity.ifBlank {"$releaseId|$path"}) else null
            player.setMediaItem(mediaItem(uri),savedInstanceState?.getLong("position") ?: if(intent.hasExtra("remotePosition"))intent.getLongExtra("remotePosition",0).coerceAtLeast(0) else progress?.takeUnless { it.completed }?.positionMs ?: 0)
            startedAt=System.currentTimeMillis(); player.prepare(); player.playWhenReady=savedInstanceState?.getBoolean("playing",true) ?: true
            view.requestFocus()
            fetchSkipSegments(config)
            while(isActive) { delay(15000); save()
                if(!terminalError&&(player.playbackState==Player.STATE_BUFFERING || !firstFrame)) {
                    val elapsed=System.currentTimeMillis()-startedAt
                    if(elapsed>180_000) {terminalError=true;recoveryJob?.cancel();if(firstFrame)frozenFrame.hold(waitForFirstFrame=true);player.stop();status=if(offline) localFailureStatus(retry=true) else PlaybackStatus("Не удалось загрузить видео","Повторите попытку или выберите другую раздачу.",retry=true)}
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
    private val streamTransferListener = object : TransferListener {
        override fun onTransferInitializing(source:DataSource,dataSpec:DataSpec,isNetwork:Boolean) = Unit
        override fun onTransferStart(source:DataSource,dataSpec:DataSpec,isNetwork:Boolean) = Unit
        override fun onTransferEnd(source:DataSource,dataSpec:DataSpec,isNetwork:Boolean) = Unit
        override fun onBytesTransferred(source:DataSource,dataSpec:DataSpec,isNetwork:Boolean,bytesTransferred:Int) {
            if(isNetwork) networkBytes.addAndGet(bytesTransferred.toLong())
        }
    }

    private fun trackLoading(config: Config) {
        scope.launch {
            snapshotFlow { status?.loading == true && !firstFrameSeen }.collectLatest { loading ->
                if (!loading) return@collectLatest
                while (isActive) {
                    if (::player.isInitialized && player.playbackState == Player.STATE_BUFFERING && player.totalBufferedDuration > 0) {
                        // This is the player's start buffer in playout time, independent of TorrServer's cache.
                        val target = INITIAL_BUFFER_MS * player.playbackParameters.speed
                        startupProgress = (player.totalBufferedDuration / target).coerceIn(0f, .99f)
                    }
                    delay(200)
                }
            }
        }
        if (intent.getStringExtra("torrentHandle").isNullOrBlank()) return
        scope.launch {
            snapshotFlow { status?.loading == true }.collectLatest { loading ->
                if (!loading) return@collectLatest
                var previousBytes = networkBytes.get()
                var previousTime = android.os.SystemClock.elapsedRealtime()
                try {
                    while (isActive) {
                        delay(500)
                        val now = android.os.SystemClock.elapsedRealtime()
                        val bytes = networkBytes.get()
                        val count = bytes - previousBytes
                        if (count > 0 || streamBytesPerSecond != null) {
                            streamBytesPerSecond = count * 1000.0 / (now - previousTime).coerceAtLeast(1)
                        }
                        previousBytes = bytes
                        previousTime = now
                    }
                } finally {
                    streamBytesPerSecond = null
                }
            }
        }
        scope.launch {
            val source = try {
                withContext(Dispatchers.IO) { TorrentPlaybackStatsSource(config, SecretStore(applicationContext)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            snapshotFlow { status?.loading == true }.collectLatest { loading ->
                if (!loading) return@collectLatest
                try {
                    while (isActive) {
                        val handle = intent.getStringExtra("torrentHandle") ?: break
                        val index = fileId
                        val stats = try {
                            withTimeoutOrNull(4_000) { withContext(Dispatchers.IO) { source.read(handle, index) } }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (handle == intent.getStringExtra("torrentHandle") && index == fileId) torrentStats = stats
                        delay(1_250)
                    }
                } finally {
                    torrentStats = null
                }
            }
        }
    }

    private fun loadArtwork(config: Config) {
        if (config.metadataProvider != "tmdb") return
        val metadataId = (intent.getStringExtra("artMetadataId") ?: intent.getStringExtra("metadataId"))
            ?.takeIf { it.matches(Regex("tmdb:(movie|tv):[0-9]+")) }
        val backdrop = intent.getStringExtra("artBackdrop")
        val poster = intent.getStringExtra("artPoster")
        val logo = intent.getStringExtra("artLogo")
        if (metadataId == null && backdrop == null && poster == null && logo == null) return
        artworkJob = scope.launch {
            try {
                val (secrets,client) = withContext(Dispatchers.IO) {
                    val secrets=SecretStore(applicationContext)
                    secrets to if (config.managed) managedClient(config, secrets) else guardedClient(config)
                }
                val loader = ImageLoader.Builder(applicationContext).okHttpClient(client).memoryCache(null).build()
                artwork = PlaybackArtwork(loader, backdrop, poster, logo)
                if (logo == null && metadataId != null) {
                    val metadata = withContext(Dispatchers.IO) {
                        val adapter = if (config.managed) ManagedMetadata(ManagedApi(config, secrets))
                            else TmdbAdapter(secrets.get("tmdb"), client)
                        MetadataRepository(adapter, db.dao(), if (config.managed) config.scope else "direct")
                            .details(metadataId, withArtwork = true)
                    }
                    if (metadata != null) {
                        artwork = PlaybackArtwork(loader, metadata.backdrop ?: backdrop, metadata.poster ?: poster, metadata.logo)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The title is available while optional artwork is unavailable.
            }
        }
    }

    private fun advanceAfterCredits(automatic: Boolean): Boolean {
        if (episodeIndex < 0) return false
        if (automatic && !getSharedPreferences("playback-preferences", MODE_PRIVATE).getBoolean("auto-next", true)) return false
        return switchEpisode(episodeIndex + 1, completed = true)
    }

    private fun switchEpisode(index:Int, completed:Boolean = false): Boolean {
        val episode=episodes.getOrNull(index) ?: return false
        val config=playbackConfig ?: return false
        if(!::player.isInitialized||released||index==episodeIndex||episodeLoad?.isActive==true)return false
        if(!offline&&!validStreamUri(episode.uri,config))return false
        save(completed)
        val previousWrite=lastProgressWrite
        episodeLoad?.cancel();recoveryJob?.cancel();stablePlayback?.cancel();handler.removeCallbacksAndMessages(null)
        segmentLookup?.cancel();skipSegments=emptyList()
        firstFrame=false;firstFrameSeen=false;episodeCompleted=false;terminalError=false;retries=0
        startupProgress=null;torrentStats=null
        frozenFrame.clear()
        view.setKeepContentOnPlayerReset(false)
        player.stop();decoderFallback.reset()
        lastKnownTracks = Tracks.EMPTY
        if (C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
        }
        episodeIndex=index;path=episode.path;fileId=episode.fileId;contentIdentity=episode.contentKey
        tvSessionPlayer?.episodeChanged()
        remoteContentId=remoteContentId.substringBeforeLast(':',"")+":"+fileId
        playbackSession=java.util.UUID.randomUUID().toString()
        intent.putExtra("uri",episode.uri).putExtra("path",path).putExtra("fileId",fileId).putExtra("contentKey",contentIdentity)
        intent.removeExtra("segmentSeason")
        intent.removeExtra("segmentEpisode")
        controlsVisible=true;status=PlaybackStatus("Готовим видео","Загружаем серию",loading=true)
        episodeLoad=scope.launch {
            previousWrite?.join()
            val progress=if(config.historyEnabled)withContext(Dispatchers.IO) {db.syncDao().resumeProgress(config.scope,releaseId,contentIdentity.ifBlank {"$releaseId|$path"})} else null
            player.setMediaItem(mediaItem(episode.uri),progress?.takeUnless {it.completed}?.positionMs ?: 0)
            startedAt=System.currentTimeMillis();player.prepare();player.play()
            fetchSkipSegments(config)
        }
        return true
    }

    private fun fetchSkipSegments(config:Config) {
        if (offline) {
            val id = intent.getStringExtra("offlineDownloadId") ?: return
            segmentLookup = scope.launch {
                try {
                    skipSegments = OfflineDownloads.get(applicationContext).localSegments(config, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Local video is playable without markers.
                }
            }
            return
        }
        val metadataId=intent.getStringExtra("metadataId")?.takeIf {it.startsWith("tmdb:tv:")} ?: return
        if(config.demo)return
        val currentPath=path
        val season=intent.getIntExtra("segmentSeason",-1).takeIf {it>0}
            ?: intent.getIntExtra(EXTRA_SEGMENT_QUEUE_SEASON,-1).takeIf {it>0}
        val episode=intent.getIntExtra("segmentEpisode",-1).takeIf {it>0}
        segmentLookup=scope.launch {
            while(player.duration<=0||!player.isCurrentMediaItemSeekable) {
                if(terminalError||player.playbackState==Player.STATE_ENDED||
                    (player.duration>0&&player.playbackState==Player.STATE_READY))return@launch
                delay(250)
            }
            val duration=player.duration
            try {
                skipSegments=withContext(Dispatchers.IO) {
                    loadSkipSegments(config,SecretStore(applicationContext),metadataId,currentPath,duration,season,episode)
                }
            } catch(e:CancellationException) {
                throw e
            } catch(_:Exception) {
                // A failed lookup must leave ordinary playback available.
            }
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
                    val previousEpisodes=episodes
                    val currentPath=path
                    val raw=intent.getStringExtra("recoveryRelease")
                    val previousHandle=intent.getStringExtra("torrentHandle")
                    val source=withContext(Dispatchers.IO) {
                        val secrets=SecretStore(applicationContext)
                        if(Settings(applicationContext).config.first().profileKey!=config.profileKey)error("Подключение изменено")
                        val managedApi=if(config.managed)ManagedApi(config,secrets).also {it.authorized("me")} else null
                        if(raw==null)null else {
                            val json=org.json.JSONObject(raw)
                            val hash=json.getString("hash")
                            val release=Release(json.getString("id"),json.getString("title"),json.getString("source"),hash=hash,
                                magnet=json.optString("magnet").takeIf {it.isNotBlank()},download=json.optString("download").takeIf {it.isNotBlank()})
                            val client=if(managedApi!=null&&endpoint(config.jackett).origin()==managedApi.base.origin())managedApi.client else guardedClient(config)
                            val torrents=TorznabRepository(config,secrets.get("jackett:${config.jackett}"),client)
                            val gateway=if(managedApi!=null)ManagedGateway(managedApi,torrents) else TorrServerGateway(config,secrets.get("torrAuth:${config.torrServer}"),client,torrents)
                            val handle=recoverTorrent(gateway,release,previousHandle ?: hash,hash,currentPath)
                            val files=handle.files.groupBy {it.path}
                            val current=files.getValue(currentPath).single()
                            val restored=previousEpisodes.map {episode->
                                files[episode.path]?.singleOrNull()?.let {file->
                                    episode.copy(uri=gateway.stream(handle,file).uri,fileId=file.serverFileId)
                                } ?: episode
                            }
                            val uri=gateway.stream(handle,current).uri
                            val stored=queue
                            stored?.write(restored)
                            RecoveredSource(handle.hash,current.serverFileId,uri,restored,
                                if(stored==null)PlaybackEpisode.encode(restored) else null)
                        }
                    }
                    if(released)return@launch
                    if(source!=null) {
                        fileId=source.fileId
                        intent.putExtra("torrentHandle",source.handle).putExtra("fileId",fileId).putExtra("uri",source.uri)
                        episodes=source.episodes
                        source.legacyQueue?.let {intent.putExtra("episodes",it)}
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

    private data class RecoveredSource(val handle:String,val fileId:Int,val uri:String,
        val episodes:List<PlaybackEpisode>,val legacyQueue:String?)

    private fun save(ended:Boolean=false) {
        val config=playbackConfig ?: return
        if(!::player.isInitialized || released || !firstFrame || !config.historyEnabled) return
        val duration=player.duration.takeIf { it>0 }
        val position=player.currentPosition.coerceAtLeast(0)
        episodeCompleted=episodeCompleted || ended || duration?.let { position>=it*0.95 }==true
        val progress=Progress(contentIdentity.ifBlank {"$releaseId|$path"},releaseId,fileId,path,position,duration,System.currentTimeMillis(),episodeCompleted)
        val opening=LibraryEntry(releaseId,intent.getStringExtra("title").orEmpty().ifBlank {path},"MJWatch",
            openedAt=progress.updatedAt,metadataId=intent.getStringExtra("metadataId")?.takeIf {it.isNotBlank()})
        val sessionId=playbackSession
        val sequence=++playbackSequence
        lastProgressWrite=scope.launch {
            val engine=saveMutex.withLock {
                withContext(Dispatchers.IO) {
                    val current=Settings(applicationContext).config.first()
                    if(current.profileKey!=config.profileKey) return@withContext null
                    SyncEngine(ManagedApi(current,SecretStore(applicationContext)),db.syncDao()).also {
                        it.recordPlayback(opening,progress,sessionId,sequence)
                    }
                }
            }
            if(engine!=null&&!offline&&progressSync?.isActive!=true) {
                progressSync=scope.launch(Dispatchers.IO) {
                    try {engine.sync()}
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){ /* Room keeps the pending changes for the next sync. */ }
                }
            }
        }
    }
    private fun audioPreferenceKey(config:Config):String? =
        releaseId.takeIf {it.isNotBlank()}?.let {"audio-track:${config.profileKey}:$it"}

    private fun restoreAudio(tracks:Tracks) {
        if(released||tracks.isEmpty)return
        val saved=rememberedAudio ?: return
        val target=tracks.playbackTracks().filter {(group,index)->
            group.type==C.TRACK_TYPE_AUDIO&&saved.matches(group.getTrackFormat(index))
        }.drop(saved.ordinal).firstOrNull()?.let {(group,index)->TrackSelectionOverride(group.mediaTrackGroup,index)}
        val parameters=player.trackSelectionParameters
        val current=parameters.overrides.values.firstOrNull {it.type==C.TRACK_TYPE_AUDIO}
        if(current==target)return
        val updated=parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if(target!=null)updated.addOverride(target)
        player.trackSelectionParameters=updated.build()
    }

    private fun tracks() {
        if(!::player.isInitialized||released)return
        val audioRecovery = status?.audioRecovery == true
        val tracks = player.currentTracks.takeUnless { audioRecovery && it.isEmpty } ?: lastKnownTracks
        val choices = tracks.playbackTracks().toList()
        val visibleChoices = choices.withIndex().filter { (_, entry) ->
            val (group, index) = entry
            !audioRecovery || (group.type == C.TRACK_TYPE_AUDIO && !group.isTrackSelected(index))
        }
        val labels = visibleChoices.map { (_, track) ->
            val (group, index) = track
            val format = group.getTrackFormat(index)
            val kind = if (group.type == C.TRACK_TYPE_AUDIO) "Аудио" else "Субтитры"
            "$kind: ${trackName(format.label, format.language)}"
        }
        val labelCounts = labels.groupingBy { it }.eachCount()
        val trackRows = visibleChoices.mapIndexed { position, (_, track) ->
            val (group, index) = track
            val icon = if (group.type == C.TRACK_TYPE_AUDIO) Icons.Outlined.VolumeUp else Icons.Outlined.Subtitles
            val label = labels[position]
            val number = (position + 1).toString().takeIf { labelCounts.getValue(label) > 1 }
            PlaybackMenuRow(label, icon, value = number, selected = group.isTrackSelected(index))
        }
        val rows = if (audioRecovery) trackRows + PlaybackMenuRow("Продолжить без звука", Icons.Outlined.VolumeOff)
            else listOf(PlaybackMenuRow("Субтитры выключены", Icons.Outlined.SubtitlesOff,
                selected=choices.none {(group,index)->group.type==C.TRACK_TYPE_TEXT&&group.isTrackSelected(index)})) + trackRows
        showPlaybackMenu(if (audioRecovery) "Аудиодорожки" else "Аудио и субтитры",Icons.Outlined.Subtitles,rows) {which,_ ->
            val builder=player.trackSelectionParameters.buildUpon()
            val selected=visibleChoices.getOrNull(if (audioRecovery) which else which-1)
            val preferences=getSharedPreferences("playback-preferences",MODE_PRIVATE)
            if (audioRecovery && selected == null) {
                player.trackSelectionParameters = builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                restartWithAudioSelection()
                return@showPlaybackMenu
            }
            if (selected == null) {
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                preferences.edit().putBoolean("subtitles-off", true).apply()
            } else {
                val (group,index)=selected.value
                val format=group.getTrackFormat(index)
                builder.setTrackTypeDisabled(group.type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                if(group.type==C.TRACK_TYPE_AUDIO) {
                    val choice=RememberedAudio(format.language,format.label,0)
                    val ordinal=choices.take(selected.index).count {(previous,position)->
                        previous.type==C.TRACK_TYPE_AUDIO&&choice.matches(previous.getTrackFormat(position))
                    }
                    val exactChoice=choice.copy(ordinal=ordinal)
                    rememberedAudio=exactChoice
                    preferences.edit().putString("audio-language",format.language).apply {
                        playbackConfig?.let(::audioPreferenceKey)?.let {putString(it,exactChoice.encode())}
                    }.apply()
                    builder.setPreferredAudioLanguage(format.language)
                } else {
                    preferences.edit().putString("subtitle-language",format.language).putBoolean("subtitles-off",false).apply()
                    builder.setPreferredTextLanguage(format.language)
                }
            }
            save()
            player.trackSelectionParameters = builder.build()
            if (audioRecovery) restartWithAudioSelection()
            val c=playbackConfig
            if(c?.historyEnabled==true)scope.launch(Dispatchers.IO){
                if(Settings(applicationContext).config.first().profileKey!=c.profileKey)return@launch
                val value=org.json.JSONObject().put("quality",preferences.getString("quality","Все")).put("audioLanguage",preferences.getString("audio-language",null)).put("subtitleLanguage",if(preferences.getBoolean("subtitles-off",false))"off" else preferences.getString("subtitle-language",null))
                runCatching {val engine=SyncEngine(ManagedApi(c,SecretStore(applicationContext)),db.syncDao());engine.enqueue("preferences","profile",value);if(!offline)engine.sync()}
            }
        }
    }
    private fun restartWithAudioSelection() {
        decoderFallback.reset()
        terminalError = false
        startedAt = System.currentTimeMillis()
        player.prepare()
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
        val heights=player.currentTracks.groups.filter {it.type==C.TRACK_TYPE_VIDEO}.flatMap {group->
            (0 until group.length).filter {group.isTrackSupported(it)}.map {group.getTrackFormat(it).height}
        }.filter {it>0}.distinct().sortedDescending()
        val fitModes=listOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL)
        val fitLabels=listOf("Вписать","Заполнить","Растянуть")
        val autoNext=preferences.getBoolean("auto-next",true)
        val heightLimit=preferences.getInt("max-video-height",Int.MAX_VALUE)
        val rows=buildList {
            add(PlaybackMenuRow("Скорость",Icons.Outlined.Speed,"${player.playbackParameters.speed}×",opensMenu=true))
            add(PlaybackMenuRow("Масштаб",Icons.Outlined.AspectRatio,fitLabels.getOrNull(fitModes.indexOf(view.resizeMode)),opensMenu=true))
            add(PlaybackMenuRow("Автопереход",Icons.Outlined.SkipNext,if(autoNext)"Вкл" else "Выкл"))
            add(PlaybackMenuRow("Пропуск фрагментов",Icons.Outlined.FastForward,opensMenu=true))
            add(PlaybackMenuRow("Буфер",Icons.Outlined.Storage,"${bufferTargetBytes/1048576} МиБ",opensMenu=true))
            if(heights.size>1)add(PlaybackMenuRow("Качество",Icons.Outlined.HighQuality,
                if(heightLimit==Int.MAX_VALUE)"Авто" else "До ${heightLimit}p",opensMenu=true))
        }
        showPlaybackMenu("Параметры видео",Icons.Outlined.Settings,rows) {which,_ -> when(which) {
            0 -> {
                val speeds=listOf(.5f,.75f,1f,1.25f,1.5f,2f)
                showPlaybackMenu("Скорость",Icons.Outlined.Speed,speeds.map {
                    PlaybackMenuRow("${it}×",selected=player.playbackParameters.speed==it)
                }) {selection,_ ->
                    val speed=speeds[selection]
                    player.setPlaybackSpeed(speed)
                    preferences.edit().putFloat("speed",speed).apply()
                }
            }
            1 -> showPlaybackMenu("Масштаб",Icons.Outlined.AspectRatio,fitLabels.mapIndexed {index,label->
                PlaybackMenuRow(label,selected=view.resizeMode==fitModes[index])
            }) {selection,_ ->
                view.resizeMode=fitModes[selection]
                preferences.edit().putInt("resize-mode",view.resizeMode).apply()
            }
            2 -> preferences.edit().putBoolean("auto-next",!autoNext).apply()
            3 -> showSkipSettings(preferences)
            4 -> {
                val maximum=bufferPlan.maximumBytes/1048576
                val choices=(listOf(16,32,64,128).filter{it<=maximum}+maximum).distinct().sorted()
                val saved=preferences.getInt("buffer-mib",0)
                val options=listOf(PlaybackMenuRow("Авто",value="${bufferPlan.recommendedBytes/1048576} МиБ",selected=saved==0))+
                    choices.map {PlaybackMenuRow("$it МиБ",value=if(it==maximum)"Максимум" else null,selected=it==saved)}
                val buffered=((player.bufferedPosition-player.currentPosition).coerceAtLeast(0)/1000)
                showPlaybackMenu("Буфер",Icons.Outlined.Storage,options,
                    subtitle="Загружено $buffered с. Размер применяется при следующем запуске видео.") {selection,_ ->
                    preferences.edit().putInt("buffer-mib",if(selection==0)0 else choices[selection-1]).apply()
                    Toast.makeText(this,"Буфер сохранён для следующего запуска видео",Toast.LENGTH_SHORT).show()
                }
            }
            5 -> {
                val options=listOf(PlaybackMenuRow("Авто",selected=heightLimit==Int.MAX_VALUE))+
                    heights.map {PlaybackMenuRow("До ${it}p",selected=heightLimit==it)}
                showPlaybackMenu("Качество",Icons.Outlined.HighQuality,options) {selection,_ ->
                    val limit=if(selection==0)Int.MAX_VALUE else heights[selection-1]
                    save()
                    player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).setMaxVideoSize(Int.MAX_VALUE,limit).build()
                    preferences.edit().putInt("max-video-height",limit).apply()
                }
            }
        }}
    }

    private fun showSkipSettings(preferences:android.content.SharedPreferences) {
        val kinds=SegmentKind.entries
        val icons=listOf(Icons.Outlined.Movie,Icons.Outlined.History,Icons.Outlined.Subtitles,Icons.Outlined.SkipNext)
        val timing=preferences.autoSkip()
        val startTitle="Начало автопропуска"
        val durationTitle="Время отсчёта"
        val options=listOf(
            PlaybackMenuRow(startTitle,Icons.Outlined.Timer,value=timing.start.label,opensMenu=true),
            PlaybackMenuRow(durationTitle,Icons.Outlined.Timer,value=autoSkipDurationLabel(timing.seconds),opensMenu=true),
        )+
            kinds.mapIndexed {index,kind->PlaybackMenuRow(kind.label,icons[index],selected=preferences.segmentEnabled(kind))}
        showPlaybackMenu("Пропуск фрагментов",Icons.Outlined.FastForward,options,toggleRows=true,onCredits={
            runCatching {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://skipdb.tv/license")))}
        }) {index,checked->
            when(index) {
                0 -> showPlaybackMenu(startTitle,Icons.Outlined.Timer,AutoSkipStart.entries.map {
                    PlaybackMenuRow(it.label,selected=it==timing.start)
                }) {selection,_->
                    preferences.edit().putString(AUTO_SKIP_START,AutoSkipStart.entries[selection].name).apply()
                    showSkipSettings(preferences)
                }
                1 -> showPlaybackMenu(durationTitle,Icons.Outlined.Timer,AUTO_SKIP_DURATIONS.map {
                    PlaybackMenuRow(autoSkipDurationLabel(it),selected=it==timing.seconds)
                }) {selection,_->
                    preferences.edit().putInt(AUTO_SKIP_SECONDS,AUTO_SKIP_DURATIONS[selection]).apply()
                    showSkipSettings(preferences)
                }
                else -> preferences.edit().putBoolean(kinds[index-2].preferenceKey(),checked).apply()
            }
        }
    }

    private fun showPlaybackMenu(
        title:String, icon:ImageVector, rows:List<PlaybackMenuRow>, subtitle:String?=null,
        toggleRows:Boolean=false, onCredits:(()->Unit)?=null, choose:(Int,Boolean)->Unit,
    ) {
        val mobile=intent.getBooleanExtra("mobile",true)
        val dialog=createPlaybackMenu(this,title,icon,rows,!mobile,subtitle,toggleRows,onCredits,choose)
        panel=dialog;panelOpen=true
        dialog.setOnDismissListener {
            if(panel===dialog){
                panel=null;panelOpen=false
                if(mobile)overlay.requestFocus()
            }
        }
        dialog.show()
    }
    override fun onSaveInstanceState(outState: Bundle) { if(::player.isInitialized&&!released) { outState.putLong("position",player.currentPosition); outState.putBoolean("playing",player.playWhenReady); outState.putFloat("speed",player.playbackParameters.speed);outState.putInt("resize-mode",view.resizeMode) }; super.onSaveInstanceState(outState) }
    override fun onStop() { if(::player.isInitialized&&!released&&!isInPictureInPictureMode) { player.pause(); save() }; super.onStop() }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode:Boolean,newConfig:Configuration) { super.onPictureInPictureModeChanged(inPictureInPictureMode,newConfig); inPip=inPictureInPictureMode; overlay.visibility=if(inPictureInPictureMode) android.view.View.GONE else android.view.View.VISIBLE }
    private fun releasePlayer() {if(!released) {released=true;startupJob?.cancel();episodeLoad?.cancel();segmentLookup?.cancel();recoveryJob?.cancel();stablePlayback?.cancel();session?.release();if(::player.isInitialized) player.release()}}
    override fun onDestroy() {
        artworkJob?.cancel()
        artwork?.loader?.shutdown()
        save()
        val pending=lastProgressWrite; handler.removeCallbacksAndMessages(null); releasePlayer()
        val finished=isFinishing
        if(active?.get()===this) active=null
        // Finish the final atomic Room write before closing the database.
        scope.launch {
            pending?.join()
            startupJob?.join()
            recoveryJob?.join()
            progressSync?.join()
            withContext(Dispatchers.IO) {
                if(finished)queue?.delete()
                db.close()
            }
            scope.cancel()
        }
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

private fun Tracks.playbackTracks():Sequence<Pair<Tracks.Group,Int>> = groups.asSequence()
    .filter {it.type==C.TRACK_TYPE_AUDIO||it.type==C.TRACK_TYPE_TEXT}
    .flatMap {group->(0 until group.length).asSequence().filter(group::isTrackSupported).map {group to it}}

@androidx.annotation.OptIn(UnstableApi::class)
private data class RememberedAudio(val language:String?,val label:String?,val ordinal:Int) {
    fun matches(format:Format):Boolean =
        languageKey(language)==languageKey(format.language)&&label.orEmpty().trim().equals(format.label.orEmpty().trim(),ignoreCase=true)

    fun encode():String = org.json.JSONObject().put("language",language).put("label",label).put("ordinal",ordinal).toString()

    companion object {
        private fun languageKey(language:String?):String =
            language?.let(androidx.media3.common.util.Util::normalizeLanguageCode).orEmpty().takeUnless {it=="und"}.orEmpty()

        fun decode(raw:String?):RememberedAudio? = raw?.let {
            runCatching {
                val value=org.json.JSONObject(it)
                RememberedAudio(value.optString("language","").ifBlank {null},value.optString("label","").ifBlank {null},
                    value.getInt("ordinal").also {ordinal->require(ordinal>=0)})
            }.getOrNull()
        }
    }
}

internal fun trackName(label:String?,language:String?):String {
    val name=language?.takeIf {it.isNotBlank()&&it!="und"}?.let {java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.forLanguageTag("ru"))}?.takeIf {it.isNotBlank()}
    return listOfNotNull(label?.takeIf {it.isNotBlank()&&it!="und"},name).distinct().joinToString(" · ").ifBlank {"язык не указан"}
}
