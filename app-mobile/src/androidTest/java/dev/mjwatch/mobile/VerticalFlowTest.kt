package dev.mjwatch.mobile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.playback.PlaybackActivity
import org.junit.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class VerticalFlowTest {
 @get:Rule(order=0) val reset=object: org.junit.rules.ExternalResource() { override fun before() { kotlinx.coroutines.runBlocking { dev.mjwatch.core.Settings(InstrumentationRegistry.getInstrumentation().targetContext).save(dev.mjwatch.core.Config()) } } }
 @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
 @Test fun localVideoFirstFrameAndProgress() {
    fun capture(label:String) {
      compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      val context=InstrumentationRegistry.getInstrumentation().targetContext
      val dir=java.io.File(context.getExternalFilesDir(null),"screenshots").apply {mkdirs()}
      UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(dir,"mobile-$label.png"))
    }
    compose.waitForIdle()
    compose.onAllNodes(isToggleable())[0].performClick()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить и продолжить"))
    compose.onNodeWithText("Сохранить и продолжить").performClick()
    compose.waitUntil(15000) {compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithTag("home-list").performScrollToNode(hasText("О тестовой раздаче"))
    capture("home")
    compose.onNodeWithText("О тестовой раздаче").performClick()
    compose.onNodeWithText("Выбрать раздачу").assertExists()
    capture("detail")
    compose.onNodeWithText("Выбрать раздачу").performScrollTo().performClick()
    compose.onNodeWithTag("release-list").performScrollToNode(hasText("Тихая орбита • тестовое видео 720p"))
    compose.onNodeWithText("Тихая орбита • тестовое видео 720p").performClick()
    compose.onNodeWithTag("release-list").performScrollToNode(hasText("Продолжить с выбранной раздачей"))
    compose.onNodeWithText("Продолжить с выбранной раздачей").performClick()

    compose.waitUntil(10000) {compose.onAllNodesWithText("Выберите файл").fetchSemanticsNodes().isNotEmpty()}
    capture("files")
    compose.onNodeWithText("Сезон 1/Серия 01.mp4").performClick()
    compose.onNodeWithText("Смотреть",substring=true).performClick()
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    var seen=false; val deadline=System.currentTimeMillis()+20000
    while(!seen&&System.currentTimeMillis()<deadline) {
      instrumentation.runOnMainSync {seen=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().any {it.firstFrameSeen}}
      Thread.sleep(200)
    }
    Assert.assertTrue("Media3 first-frame callback",seen)
    capture("player")
    instrumentation.runOnMainSync {
      val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().single()
      val field=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}
      val player=field.get(activity) as androidx.media3.common.Player
      Assert.assertTrue(player.duration>0)
      player.pause();Assert.assertFalse(player.playWhenReady)
      player.seekTo(2000);Assert.assertEquals(2000L,player.currentPosition)
      player.play()
    }
    Thread.sleep(1000)
    UiDevice.getInstance(instrumentation).pressBack()
    compose.waitForIdle()
    compose.waitUntil(10000) {compose.onAllNodesWithText("Сохранено",substring=true).fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("Сохранено",substring=true).assertExists()
    capture("saved-progress")
 }
}
