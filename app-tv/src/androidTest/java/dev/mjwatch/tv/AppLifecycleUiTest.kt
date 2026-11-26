@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import java.io.File

class AppLifecycleUiTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true,theme="dark"));Settings(c).destination("home")
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String) {
        val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"issues-20-23").apply {mkdirs()}
        if(name.startsWith("startup")) File(dir,"$name.png").outputStream().use {output->
            Assert.assertTrue(compose.onNodeWithTag("startup-banner").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))
        } else {
            Thread.sleep(500);i.waitForIdleSync()
            Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
        }
    }
    @Test fun readyHomeSkipsStartupAnimationAndStartupDoesNotReturnOnNavigation() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {!model.state.value.initializing}
        compose.mainClock.autoAdvance=false
        compose.runOnUiThread {compose.activity.setContent {WatchApp(model,true)}}
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("app-content").assertExists()
        compose.onNodeWithTag("startup-banner").assertDoesNotExist()
        compose.mainClock.autoAdvance=true
        compose.runOnIdle {model.page("settings");model.page("home")}
        compose.onNodeWithTag("startup-banner").assertDoesNotExist()
    }

    @Test fun coldRevealDrawsReadyContentBeforeRemovingArtwork() {
        Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        val model=compose.runOnIdle {ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {!model.state.value.initializing}
        compose.mainClock.autoAdvance=false
        compose.runOnUiThread {compose.activity.setContent {WatchApp(model,true,animateStartup=true)}}
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("app-content").assertExists()
        compose.onNodeWithTag("startup-banner").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag("startup-banner").assertIsDisplayed()
        compose.onNodeWithTag("app-content").assertExists()
        repeat(32) {
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
        }
        compose.onNodeWithTag("startup-banner").assertDoesNotExist()
        compose.mainClock.autoAdvance=true
    }
    @Test fun startupArtworkCanFinishWhileContentIsLoading() {
        compose.mainClock.autoAdvance=false
        compose.runOnUiThread {compose.activity.setContent {StartupBanner()}}
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("startup-banner").assertIsDisplayed()
        capture("startup-brand")
        compose.mainClock.advanceTimeBy(275)
        capture("startup-assembly")
        compose.mainClock.advanceTimeBy(460)
        capture("startup-final")
        compose.mainClock.autoAdvance=true
    }
    @Test fun completedStartupDoesNotReplayAfterActivityRecreation() {
        compose.mainClock.advanceTimeBy(1800)
        compose.onNodeWithTag("startup-banner").assertDoesNotExist()
        compose.mainClock.autoAdvance=false
        compose.activityRule.scenario.recreate()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("startup-banner").assertDoesNotExist()
        compose.mainClock.autoAdvance=true
    }
    @Test fun downloadProgressUpdatesAndCancelStaysVisible() {
        val update=AppUpdate(10000,"0.8.11","dev.mjwatch.tv","","",20L*1024*1024,"","")
        var state by mutableStateOf(UpdateState(available=update,downloading=true))
        var shown by mutableStateOf(true)
        var theme by mutableStateOf("dark")
        compose.runOnUiThread {compose.activity.setContent {WatchTheme(theme) {if(shown) UpdateDownloadDialog(state,true,onCancel={shown=false})}}}
        compose.onNodeWithText("0%").assertIsDisplayed()
        compose.runOnIdle {state=state.copy(downloaded=update.size/2)}
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithTag("update-download-progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(.5f,0f..1f)))
        val cancel=compose.onNodeWithText("Отменить загрузку")
        cancel.assertIsDisplayed()
        if(true) {compose.waitUntil(5000) {cancel.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)==true};cancel.assertIsFocused()}
        capture("download-dark")
        compose.runOnIdle {theme="light";state=state.copy(downloaded=update.size)}
        compose.onNodeWithText("Проверяем APK и подпись…").assertIsDisplayed()
        cancel.assertIsDisplayed()
        capture("download-light")
        if(true) cancel.performKeyInput {pressKey(Key.DirectionCenter)} else cancel.performClick()
        compose.onNodeWithTag("update-download-progress").assertDoesNotExist()
        compose.runOnIdle {Assert.assertFalse(shown)}
    }
    @Test fun verifiedDownloadKeepsDialogAndOffersInstall() {
        val update=AppUpdate(10000,"0.8.13","dev.mjwatch.tv","","",20L*1024*1024,"","")
        var state by mutableStateOf(UpdateState(available=update,downloading=true,downloaded=update.size))
        var shown by mutableStateOf(true)
        var installs=0
        compose.runOnUiThread {compose.activity.setContent {WatchTheme("dark") {if(shown) UpdateDownloadDialog(
            state,true,onCancel={shown=false},onDismiss={shown=false},
            installButton={modifier->dev.mjwatch.designsystem.TvActionButton("Установить обновление",modifier,onClick={installs++})}
        )}}}
        compose.onNodeWithText("Проверяем APK и подпись…").assertIsDisplayed()
        compose.onNodeWithText("Установить обновление").assertDoesNotExist()
        compose.runOnIdle {state=state.copy(downloading=false,ready=File(compose.activity.cacheDir,"verified-test.apk"))}
        compose.onNodeWithText("Обновление готово").assertIsDisplayed()
        compose.onNodeWithTag("update-download-progress").assertDoesNotExist()
        val install=compose.onNodeWithText("Установить обновление")
        if(true) {
            compose.waitUntil(5000) {install.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)==true}
            install.performKeyInput {pressKey(Key.DirectionCenter)}
        } else install.performClick()
        compose.runOnIdle {Assert.assertEquals(1,installs)}
        compose.onNodeWithText("Обновление готово").assertIsDisplayed()
        val later=compose.onNodeWithText("Позже")
        if(true) later.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused().performKeyInput {pressKey(Key.DirectionCenter)} else later.performClick()
        compose.onNodeWithText("Обновление готово").assertDoesNotExist()
    }
    @Test fun explicitExitRemovesActivityAndKeepsConfiguration() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.state.value.config.demo}
        compose.mainClock.advanceTimeBy(1800)
        compose.runOnIdle {model.page("settings")}
        val exit=compose.onNodeWithText("Выйти из приложения")
        exit.performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(5000) {compose.onNodeWithText("Выйти из MJWatch?").isDisplayed()}
        compose.onNodeWithText("Выйти из MJWatch?").assertIsDisplayed()
        compose.waitUntil(5000) {compose.onNodeWithText("Остаться").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)==true}
        compose.onNodeWithText("Остаться").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.onNodeWithText("Выйти из MJWatch?").assertDoesNotExist()
        compose.waitUntil(5000) {compose.activity.window.decorView.hasWindowFocus()}
        exit.performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(5000) {compose.onNodeWithText("Остаться").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)==true}
        val activity=compose.activity
        compose.onNodeWithText("Выйти",substring=false).performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused().performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(5000) {activity.isFinishing||activity.isDestroyed}
        Assert.assertTrue(runBlocking {Settings(activity.applicationContext).config.first().demo})
    }
}
