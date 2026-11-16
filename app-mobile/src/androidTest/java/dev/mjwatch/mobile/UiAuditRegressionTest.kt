package dev.mjwatch.mobile

import android.content.pm.ActivityInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*

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
    @Test fun landscapeSearchKeepsMinimumFieldHeightAndQuery() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {model.state.value.config.demo&&model.state.value.configured}
        compose.runOnIdle {
            model.page("search");model.search("orbit")
            compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10000) {compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        compose.onNodeWithTag("search-field").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("search-field").performClick()
        compose.waitUntil(5000) {androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}
        compose.onNodeWithTag("search-field").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(64f)).assertTextContains("orbit")
        compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
