@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.mobile

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.PlaybackActivity
import dev.mjwatch.playback.PlaybackEpisode
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SeriesPlaybackUiTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true));Settings(c).destination("home")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun activity():PlaybackActivity?=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()
    private fun field(a:PlaybackActivity,name:String):Any?=PlaybackActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(a)
    private fun awaitEpisode(id:Int):PlaybackActivity {
        var result:PlaybackActivity?=null
        compose.waitUntil(20000){instrumentation.runOnMainSync {result=activity()?.takeIf {field(it,"fileId")==id&&it.firstFrameSeen}};result!=null}
        compose.waitUntil(5000){compose.onAllNodesWithTag("player-previous-episode").fetchSemanticsNodes().isNotEmpty()}
        return result!!
    }
    private fun screenAwake(expected:Boolean) {
        compose.waitUntil(5000){var awake=false;instrumentation.runOnMainSync {awake=(field(activity()!!,"view") as android.view.View).keepScreenOn};awake==expected}
    }
    @Test fun episodesSwitchInNumericOrderWithoutSamplesAndScreenStaysAwakeOnlyDuringPlayback() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.config.demo}
        compose.runOnIdle {model.open(DemoReleases.items.first());model.prepare()}
        compose.waitUntil(10000){model.state.value.page=="files"}
        val first=compose.onNodeWithText("Сезон 1 · Серия 1")
        first.performClick()
        compose.onNodeWithText("Смотреть").performClick()
        var a=awaitEpisode(3)
        UiDevice.getInstance(instrumentation).wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)got it"))),1000)?.click()
        var queue=emptyList<PlaybackEpisode>()
        instrumentation.runOnMainSync {queue=PlaybackEpisode.decode(a.intent.getStringExtra("episodes"))}
        Assert.assertEquals(listOf(3,17),queue.map {it.fileId})
        compose.onNodeWithTag("player-previous-episode").assertIsNotEnabled()
        compose.onNodeWithTag("player-next-episode").assertIsEnabled()
        screenAwake(true)
        instrumentation.runOnMainSync {(field(a,"player") as Player).pause()}
        screenAwake(false)
        compose.onNodeWithTag("player-next-episode").performClick()
        a=awaitEpisode(17)
        screenAwake(true)
        compose.onNodeWithTag("player-next-episode").assertIsNotEnabled()
        compose.onNodeWithTag("player-previous-episode").assertIsEnabled()
        // Recreating the Activity must retain both the selected episode and the queue.
        instrumentation.runOnMainSync {a.recreate()}
        a=awaitEpisode(17)
        compose.onNodeWithTag("player-next-episode").assertIsNotEnabled()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        a=awaitEpisode(3)
        compose.onNodeWithTag("player-previous-episode").assertIsNotEnabled()
        val dir=File(instrumentation.targetContext.getExternalFilesDir(null),"issue-13").apply {mkdirs()}
        UiDevice.getInstance(instrumentation).takeScreenshot(File(dir,"series-controls.png"))
        instrumentation.runOnMainSync {a.finish()}
    }
}
