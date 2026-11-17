@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.mobile

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
        if(name=="startup") File(dir,"$name.png").outputStream().use {output->
            Assert.assertTrue(compose.onNodeWithTag("startup-banner").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))
        } else {
            Thread.sleep(500);i.waitForIdleSync()
            Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
        }
    }
    @Test fun startupBannerPrecedesReadyHomeAndDoesNotReturnOnNavigation() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {!model.state.value.initializing}
        compose.mainClock.autoAdvance=false
        compose.runOnUiThread {compose.activity.setContent {WatchApp(model,false)}}
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("startup-banner").assertIsDisplayed()
        capture("startup")
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("startup-banner").assertDoesNotExist()
        compose.mainClock.autoAdvance=true
        compose.runOnIdle {model.page("settings");model.page("home")}
        compose.onNodeWithTag("startup-banner").assertDoesNotExist()
    }
    @Test fun downloadProgressUpdatesAndCancelStaysVisible() {
        val update=AppUpdate(10000,"0.8.11","dev.mjwatch.mobile","","",20L*1024*1024,"","")
        var state by mutableStateOf(UpdateState(available=update,downloading=true))
        var shown by mutableStateOf(true)
        var theme by mutableStateOf("dark")
        compose.runOnUiThread {compose.activity.setContent {WatchTheme(theme) {if(shown) UpdateDownloadDialog(state,false) {shown=false}}}}
        compose.onNodeWithText("0%").assertIsDisplayed()
        compose.runOnIdle {state=state.copy(downloaded=update.size/2)}
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithTag("update-download-progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(.5f,0f..1f)))
        val cancel=compose.onNodeWithText("Отменить загрузку")
        cancel.assertIsDisplayed()
        if(false) cancel.assertIsFocused()
        capture("download-dark")
        compose.runOnIdle {theme="light";state=state.copy(downloaded=update.size)}
        compose.onNodeWithText("Проверяем APK и подпись…").assertIsDisplayed()
        cancel.assertIsDisplayed()
        capture("download-light")
        if(false) cancel.performKeyInput {pressKey(Key.DirectionCenter)} else cancel.performClick()
        compose.onNodeWithTag("update-download-progress").assertDoesNotExist()
        compose.runOnIdle {Assert.assertFalse(shown)}
    }
    @Test fun explicitExitRemovesActivityAndKeepsConfiguration() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.state.value.config.demo}
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {model.page("settings")}
        val exit=compose.onNodeWithText("Выйти из приложения")
        if(!false) compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Выйти из приложения"))
        exit.performClick()
        compose.onNodeWithText("Выйти из MJWatch?").assertIsDisplayed()
        compose.onNodeWithText("Остаться").performClick()
        compose.onNodeWithText("Выйти из MJWatch?").assertDoesNotExist()
        exit.performClick()
        capture("exit")
        val activity=compose.activity
        compose.onNodeWithText("Выйти",substring=false).performClick()
        compose.waitUntil(5000) {activity.isFinishing||activity.isDestroyed}
        Assert.assertTrue(runBlocking {Settings(activity.applicationContext).config.first().demo})
    }
}
