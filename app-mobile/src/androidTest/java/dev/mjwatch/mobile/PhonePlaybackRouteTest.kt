package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*

/** Simulated receiver availability; real catalogue, file selection and local Media3 player. */
class PhonePlaybackRouteTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            c.getSharedPreferences("playback-preferences",0).edit().clear().commit()
            Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true));Settings(c).destination("home")
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @Suppress("UNCHECKED_CAST")
    private val remote get()=RemotePlaybackService::class.java.getDeclaredField("mutable").apply {isAccessible=true}.get(null) as MutableStateFlow<RemotePlaybackState>
    @After fun clearRemote(){remote.value=RemotePlaybackState()}
    private fun selectRelease(connected:Boolean) {
        lateinit var model:WatchModel
        ui.runOnIdle {model=ViewModelProvider(ui.activity)[WatchModel::class.java]}
        ui.waitUntil(10000) {model.state.value.config.demo}
        ui.runOnIdle {
            remote.value=RemotePlaybackState(target=RemoteTarget("test-tv","Тестовый ТВ","instance",RemoteVideo()),connected=connected,profileKey=model.state.value.config.profileKey,observedAt=System.nanoTime())
            model.open(DemoReleases.items.first());model.page("releases")
        }
    }
    @Test fun unavailableTelevisionDoesNotBlockPhonePlayback() {
        selectRelease(false)
        ui.onNodeWithTag("source-continue-tv").assertDoesNotExist()
        ui.onNodeWithTag("source-continue").assertIsDisplayed().performClick()
        ui.waitUntil(10000) {ui.onAllNodesWithText("Сезон 1 · Серия 1").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("Сезон 1 · Серия 1").performClick()
        ui.onNodeWithTag("file-continue").performClick()
        val i=InstrumentationRegistry.getInstrumentation()
        var player:PlaybackActivity?=null
        ui.waitUntil(15000) {
            i.runOnMainSync {player=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull {it.firstFrameSeen}}
            player!=null
        }
        i.runOnMainSync {Assert.assertTrue(player!!.intent.getBooleanExtra("mobile",false));player!!.finish()}
    }
    @Test fun connectedTelevisionHasSeparateLaunchAction() {
        selectRelease(true)
        ui.onNodeWithTag("source-continue").assertIsDisplayed()
        ui.onNodeWithTag("source-continue-tv").assertIsDisplayed().performClick()
        lateinit var model:WatchModel
        ui.runOnIdle {model=ViewModelProvider(ui.activity)[WatchModel::class.java]}
        ui.waitUntil(10000) {model.state.value.page=="files"}
        Assert.assertTrue(model.state.value.remotePlayback)
        ui.onNodeWithText("Сезон 1 · Серия 1").performClick()
        ui.onNodeWithTag("file-continue").performClick()
        ui.waitUntil(5000) {model.state.value.page=="detail"}
        Assert.assertFalse("TV launch must not fall back to a phone player",PlaybackActivity.hasActivePlayback())
    }
}
