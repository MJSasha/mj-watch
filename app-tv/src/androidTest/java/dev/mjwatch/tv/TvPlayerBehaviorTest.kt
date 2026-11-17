@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import dev.mjwatch.playback.PlaybackEpisode
import kotlinx.coroutines.runBlocking
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
            var focused=false
            instrumentation.runOnMainSync {activity()?.let {a ->
                val dialog=field(a,"panel") as? android.app.AlertDialog
                focused=(dialog?.window ?: a.window).decorView.hasWindowFocus()
            }}
            focused||compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        instrumentation.sendKeyDownUpSync(code);compose.waitForIdle()
    }
    private fun awaitReady(a:PlaybackActivity) {
        compose.waitUntil(10000) {
            var ready=false
            instrumentation.runOnMainSync {ready=(field(a,"player") as Player).playbackState==Player.STATE_READY}
            ready&&compose.onAllNodesWithTag("playback-status").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }
    private fun launch(series:Boolean=false,title:String="TV remote regression",label:String="1.mp4"):PlaybackActivity {
        val c=instrumentation.targetContext
        val queue=(1..if(series)2 else 1).map {PlaybackEpisode("asset:///demo.mp4",if(series&&it>1)"$it.mp4" else label,it)}
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
    @Test fun remotePreviewModalFocusHiddenControlsAndSingleBackExitWithRealVideo() {
        val a=launch();val player=field(a,"player") as Player
        compose.onNodeWithTag("player-play").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("player-timeline").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.runOnMainSync {Assert.assertEquals(2000L,player.currentPosition)}
        key(KeyEvent.KEYCODE_BACK)
        instrumentation.runOnMainSync {Assert.assertSame(a,activity());Assert.assertEquals(2000L,player.currentPosition)}
        key(KeyEvent.KEYCODE_DPAD_LEFT);key(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.runOnMainSync {Assert.assertEquals(0L,player.currentPosition)}
        awaitReady(a)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("player-tracks").performSemanticsAction(SemanticsActions.RequestFocus)
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
            compose.onNodeWithTag("player-video").performSemanticsAction(SemanticsActions.RequestFocus)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        videoMenu();selectDialog(0);selectDialog(1)
        instrumentation.runOnMainSync {Assert.assertEquals(2000L,player.currentPosition);Assert.assertFalse(player.playWhenReady)}
        videoMenu();selectDialog(1);selectDialog(4)
        videoMenu();selectDialog(2);selectDialog(1)
        compose.onNodeWithTag("player-tracks").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_DPAD_CENTER);selectDialog(0)
        instrumentation.runOnMainSync {
            Assert.assertEquals(2000L,player.currentPosition);Assert.assertFalse(player.playWhenReady)
            Assert.assertEquals(1.5f,player.playbackParameters.speed,0f)
        }
        compose.onNodeWithTag("player-play").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_DPAD_UP);key(KeyEvent.KEYCODE_DPAD_UP);key(KeyEvent.KEYCODE_BACK)
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
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag(tag).assertIsFocused()
            capture(tag)
        }
        compose.onNodeWithContentDescription("Дорожки").performSemanticsAction(SemanticsActions.RequestFocus)
        capture("top-tracks")
        key(KeyEvent.KEYCODE_DPAD_CENTER);capture("top-tracks-dialog");key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("Дорожки").assertIsFocused()
        compose.onNodeWithTag("player-timeline").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_DPAD_RIGHT);capture("timeline-preview")
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        for(tag in listOf("player-episodes","player-tracks","player-video")) {
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
            key(KeyEvent.KEYCODE_DPAD_CENTER);capture("$tag-dialog")
            key(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000) {runCatching {compose.onNodeWithTag(tag).assertIsFocused();true}.getOrDefault(false)}
        }
        compose.onNodeWithTag("player-play").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_DPAD_UP);key(KeyEvent.KEYCODE_DPAD_UP)
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

}
