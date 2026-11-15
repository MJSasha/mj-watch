package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
 @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {override fun before()=runBlocking {
    val c=InstrumentationRegistry.getInstrumentation().targetContext
    c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
    Settings(c).save(Config(demo=true,theme="dark",torrServer="http://127.0.0.1:8090",localHttp=true));Settings(c).destination("settings")
    SecretStore(c).put("torrPassword:http://127.0.0.1:8090","fixture-password")
 }}
 @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
 private fun screenshot(name:String) {
    compose.waitForIdle();Thread.sleep(350);compose.mainClock.advanceTimeBy(500);compose.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
    val dir=File(i.targetContext.getExternalFilesDir(null),"settings-fixes").apply {mkdirs()}
    Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
 }
 @Test fun independentConnectionPagesAndConsistentPreferenceRows() {
    lateinit var model:WatchModel
    compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
    screenshot("settings")
    compose.onNodeWithText("Jackett / JacRed").performClick()
    compose.onNodeWithText("Адрес Jackett / JacRed").assertExists()
    compose.onNodeWithText("Адрес TorrServer").assertDoesNotExist()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить"))
    compose.onNodeWithText("Сохранить").performClick()
    compose.waitUntil(10000) {model.state.value.page=="settings"}
    Assert.assertEquals("fixture-password",model.secrets.get("torrPassword:http://127.0.0.1:8090"))
    compose.onNodeWithText("TorrServer").performClick()
    compose.onNodeWithText("Адрес TorrServer").assertExists()
    compose.onNodeWithText("Адрес Jackett / JacRed").assertDoesNotExist()
    screenshot("torrserver")
    compose.onNodeWithText("Адрес TorrServer").performTextReplacement("http://127.0.0.1:8091")
    compose.onNodeWithContentDescription("Назад").performClick()
    compose.onNodeWithText("Сохранить изменения?").assertExists()
    compose.onNodeWithText("Не сохранять").performClick()
    compose.waitUntil(5000) {model.state.value.page=="settings"}
    Assert.assertEquals("http://127.0.0.1:8090",model.state.value.config.torrServer)
    compose.onNodeWithText("Каталог").performClick()
    compose.onNodeWithContentDescription("Каталог TMDB").assertExists()
    compose.onNodeWithText("Адрес TorrServer").assertDoesNotExist()
    screenshot("catalog")
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Субтитры"))
    compose.onNodeWithText("Субтитры").performClick()
    compose.onNode(hasText("Выключены") and isSelectable()).performClick()
    val prefs=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("playback-preferences",0)
    Assert.assertTrue(prefs.getBoolean("subtitles-off",false))
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Обновления"))
    compose.onNodeWithText("Обновления").performClick()
    compose.onNodeWithText("Обновление MJWatch").assertExists()
    compose.onNodeWithText("Проверить сейчас").assertExists()
    screenshot("updates")
 }
 @Test fun longRawTitleNeverOverlapsBackOrBottomAction() {
    lateinit var model:WatchModel
    compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java];model.open(Release("fixture:long-title",("Очень длинное название / Long title [2026, приключения, семейный] ").repeat(12),"Локальный fixture"))}
    val toolbar=compose.onNodeWithTag("detail-toolbar").fetchSemanticsNode().boundsInRoot
    val title=compose.onNodeWithTag("detail-title").fetchSemanticsNode().boundsInRoot
    Assert.assertTrue("Header controls precede title",toolbar.bottom<=title.top)
    compose.onNodeWithContentDescription("Назад").assertIsDisplayed()
    compose.onNodeWithTag("detail-sources").assertIsDisplayed()
    screenshot("long-title")
    compose.onNodeWithContentDescription("Назад").performClick()
    compose.waitUntil(5000) {model.state.value.page=="home"}
 }
}
