@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import dev.mjwatch.playback.PlaybackEpisode
import dev.mjwatch.playback.PlaybackQueue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.*

/** Installed TV APK + ExoPlayer + bundled real MP4. No user devices or mouse input. */
class TvPlayerBehaviorTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        val c=instrumentation.targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        c.getSharedPreferences("playback-preferences",0).edit().clear().putBoolean("auto-next",false).commit()
        Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true));Settings(c).destination("home")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun activity():PlaybackActivity?=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()
    private fun field(a:PlaybackActivity,name:String):Any?=PlaybackActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(a)
    private fun key(code:Int) {
        // Compose focus returns before Android finishes transferring input from a closing dialog.
        compose.waitUntil(5000) {
            val composeDialog=compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
            var focused=false
            instrumentation.runOnMainSync {activity()?.let {a ->
                val dialog=field(a,"panel") as? android.app.AlertDialog
                focused=when {
                    dialog!=null -> dialog.window?.decorView?.hasWindowFocus()==true
                    composeDialog -> android.os.Build.VERSION.SDK_INT<29||android.view.inspector.WindowInspector.getGlobalWindowViews().any {it!==a.window.decorView&&it.hasWindowFocus()}
                    else -> a.window.decorView.hasWindowFocus()
                }
            }}
            focused
        }
        instrumentation.sendKeyDownUpSync(code);compose.waitForIdle()
    }
    private fun focus(node:SemanticsNodeInteraction) {
        // Android may still own the dismissed dialog's window after Compose becomes idle.
        compose.waitUntil(5000) {
            var focused=false
            instrumentation.runOnMainSync {activity()?.let {focused=field(it,"panel")==null&&it.window.decorView.hasWindowFocus()}}
            focused
        }
        compose.waitForIdle()
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitUntil(5000) {runCatching {node.assertIsFocused();true}.getOrDefault(false)}
        instrumentation.waitForIdleSync()
    }
    private fun hideControlsFromPlay() {
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("player-timeline").assertIsFocused();true}.getOrDefault(false)}
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.waitUntil(5000) {compose.onAllNodesWithTag("player-play").fetchSemanticsNodes().isEmpty()}
    }
    private fun awaitReady(a:PlaybackActivity) {
        compose.waitUntil(10000) {
            var ready=false
            instrumentation.runOnMainSync {ready=(field(a,"player") as Player).playbackState==Player.STATE_READY}
            ready&&compose.onAllNodesWithTag("playback-status").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }
    private fun launch(series:Boolean=false,title:String="TV remote regression",label:String="1.mp4",episodeCount:Int=if(series) 2 else 1):PlaybackActivity {
        val c=instrumentation.targetContext
        val queue=(1..episodeCount).map {PlaybackEpisode("asset:///demo.mp4",if(series&&it>1)"$it.mp4" else label,it)}
        c.startActivity(Intent(c,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("uri",queue.first().uri).putExtra("title",title).putExtra("mobile",false).putExtra("path",queue.first().path).putExtra("fileId",1).putExtra("episodes",PlaybackEpisode.encode(queue)))
        var a:PlaybackActivity?=null
        compose.waitUntil(20000) {instrumentation.runOnMainSync {a=activity()?.takeIf {it.firstFrameSeen}};a!=null}
        instrumentation.runOnMainSync {(field(a!!,"player") as Player).apply {pause();seekTo(2000)}}
        awaitReady(a!!)
        compose.waitUntil(5000) {compose.onAllNodesWithTag("player-play").fetchSemanticsNodes().isNotEmpty()}
        return a!!
    }
    private fun selectDialog(index:Int) {
        lateinit var dialog:android.app.AlertDialog
        instrumentation.runOnMainSync {dialog=field(activity()!!,"panel") as android.app.AlertDialog;dialog.listView.requestFocus()}
        var selected=-1
        instrumentation.runOnMainSync {selected=dialog.listView.selectedItemPosition}
        repeat(20) {
            if(selected==index)return@repeat
            key(if(selected<index)KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
            instrumentation.runOnMainSync {selected=dialog.listView.selectedItemPosition}
        }
        Assert.assertEquals(index,selected)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    @After fun cleanup() {instrumentation.runOnMainSync {activity()?.finish()}}
    @Test fun remoteOnlyWalkthroughKeepsFocusAcrossTracksAndNestedVideoPanels() {
        launch(series=true,title="Тихая орбита",label="Сезон 1 · Серия 4")
        compose.waitUntil(5000) {compose.onAllNodesWithTag("player-prompt").fetchSemanticsNodes().isNotEmpty()}
        val folder=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"remote-audit/"+
            InstrumentationRegistry.getArguments().getString("evidenceDir","before")).apply {mkdirs()}
        fun capture(name:String) {compose.waitForIdle();java.io.File(folder,"$name.png").outputStream().use {
            Assert.assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
        }}
        fun focused(tag:String) {try {compose.waitUntil(5000) {runCatching {compose.onNodeWithTag(tag).assertIsFocused();true}.getOrDefault(false)}}
            catch(error:Throwable) {capture("failure-$tag");java.io.File(folder,"failure.txt").writeText(compose.onRoot().printToString());throw error}}
        capture("00-prompt")
        key(KeyEvent.KEYCODE_BACK)
        focused("player-play");capture("01-play")
        repeat(4) {key(KeyEvent.KEYCODE_DPAD_RIGHT)}
        focused("player-tracks");capture("02-tracks-button")
        repeat(5) {index->
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            if(index==0)capture("03-tracks-open")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            if(index==0)capture("04-audio-row")
            key(if(index%2==0)KeyEvent.KEYCODE_DPAD_CENTER else KeyEvent.KEYCODE_BACK)
            focused("player-tracks")
        }
        capture("05-tracks-return")
        key(KeyEvent.KEYCODE_DPAD_RIGHT);focused("player-video")
        key(KeyEvent.KEYCODE_DPAD_CENTER);capture("06-video-panel")
        key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER)
        capture("07-speed-panel")
        key(KeyEvent.KEYCODE_BACK);focused("player-video");capture("08-video-return")
        key(KeyEvent.KEYCODE_DPAD_UP);focused("player-timeline");capture("09a-timeline-focused")
        key(KeyEvent.KEYCODE_DPAD_RIGHT);capture("09-seek-preview")
        key(KeyEvent.KEYCODE_BACK);focused("player-timeline")
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_UP);focused("player-back")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER);capture("10-top-tracks")
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("Дорожки").assertIsFocused()
        capture("11-top-tracks-return")
        key(KeyEvent.KEYCODE_DPAD_DOWN);focused("player-play")
    }
    @Test fun remotePreviewModalFocusHiddenControlsAndSingleBackExitWithRealVideo() {
        val a=launch();val player=field(a,"player") as Player
        focus(compose.onNodeWithTag("player-play"))
        key(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("player-timeline").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.runOnMainSync {Assert.assertEquals(2000L,player.currentPosition)}
        key(KeyEvent.KEYCODE_BACK)
        instrumentation.runOnMainSync {Assert.assertSame(a,activity());Assert.assertEquals(2000L,player.currentPosition)}
        key(KeyEvent.KEYCODE_DPAD_LEFT);key(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.runOnMainSync {Assert.assertEquals(0L,player.currentPosition)}
        awaitReady(a)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        focus(compose.onNodeWithTag("player-tracks"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.runOnMainSync {Assert.assertNotNull(field(a,"panel"))}
        key(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("player-tracks").assertIsFocused();true}.getOrDefault(false)}
        key(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("player-timeline").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("player-play").assertDoesNotExist()
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("player-play").assertDoesNotExist()
        key(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("player-back").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("player-play").assertIsFocused()
        key(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) {var gone=false;instrumentation.runOnMainSync {gone=activity()==null};gone}
        compose.onNodeWithText("История").assertExists()
    }
    private fun withSystemController(block:(MediaController)->Unit) {
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            val manager=context.getSystemService(MediaSessionManager::class.java)
            var controller:MediaController?=null
            compose.waitUntil(5000) {
                controller=manager.getActiveSessions(null).firstOrNull {it.packageName==context.packageName && it.playbackState!=null}
                controller!=null
            }
            block(controller!!)
        } finally {instrumentation.uiAutomation.dropShellPermissionIdentity()}
    }
    @Test fun systemNextPlaysTheFollowingEpisodeAndDisappearsAtTheEnd() {
        val a=launch(series=true,episodeCount=3)
        withSystemController {controller->
            for(file in 2..3) {
                compose.waitUntil(5000) {checkNotNull(controller.playbackState).actions and PlaybackState.ACTION_SKIP_TO_NEXT!=0L}
                controller.transportControls.skipToNext()
                compose.waitUntil(10000) {
                    var playing=false
                    instrumentation.runOnMainSync {playing=a.intent.getIntExtra("fileId",0)==file && a.firstFrameSeen}
                    playing
                }
            }
            compose.waitUntil(5000) {checkNotNull(controller.playbackState).actions and PlaybackState.ACTION_SKIP_TO_NEXT==0L}
            controller.transportControls.skipToNext()
            instrumentation.waitForIdleSync()
            Assert.assertEquals(3,a.intent.getIntExtra("fileId",0))
        }
    }
    @Test fun systemNextIsUnavailableForStandaloneFilm() {
        val a=launch()
        withSystemController {controller->
            Assert.assertEquals(0L,checkNotNull(controller.playbackState).actions and PlaybackState.ACTION_SKIP_TO_NEXT)
            controller.transportControls.skipToNext()
            instrumentation.waitForIdleSync()
            Assert.assertEquals(1,a.intent.getIntExtra("fileId",0))
        }
    }
    @Test fun hardwareEpisodeChangeIsSingleAndRecreationPreservesPausePositionAndSpeed() {
        var a=launch(true)
        // The ten-second fixture is inside the final section immediately; dismiss its offer.
        compose.waitUntil(5000) {compose.onAllNodesWithTag("player-prompt").fetchSemanticsNodes().isNotEmpty()}
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_MEDIA_NEXT)
        compose.waitUntil(10000) {var loaded=false;instrumentation.runOnMainSync {activity()?.let {a=it;loaded=field(it,"fileId")==2&&it.firstFrameSeen}};loaded}
        instrumentation.runOnMainSync {(field(a,"player") as Player).apply {pause();seekTo(3000);setPlaybackSpeed(1.5f)};a.recreate()}
        compose.waitUntil(10000) {var loaded=false;instrumentation.runOnMainSync {activity()?.let {a=it;loaded=it.firstFrameSeen}};loaded}
        instrumentation.runOnMainSync {val p=field(a,"player") as Player;Assert.assertEquals(2,field(a,"fileId"));Assert.assertFalse(p.playWhenReady);Assert.assertEquals(3000L,p.currentPosition);Assert.assertEquals(1.5f,p.playbackParameters.speed,0f)}
        key(KeyEvent.KEYCODE_MEDIA_NEXT)
        instrumentation.runOnMainSync {Assert.assertEquals(2,field(a,"fileId"))}
    }
    @Test fun changingQualityAudioSpeedAndFitKeepsPausedPosition() {
        val a=launch();val player=field(a,"player") as Player
        fun videoMenu() {
            focus(compose.onNodeWithTag("player-video"))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        videoMenu();selectDialog(0);selectDialog(1)
        instrumentation.runOnMainSync {Assert.assertEquals(2000L,player.currentPosition);Assert.assertFalse(player.playWhenReady)}
        videoMenu();selectDialog(1);selectDialog(4)
        videoMenu();selectDialog(2);selectDialog(1)
        focus(compose.onNodeWithTag("player-tracks"))
        key(KeyEvent.KEYCODE_DPAD_CENTER);selectDialog(0)
        instrumentation.runOnMainSync {
            Assert.assertEquals(2000L,player.currentPosition);Assert.assertFalse(player.playWhenReady)
            Assert.assertEquals(1.5f,player.playbackParameters.speed,0f)
        }
        focus(compose.onNodeWithTag("player-play"))
        hideControlsFromPlay();key(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) {var gone=false;instrumentation.runOnMainSync {gone=activity()==null};gone}
    }

    @Test fun approvedDesignSemanticsTargetsAndFocusEvidenceWithRealVideo() {
        val largeFont=InstrumentationRegistry.getArguments().getString("evidenceDir")=="font200"
        launch(series=true,title=if(largeFont)"Тихая орбита · Очень длинное русское название фильма для проверки крупного шрифта" else "Тихая орбита",
            label=if(largeFont)"Сезон 1 · Серия 4 · Очень длинное название файла с дополнительной информацией о серии.mp4" else "Сезон 1 · Серия 4")
        compose.waitUntil(5000) {compose.onAllNodesWithTag("player-prompt").fetchSemanticsNodes().isNotEmpty()}
        key(KeyEvent.KEYCODE_BACK)
        val folder=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"issue-32-player/"+InstrumentationRegistry.getArguments().getString("evidenceDir","720p")).apply {mkdirs()}
        fun capture(name:String) {
            compose.waitForIdle()
            java.io.File(folder,"$name.png").outputStream().use {
                Assert.assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
            }
        }
        val labels=listOf("Предыдущая серия","Следующая серия","Назад","Дорожки","Назад на 10 секунд","Воспроизвести","Вперёд на 10 секунд","Выбор серии","Аудио и субтитры","Параметры видео")
        for(label in labels) {
            val node=compose.onNodeWithContentDescription(label)
            try {node.assertExists().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)}
            catch(e:AssertionError) {throw AssertionError("Target: $label",e)}
            compose.onAllNodesWithText(label,useUnmergedTree=true).assertCountEquals(0)
        }
        compose.onNodeWithTag("player-previous-episode").assertIsNotEnabled()
        for(tag in listOf("player-next-episode","player-play","player-back","player-episodes","player-tracks","player-video","player-timeline")) {
            focus(compose.onNodeWithTag(tag))
            compose.onNodeWithTag(tag).assertIsFocused()
            capture(tag)
        }
        focus(compose.onNodeWithContentDescription("Дорожки"))
        capture("top-tracks")
        key(KeyEvent.KEYCODE_DPAD_CENTER);capture("top-tracks-dialog");key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("Дорожки").assertIsFocused()
        focus(compose.onNodeWithTag("player-timeline"))
        key(KeyEvent.KEYCODE_DPAD_RIGHT);capture("timeline-preview")
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        for(tag in listOf("player-episodes","player-tracks","player-video")) {
            focus(compose.onNodeWithTag(tag))
            key(KeyEvent.KEYCODE_DPAD_CENTER);capture("$tag-dialog")
            key(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000) {runCatching {compose.onNodeWithTag(tag).assertIsFocused();true}.getOrDefault(false)}
        }
        focus(compose.onNodeWithTag("player-play"))
        hideControlsFromPlay()
        capture("hidden-video")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.runOnMainSync {Assert.assertTrue((field(activity()!!,"player") as Player).playWhenReady)}
        compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("Пауза").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("Пауза",useUnmergedTree=true).assertCountEquals(0)
        capture("playing-pause-icon")
        key(KeyEvent.KEYCODE_MEDIA_PAUSE)
        instrumentation.runOnMainSync {Assert.assertFalse((field(activity()!!,"player") as Player).playWhenReady)}
        key(KeyEvent.KEYCODE_MEDIA_PLAY)
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        instrumentation.runOnMainSync {Assert.assertFalse((field(activity()!!,"player") as Player).playWhenReady)}
    }

    @Test fun remembersLabeledAudioAcrossReorderedEpisodesAndReopen() {
        val context = instrumentation.targetContext
        val releaseId = "test:audio-tracks:${java.util.UUID.randomUUID()}"
        val files = listOf("audio-tracks-original.mkv", "audio-tracks-reordered.mkv")
        val fixtures = files.associateWith { name ->
            instrumentation.context.assets.open(name).use { it.readBytes() }
        }

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val bytes = fixtures[request.path?.removePrefix("/")]
                        ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setHeader("Content-Type", "video/x-matroska")
                        .setBody(Buffer().write(bytes))
                }
            }
            server.start()
            runBlocking {
                Settings(context).save(Config(torrServer = server.url("/").toString(), localHttp = true))
            }
            val episodes = files.mapIndexed { index, name ->
                PlaybackEpisode(server.url("/$name").toString(), name, index + 1)
            }
            fun openRelease() {
                val first = episodes.first()
                val queue = PlaybackQueue.create(context.cacheDir).apply { write(episodes) }
                context.startActivity(Intent(context, PlaybackActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("uri", first.uri)
                    .putExtra("path", first.path)
                    .putExtra("fileId", first.fileId)
                    .putExtra("releaseId", releaseId)
                    .putExtra(PlaybackQueue.EXTRA, queue.token)
                    .putExtra("mobile", false))
            }
            fun awaitAudio(fileId: Int, label: String): PlaybackActivity {
                var match: PlaybackActivity? = null
                compose.waitUntil(10000) {
                    instrumentation.runOnMainSync {
                        val current = activity()?.takeIf { it.firstFrameSeen && field(it, "fileId") == fileId }
                            ?: return@runOnMainSync
                        val player = field(current, "player") as Player
                        val audio = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        if (audio.any { group ->
                                (0 until group.length).any { index ->
                                    group.isTrackSelected(index) && group.getTrackFormat(index).label == label
                                }
                            }
                        ) match = current
                    }
                    match != null
                }
                return checkNotNull(match)
            }

            openRelease()
            val first = awaitAudio(1, "Studio")
            instrumentation.runOnMainSync { (field(first, "player") as Player).pause() }
            awaitReady(first)
            compose.onNodeWithTag("player-tracks").performClick()
            var commentary = -1
            instrumentation.runOnMainSync {
                val dialog = field(first, "panel") as android.app.AlertDialog
                val rows = dialog.listView.adapter
                commentary = (0 until rows.count).first { rows.getItem(it).toString().contains("Commentary") }
            }
            selectDialog(commentary)
            awaitAudio(1, "Commentary")

            compose.onNodeWithTag("player-next-episode").performClick()
            val second = awaitAudio(2, "Commentary")
            instrumentation.runOnMainSync { second.finish() }
            compose.waitUntil(5000) {
                var closed = false
                instrumentation.runOnMainSync { closed = activity() == null }
                closed
            }

            openRelease()
            val reopened = awaitAudio(1, "Commentary")
            instrumentation.runOnMainSync { reopened.finish() }
        }
    }
}
