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
    compose.waitForIdle()
    compose.onAllNodes(isToggleable())[0].performClick()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить и продолжить"))
    compose.onNodeWithText("Сохранить и продолжить").performClick()
    compose.onNodeWithTag("home-list").performScrollToNode(hasText("О тестовой раздаче"))
    compose.onNodeWithText("О тестовой раздаче").performClick()
    compose.onNodeWithText("Выбрать раздачу").performClick()
    compose.onNodeWithText("Тихая орбита • тестовое видео 720p").performClick()
    compose.onNodeWithText("Продолжить с выбранной раздачей").performClick()

    compose.waitUntil(10000) {compose.onAllNodesWithText("Выберите файл").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("Сезон 1/Серия 01.mp4").performClick()
    compose.onNodeWithText("Смотреть").performClick()
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    var seen=false; val deadline=System.currentTimeMillis()+20000
    while(!seen&&System.currentTimeMillis()<deadline) {
      instrumentation.runOnMainSync {seen=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().any {it.firstFrameSeen}}
      Thread.sleep(200)
    }
    Assert.assertTrue("Media3 first-frame callback",seen)
    Thread.sleep(6000)
    UiDevice.getInstance(instrumentation).pressBack()
    compose.waitForIdle()
    compose.waitUntil(10000) {compose.onAllNodesWithText("Сохранено",substring=true).fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("Сохранено",substring=true).assertExists()
 }
}
