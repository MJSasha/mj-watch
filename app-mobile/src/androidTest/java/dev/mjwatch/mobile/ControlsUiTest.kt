package dev.mjwatch.mobile

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.Config
import dev.mjwatch.core.Settings
import dev.mjwatch.designsystem.WatchApp
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ControlsUiTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            Settings(context).handleLegacyHistory()
            Settings(context).save(Config(demo=true,theme="dark"))
            Settings(context).destination("settings")
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()

    private fun model():WatchModel {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.state.value.config.demo}
        return model
    }
    private fun screenshot(name:String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        // Native ripple and system-bar rendering run outside Compose's test clock.
        Thread.sleep(800)
        instrumentation.waitForIdleSync()
        val dir=File(instrumentation.targetContext.getExternalFilesDir(null),"family-controls").apply {mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(dir,"$name.png")))
    }

    @Test fun switchActivatesOnceFromLabelAndThumb() {
        val model=model()
        compose.runOnIdle {model.page("settings-updates")}
        val row=compose.onNode(hasText("Автопроверка") and isToggleable())
        row.assertIsOff().assertHeightIsAtLeast(48.dp)
        // The label side must work without having to aim at the switch thumb.
        row.performTouchInput {click(Offset(24f,centerY))}
        row.assertIsOn()
        row.performTouchInput {click(Offset(width-24f,centerY))}
        row.assertIsOff()
        screenshot("dark-updates")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {Settings(context).save(model.state.value.config.copy(theme="light"))}
        compose.waitUntil(10000) {model.state.value.config.theme=="light"}
        screenshot("light-updates")
    }

    @Test fun checkboxRowTogglesWithoutChangingSettingsBeforeSave() {
        val model=model()
        compose.runOnIdle {model.page("settings-server")}
        val row=compose.onNode(hasText("Разрешить локальный HTTP") and isToggleable())
        row.assertIsOff().assertHeightIsAtLeast(48.dp)
        row.performTouchInput {click(Offset(width-24f,centerY))}
        row.assertIsOn()
        compose.runOnIdle {Assert.assertFalse(model.state.value.config.localHttp)}
        row.performClick().assertIsOff()
    }

    @Test fun longActionWrapsWithoutClippingAtDoubleFontInBothThemes() {
        val model=model()
        compose.runOnUiThread {
            compose.activity.setContent {
                val density=LocalDensity.current
                val configuration=Configuration(LocalConfiguration.current).apply {fontScale=2f}
                CompositionLocalProvider(LocalDensity provides Density(density.density,2f),LocalConfiguration provides configuration) {
                    WatchApp(model,false)
                }
            }
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for(theme in listOf("dark","light")) {
            runBlocking {Settings(context).save(Config(demo=true,theme=theme))}
            compose.waitUntil(10000) {model.state.value.config.theme==theme}
            compose.runOnIdle {model.page("connection-choice")}
            val label="Демонстрация, импорт и экспорт"
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val layouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
            Assert.assertTrue(layouts.isNotEmpty())
            Assert.assertTrue("Long labels must wrap",layouts.single().lineCount>1)
            Assert.assertFalse("Button text must stay complete",layouts.single().hasVisualOverflow)
            screenshot("$theme-double-font")
        }
    }
}
