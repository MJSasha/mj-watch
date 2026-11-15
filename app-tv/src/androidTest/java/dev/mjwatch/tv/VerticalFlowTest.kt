@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
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
    fun remoteClick(text:String) {
      val node=compose.onNodeWithText(text,substring=text=="Смотреть")
      node.performSemanticsAction(SemanticsActions.RequestFocus)
      node.performKeyInput {pressKey(Key.DirectionCenter)}
    }
    fun capture(label:String) {
      compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      val context=InstrumentationRegistry.getInstrumentation().targetContext
      val dir=java.io.File(context.getExternalFilesDir(null),"screenshots").apply {mkdirs()}
      UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(dir,"tv-$label.png"))
    }
    compose.waitForIdle()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasContentDescription("Демонстрация с локальным видео"))
    compose.onNodeWithContentDescription("Демонстрация с локальным видео").performClick()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить и продолжить"))
    remoteClick("Сохранить и продолжить")
    compose.waitUntil(15000) {compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("hero-open"))
    capture("home")
    compose.onNodeWithTag("hero-open").performSemanticsAction(SemanticsActions.RequestFocus)
    compose.onNodeWithTag("hero-open").performKeyInput {pressKey(Key.DirectionCenter)}
    compose.onNodeWithTag("detail-sources").assertExists()
    capture("detail")
    compose.onNodeWithTag("detail-sources").performSemanticsAction(SemanticsActions.RequestFocus)
    compose.onNodeWithTag("detail-sources").performKeyInput {pressKey(Key.DirectionCenter)}
    compose.onNodeWithTag("release-list").performScrollToNode(hasText("Тихая орбита • тестовое видео 720p"))
    remoteClick("Тихая орбита • тестовое видео 720p")

    compose.waitUntil(10000) {compose.onAllNodesWithText("Выберите файл").fetchSemanticsNodes().isNotEmpty()}
    capture("files")
    remoteClick("Сезон 1/Серия 01.mp4")
    remoteClick("Смотреть")
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    var seen=false; val deadline=System.currentTimeMillis()+20000
    while(!seen&&System.currentTimeMillis()<deadline) {
      instrumentation.runOnMainSync {seen=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().any {it.firstFrameSeen}}
      Thread.sleep(200)
    }
    Assert.assertTrue("Media3 first-frame callback",seen)
    UiDevice.getInstance(instrumentation).wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)got it"))),1500)?.click()
    capture("player")
    Thread.sleep(6000)
    UiDevice.getInstance(instrumentation).pressBack()
    compose.waitForIdle()
    compose.waitUntil(10000) {compose.onAllNodesWithText("Сохранено",substring=true).fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("Сохранено",substring=true).assertExists()
    capture("saved-progress")
    UiDevice.getInstance(instrumentation).pressBack()
    compose.waitUntil(5000) {compose.onAllNodesWithTag("hero-open").fetchSemanticsNodes().isNotEmpty()}
    compose.waitUntil(5000) {compose.onAllNodesWithTag("hero-open").fetchSemanticsNodes().any {it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) {false}}}
    compose.onNodeWithTag("hero-open").assertIsFocused()
 }
}
