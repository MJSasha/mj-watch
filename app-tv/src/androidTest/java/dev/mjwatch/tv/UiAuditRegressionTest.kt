@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.designsystem.releaseCountText
import kotlinx.coroutines.runBlocking
import org.junit.*

/** Audit regressions use bundled content and remote keys, without live servers. */
class UiAuditRegressionTest {
    private var previousImeSetting:String?=null
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            previousImeSetting=device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            Settings(context).handleLegacyHistory()
            Settings(context).save(Config(demo=true))
            Settings(context).destination("home")
        }
        override fun after() {
            val restore=previousImeSetting?.takeIf {it=="0"||it=="1"}
            val command=if(restore==null) "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $restore"
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(command)
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun model():WatchModel {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(5000) {model.state.value.config.demo&&model.state.value.configured}
        return model
    }
    @Test fun russianReleaseCountsIgnoreSystemLanguage() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val expected=mapOf(0 to "0 раздач",1 to "1 раздача",2 to "2 раздачи",5 to "5 раздач",11 to "11 раздач",21 to "21 раздача",22 to "22 раздачи",25 to "25 раздач")
        for(language in listOf("en","ru")) {
            val localized=context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {setLocale(java.util.Locale(language))})
            expected.forEach {(count,text)->Assert.assertEquals("$language/$count",text,releaseCountText(localized,count))}
        }
    }
    @Test fun keyboardDoesNotCollapseTelevisionNavigation() {
        val model=model()
        compose.runOnIdle {model.page("search")}
        compose.onNodeWithTag("search-field").performClick()
        compose.waitUntil(5000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
        }
        compose.onNodeWithText("Настройки").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }
    @Test fun downLeavesSearchFieldAndCanOpenResult() {
        val model=model()
        compose.runOnIdle {model.page("search");model.search("720")}
        compose.waitUntil(10000) {!model.state.value.busy&&model.state.value.results.isNotEmpty()}
        compose.onNodeWithTag("search-field").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("search-field").performKeyInput {pressKey(Key.DirectionDown)}
        compose.onNodeWithTag("search-field").assertIsNotFocused()
        compose.onNodeWithText(DemoReleases.items.first().title).assertIsFocused().performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(5000) {model.state.value.page=="detail"}
    }
    @Test fun imeSearchTransfersFocusAfterDebouncedResultArrives() {
        val model=model()
        compose.runOnIdle {model.page("search")}
        compose.onNodeWithTag("search-field").performTextReplacement("720")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.waitUntil(10000) {!model.state.value.busy&&model.state.value.results.isNotEmpty()}
        compose.onNodeWithText(DemoReleases.items.first().title).assertIsFocused()
        compose.onNodeWithTag("search-field").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("search-field").performImeAction()
        compose.onNodeWithText(DemoReleases.items.first().title).assertIsFocused()
    }
    @Test fun backRestoresBookmarkedCardFocus() {
        val model=model()
        compose.runOnIdle {model.open(DemoReleases.items.first())}
        compose.waitUntil(5000) {model.scopedLibrary.value.context==model.state.value.config.profileKey}
        compose.runOnIdle {if(model.scopedLibrary.value.entries.none {it.id=="demo:orbit"&&it.bookmarked}) model.bookmark()}
        compose.waitUntil(5000) {model.scopedLibrary.value.entries.any {it.id=="demo:orbit"&&it.bookmarked}}
        compose.runOnIdle {model.page("library")}
        compose.onNodeWithText("Буду смотреть").performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.waitUntil(5000) {compose.onAllNodesWithTag("library-demo:orbit").fetchSemanticsNodes().isNotEmpty()}
        repeat(3) {
            compose.onNodeWithTag("library-demo:orbit").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag("library-demo:orbit").performKeyInput {pressKey(Key.DirectionCenter)}
            compose.waitUntil(5000) {model.state.value.page=="detail"}
            compose.runOnIdle {model.back()}
            compose.onNodeWithTag("library-demo:orbit").assertIsFocused()
        }
    }
    @Test fun backRestoresUpdatesRowFocus() {
        val model=model()
        compose.runOnIdle {model.page("settings")}
        compose.onNodeWithText("О приложении").performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.onNodeWithText("Обновления").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Обновления").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(5000) {model.state.value.page=="settings-updates"}
        compose.onNode(hasText("Автопроверка") and isToggleable()).assertIsFocused()
        compose.runOnIdle {model.back()}
        compose.onNodeWithText("Обновления").assertIsFocused()
    }
    @Test fun missingDocumentProviderShowsRussianFallback() {
        val model=model()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val intent=androidx.activity.result.contract.ActivityResultContracts.OpenDocument().createIntent(context,arrayOf("application/json"))
        val realHandlers=context.packageManager.queryIntentActivities(intent,0).filter {it.activityInfo.packageName!="com.android.tv.frameworkpackagestubs"}
        Assume.assumeTrue("This case needs a TV without a document provider",realHandlers.isEmpty())
        compose.runOnIdle {model.page("connections")}
        compose.onNodeWithText("Адрес Jackett / JacRed").assertIsFocused()
        compose.runOnIdle {
            Assert.assertFalse(compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true)
        }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Импорт конфигурации"))
        compose.onNodeWithText("Импорт конфигурации").performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.onNodeWithText("На устройстве нет приложения для выбора файлов. Подключитесь по QR или введите настройки вручную.").assertIsDisplayed()
        compose.onNodeWithText("Понятно").assertIsFocused()
        val dir=java.io.File(context.getExternalFilesDir(null),"ui-audit-089").apply {mkdirs()}
        compose.waitForIdle();Thread.sleep(350)
        Assert.assertTrue(androidx.test.uiautomator.UiDevice.getInstance(instrumentation).takeScreenshot(java.io.File(dir,"picker-fallback.png")))
    }
    @Test fun initialQrActionIsFullyOnScreen() {
        val model=model()
        compose.runOnIdle {model.page("managed-login")}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("tv-refresh-qr").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("tv-refresh-qr").assertIsDisplayed().assertIsFocused()
        val bounds=compose.onNodeWithTag("tv-refresh-qr").getUnclippedBoundsInRoot()
        Assert.assertTrue("Full focused QR action fits screen",bounds.bottom.value<=compose.activity.resources.configuration.screenHeightDp)
    }
}
