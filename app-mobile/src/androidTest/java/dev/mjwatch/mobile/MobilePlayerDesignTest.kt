package dev.mjwatch.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import dev.mjwatch.playback.PlaybackEpisode
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.io.File

class MobilePlayerDesignTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            Settings(context).handleLegacyHistory();Settings(context).save(Config(demo=true));Settings(context).destination("home")
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private var activity:PlaybackActivity?=null
    @After fun closePlayer() {instrumentation.runOnMainSync {activity?.finish()}}

    @Test fun nativePhoneChromeKeepsTouchActionsAndPlaybackWithoutActionLabels() {
        val queue=(1..2).map {PlaybackEpisode("asset:///demo.mp4","Серия $it.mp4",it)}
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,PlaybackActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mobile",true).putExtra("uri",queue[0].uri)
            .putExtra("title","Тихая орбита · Проверка мобильного плеера").putExtra("path",queue[0].path)
            .putExtra("fileId",1).putExtra("episodes",PlaybackEpisode.encode(queue)))
        ui.waitUntil(20000) {instrumentation.runOnMainSync {
            activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlaybackActivity>().firstOrNull {it.firstFrameSeen}
        };activity!=null}
        lateinit var player:Player
        instrumentation.runOnMainSync {
            player=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}.get(activity) as Player
            player.pause();player.seekTo(2000)
        }
        ui.waitUntil(5000) {ui.onAllNodesWithContentDescription("Воспроизвести").fetchSemanticsNodes().isNotEmpty()}
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"mobile-player-design/"+
            InstrumentationRegistry.getArguments().getString("evidenceDir","phone")).apply {mkdirs()}
        fun capture(name:String) {ui.waitForIdle();Assert.assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder,"$name.png")))}
        capture("initial")
        for(label in listOf("Назад","Дорожки","Предыдущая серия","Назад на 10 секунд","Воспроизвести","Вперёд на 10 секунд","Следующая серия","Аудио и субтитры","Масштаб","PiP")) {
            try {ui.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)}
            catch(error:AssertionError) {throw AssertionError("Target: $label",error)}
            ui.onAllNodesWithText(label,useUnmergedTree=true).assertCountEquals(0)
        }
        // Start visual evidence in touch mode even if the previous test used a TV remote.
        ui.onNodeWithTag("player-gestures").performTouchInput {click(center)}
        ui.waitUntil(5000) {ui.onAllNodesWithTag("player-play").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("player-gestures").performTouchInput {click(center)}
        ui.waitUntil(5000) {runCatching {ui.onNodeWithContentDescription("Воспроизвести").assertIsDisplayed();true}.getOrDefault(false)}
        capture("paused")
        ui.onNodeWithContentDescription("Воспроизвести").performClick()
        ui.waitUntil(5000) {ui.onAllNodesWithContentDescription("Пауза").fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodesWithText("Пауза",useUnmergedTree=true).assertCountEquals(0)
        capture("playing")
        ui.onNodeWithContentDescription("Пауза").performClick()
        ui.onNodeWithTag("player-gestures").performTouchInput {click(center)}
        ui.waitUntil(5000) {ui.onAllNodesWithTag("player-play").fetchSemanticsNodes().isEmpty()}
        capture("hidden")
        ui.onNodeWithTag("player-gestures").performTouchInput {click(center)}
        ui.waitUntil(5000) {runCatching {ui.onNodeWithContentDescription("Воспроизвести").assertIsDisplayed();true}.getOrDefault(false)}
    }
}
