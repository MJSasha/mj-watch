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
    compose.onNodeWithTag("settings-list").performScrollToNode(hasContentDescription("Демонстрация с локальным видео"))
    compose.onNodeWithContentDescription("Демонстрация с локальным видео").performClick()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить и продолжить"))
    compose.onNodeWithText("Сохранить и продолжить").performClick()
    compose.waitUntil(15000) {compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("hero-open"))
    capture("home")
    compose.onNodeWithTag("hero-open").performClick()
    compose.onNodeWithTag("detail-sources").assertExists()
    capture("detail")
    compose.onNodeWithTag("detail-sources").performClick()
    compose.onNodeWithTag("release-list").performScrollToNode(hasText("Тихая орбита • тестовое видео 720p"))
    compose.onNodeWithText("Тихая орбита • тестовое видео 720p").performClick()
    compose.onNodeWithTag("source-continue").assertIsDisplayed()
    compose.onNodeWithTag("source-continue").performClick()

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
    UiDevice.getInstance(instrumentation).wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)got it"))),1500)?.click()
    compose.mainClock.advanceTimeBy(500)
    compose.onNodeWithContentDescription("Пауза").performClick()
    capture("player")
    instrumentation.runOnMainSync {
      val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().single()
      val field=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}
      val player=field.get(activity) as androidx.media3.common.Player
      Assert.assertTrue(player.duration>0)
      Assert.assertFalse("Compose pause control",player.playWhenReady)
      player.seekTo(2000);Assert.assertEquals(2000L,player.currentPosition)
      player.play()
    }
    Thread.sleep(1000)
    UiDevice.getInstance(instrumentation).pressBack()
    compose.waitForIdle()
    try {compose.waitUntil(10000) {compose.onAllNodesWithText("Сохранено",substring=true).fetchSemanticsNodes().isNotEmpty()}} catch(e:Throwable) {
      capture("back-failure")
      compose.runOnIdle {val model=androidx.lifecycle.ViewModelProvider(compose.activity)[dev.mjwatch.designsystem.WatchModel::class.java];println("Back failure: page=${model.state.value.page}, selected=${model.state.value.selected?.id}, progress=${model.progress.value}, saved=${model.library.value.size}")}
      throw e
    }
    compose.onNodeWithText("Сохранено",substring=true).assertExists()
    capture("saved-progress")
    compose.onNodeWithTag("detail-resume").performClick()
    seen=false
    compose.waitUntil(30000) {
      instrumentation.runOnMainSync {seen=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().any {it.firstFrameSeen}}
      seen
    }
    if(!seen) compose.runOnIdle {
      val model=androidx.lifecycle.ViewModelProvider(compose.activity)[dev.mjwatch.designsystem.WatchModel::class.java]
      println("Resume failure: page=${model.state.value.page}, busy=${model.state.value.busy}, message=${model.state.value.message}, resumePath=${model.state.value.resumePath}")
    }
    Assert.assertTrue("Resume first frame",seen)
    instrumentation.runOnMainSync {
      val a=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().single()
      val f=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}
      Assert.assertTrue("Saved seek position",(f.get(a) as androidx.media3.common.Player).currentPosition>=2000)
    }
    UiDevice.getInstance(instrumentation).pressBack()

 }
}
