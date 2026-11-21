@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

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
import dev.mjwatch.playback.PlaybackQueue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
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
        compose.waitUntil(10000){compose.onAllNodesWithTag("file-3").fetchSemanticsNodes().isNotEmpty()}
        val first=compose.onNodeWithText("Сезон 1 · Серия 1")
        first.performSemanticsAction(SemanticsActions.RequestFocus)
        first.performKeyInput {pressKey(Key.DirectionCenter);pressKey(Key.DirectionCenter)}
        var a=awaitEpisode(3)
        UiDevice.getInstance(instrumentation).wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)got it"))),1000)?.click()
        // Android's fullscreen hint enters touch mode; resume actual D-pad navigation.
        UiDevice.getInstance(instrumentation).pressDPadDown()
        compose.waitForIdle()
        val queue=PlaybackQueue.open(a.cacheDir,a.intent.getStringExtra(PlaybackQueue.EXTRA))
        Assert.assertEquals(listOf(3,17),queue?.read()?.map {it.fileId})
        compose.onNodeWithTag("player-previous-episode").assertIsNotEnabled()
        compose.onNodeWithTag("player-next-episode").assertIsEnabled()
        screenAwake(true)
        instrumentation.runOnMainSync {(field(a,"player") as Player).pause()}
        screenAwake(false)
        compose.onNodeWithTag("player-play").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("player-play").assertIsFocused()
        compose.onNodeWithTag("player-play").performKeyInput {pressKey(Key.DirectionRight)}
        compose.onNodeWithContentDescription("Вперёд на 10 секунд").assertIsFocused()
        compose.onNodeWithContentDescription("Вперёд на 10 секунд").performKeyInput {pressKey(Key.DirectionRight)}
        compose.onNodeWithTag("player-next-episode").assertIsFocused()
        compose.onNodeWithTag("player-next-episode").performKeyInput {pressKey(Key.DirectionCenter)}
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

    @Test fun largeReleaseScrollsAndPlaysTheLastEpisode() {
        MockWebServer().use {server->
            val count=177
            val hash="08ada5a7a6183aae1e09d831df6748d566095a10"
            val context=instrumentation.targetContext
            val video=context.assets.open("demo.mp4").use {it.readBytes()}
            // Exercise a queue whose long paths would make the old Activity Intent exceed 1 MiB.
            val directory=("Long release directory ".repeat(9).trimEnd()+"/").repeat(16)
            fun filename(index:Int)="Series.S01E${index.toString().padStart(3,'0')}.mp4"
            val files=JSONArray()
            for(index in count downTo 1) {
                files.put(JSONObject().put("id",index).put("path",directory+filename(index)).put("length",video.size))
            }
            val torrent=JSONObject().put("hash",hash).put("file_stats",files).toString()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse = when {
                    request.path=="/torrents" -> MockResponse().setBody(torrent)
                    request.path.orEmpty().startsWith("/stream/") -> MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(video))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val config=Config(torrServer=server.url("/").toString(),localHttp=true)
            runBlocking {Settings(context).save(config)}
            lateinit var model:WatchModel
            compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
            compose.waitUntil(10000) {model.state.value.config.torrServer==config.torrServer}
            compose.runOnIdle {model.open(Release("fixture:177","Series S01","Fixture",hash=hash));model.prepare()}
            compose.waitUntil(10000) {compose.onAllNodesWithText("Сезон 1 · Серия 1").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(5000) {
                compose.onAllNodes(hasText("Сезон 1 · Серия 1") and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            val last=compose.onNodeWithText("Сезон 1 · Серия 177")
            last.assertDoesNotExist()
            compose.onNodeWithTag("file-list").performScrollToIndex(count-2)
            val previous=compose.onNodeWithText("Сезон 1 · Серия 176")
            previous.performSemanticsAction(SemanticsActions.RequestFocus)
            previous.performKeyInput {pressKey(Key.DirectionDown)}
            last.assertIsFocused().performKeyInput {pressKey(Key.DirectionCenter);pressKey(Key.DirectionCenter)}

            val player=awaitEpisode(count)
            try {
                Assert.assertNull(player.intent.getStringExtra("episodes"))
                val queue=PlaybackQueue.open(player.cacheDir,player.intent.getStringExtra(PlaybackQueue.EXTRA))
                Assert.assertEquals((1..count).toList(),queue?.read()?.map {it.fileId})
                instrumentation.runOnMainSync {
                    (field(player,"player") as Player).pause()
                }
                compose.onNodeWithTag("player-episodes").performClick()
                val list=hasTestTag("player-episode-list")
                val lastInPlayer=hasText(filename(count),substring=true) and hasAnyAncestor(list)
                compose.waitUntil(5000) {compose.onAllNodes(lastInPlayer).fetchSemanticsNodes().isNotEmpty()}
                compose.onNode(lastInPlayer).assertIsDisplayed()
                compose.onNode(hasText(filename(1),substring=true) and hasAnyAncestor(list)).assertDoesNotExist()
                compose.onNode(list).performScrollToIndex(0)
                compose.onNode(hasText(filename(1),substring=true) and hasAnyAncestor(list)).assertIsDisplayed()
            } finally {
                instrumentation.runOnMainSync {player.finish()}
            }
        }
    }
}
