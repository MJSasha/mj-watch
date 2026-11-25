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
    compose.onNode(hasText("Каталог TMDB") and isToggleable()).assertExists()
    compose.onNodeWithText("Адрес TorrServer").assertDoesNotExist()
    screenshot("catalog")
    compose.onNodeWithContentDescription("Назад").performClick()
    compose.waitUntil(5000) {model.state.value.page=="settings"}
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
 @Test fun advancedSearchValuesSurviveCollapseAndSave(){
    lateinit var model:WatchModel
    compose.runOnIdle{model=ViewModelProvider(compose.activity)[WatchModel::class.java];model.page("settings-search")}
    val list=compose.onNodeWithTag("settings-list")
    list.performScrollToNode(hasText("Параметры поиска"));compose.onNodeWithText("Параметры поиска").performClick()
    list.performScrollToNode(hasText("Индексаторы (необязательно)"))
    compose.onNodeWithText("Индексаторы (необязательно)").performTextReplacement("fixture-source")
    list.performScrollToNode(hasText("JacRed JSON"));compose.onNodeWithText("JacRed JSON").performClick()
    list.performScrollToNode(hasText("Параметры поиска"));compose.onNodeWithText("Параметры поиска").performClick()
    compose.onNodeWithText("Индексаторы (необязательно)").assertDoesNotExist()
    compose.onNodeWithText("Параметры поиска").performClick()
    list.performScrollToNode(hasText("Индексаторы (необязательно)"))
    compose.onNodeWithText("Индексаторы (необязательно)").assertTextContains("fixture-source")
    list.performScrollToNode(hasText("Сохранить"));compose.onNodeWithText("Сохранить").performClick()
    compose.waitUntil(10000){model.state.value.page=="settings"}
    Assert.assertEquals("fixture-source",model.state.value.config.indexers)
    Assert.assertEquals("jacred",model.state.value.config.releaseApi)
    Assert.assertEquals("fixture-password",model.secrets.get("torrPassword:http://127.0.0.1:8090"))
 }
 @Test fun managedSearchTabsSwitchAndSaveTheSource(){
    lateinit var model:WatchModel
    compose.runOnIdle{model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
    val context=InstrumentationRegistry.getInstrumentation().targetContext
    runBlocking {Settings(context).save(Config(connectionMode="managed",managedUrl="https://127.0.0.1:1",
        serverId="source-tabs",userId="owner",theme="dark"))}
    compose.waitUntil(10000){model.state.value.config.internalJacred}
    compose.runOnIdle{model.page("settings-search")}
    val internal=compose.onNodeWithText("Внутренний JacRed")
    val external=compose.onNodeWithText("Внешний источник")
    internal.assertIsSelected()
    compose.onNodeWithText("Адрес Jackett / JacRed").assertDoesNotExist()
    external.performClick().assertIsSelected()
    internal.assertIsNotSelected()
    compose.onNodeWithText("Адрес Jackett / JacRed").assertExists()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить"))
    compose.onNodeWithText("Сохранить").performClick()
    compose.waitUntil(5000){model.state.value.config.externalReleaseSearch}
 }
 @Test fun longRawTitleNeverOverlapsBackOrBottomAction() {
    lateinit var model:WatchModel
    compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
    compose.waitUntil(10000) {model.state.value.config.demo&&model.state.value.configured}
    compose.runOnIdle {model.open(Release("fixture:long-title",("Очень длинное название / Long title [2026, приключения, семейный] ").repeat(12),"Локальный fixture"))}
    val toolbar=compose.onNodeWithTag("detail-toolbar").fetchSemanticsNode().boundsInRoot
    val title=compose.onNodeWithTag("detail-title").fetchSemanticsNode().boundsInRoot
    Assert.assertTrue("Header controls precede title",toolbar.bottom<=title.top)
    compose.onNodeWithContentDescription("Назад").assertIsDisplayed()
    compose.onNodeWithTag("detail-cast").assertIsDisplayed()
    compose.onNodeWithTag("watch-on-tv").assertDoesNotExist()
    screenshot("long-title")
    compose.onNodeWithContentDescription("Назад").performClick()
    compose.waitUntil(5000) {model.state.value.page=="home"}
 }
}
