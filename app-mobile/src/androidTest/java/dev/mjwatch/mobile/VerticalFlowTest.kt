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
 @Test fun catalogAndBookmarksRemainAvailableWithoutTelevision() {
    fun capture(label:String) {
      compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync()
      val context=InstrumentationRegistry.getInstrumentation().targetContext
      val dir=java.io.File(context.getExternalFilesDir(null),"screenshots").apply {mkdirs()}
      UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(dir,"mobile-$label.png"))
    }
    compose.waitForIdle()
    compose.waitUntil(10000){compose.onAllNodesWithText("Демонстрация, импорт и экспорт").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("Демонстрация, импорт и экспорт").performClick()
    compose.waitUntil(10000){compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty()}
    val demoSwitch=hasText("Демонстрация с локальным видео") and isToggleable()
    compose.onNodeWithTag("settings-list").performScrollToNode(demoSwitch)
    compose.onNode(demoSwitch).performClick()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить и продолжить"))
    compose.onNodeWithText("Сохранить и продолжить").performClick()
    compose.waitUntil(15000) {compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("hero-open"))
    capture("home")
    compose.onNodeWithTag("hero-open").performClick()
    compose.onNodeWithTag("detail-cast").assertExists()
    compose.onNodeWithTag("watch-on-tv").assertDoesNotExist()
    compose.onNodeWithContentDescription("В «Буду смотреть»").performClick()
    capture("detail-no-tv")
    compose.onNodeWithTag("detail-cast").performTouchInput {click()}
    compose.waitUntil(5000){compose.onAllNodesWithText("Где смотреть?").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("Где смотреть?").assertExists()
    compose.onNodeWithText("Для подключения ТВ войдите в аккаунт MJWatch. В direct удалённое управление пока недоступно.").assertExists()
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    compose.onNodeWithText("Раздачи").performClick()
    compose.onNodeWithTag("release-list").performScrollToNode(hasText("Тихая орбита • тестовое видео 720p"))
    compose.onNodeWithText("Тихая орбита • тестовое видео 720p").performClick()
    compose.onNodeWithTag("source-continue").assertDoesNotExist()
    Assert.assertFalse(PlaybackActivity.hasActivePlayback())
    compose.runOnIdle {val model=androidx.lifecycle.ViewModelProvider(compose.activity)[dev.mjwatch.designsystem.WatchModel::class.java];Assert.assertTrue(model.progress.value.isEmpty());Assert.assertTrue(model.library.value.all {it.openedAt==0L})}
 }
}
