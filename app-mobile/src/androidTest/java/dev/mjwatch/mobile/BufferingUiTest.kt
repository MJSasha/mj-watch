package dev.mjwatch.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BufferingUiTest {
    private val server=MockWebServer()
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        server.start()
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        Settings(c).handleLegacyHistory();Settings(c).save(Config(torrServer=server.url("/").toString(),localHttp=true));Settings(c).destination("home")
    };override fun after(){server.shutdown()}}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun bufferingIsCompactAndRetryRendersARealFirstFrame() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        server.enqueue(MockResponse().setHeadersDelay(8,TimeUnit.SECONDS).setBody("not a video"))
        context.startActivity(Intent(context,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("uri",server.url("/movie.mp4").toString()).putExtra("title","Проверка подгрузки").putExtra("releaseId","buffering-fixture").putExtra("path","movie.mp4"))
        compose.waitUntil(10000){compose.onAllNodesWithTag("playback-status").fetchSemanticsNodes().isNotEmpty()}
        UiDevice.getInstance(i).wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)got it"))),1000)?.click()
        Thread.sleep(500)
        val status=compose.onNodeWithTag("playback-status").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("Loading surface must not span the video",status.width<context.resources.displayMetrics.widthPixels*.7f)
        compose.onNodeWithText("Готовим видео").assertExists()
        compose.onNodeWithText("Буферизация… · Назад — отменить").assertDoesNotExist()
        val dir=File(context.getExternalFilesDir(null),"issues-6-7").apply {mkdirs()}
        UiDevice.getInstance(i).takeScreenshot(File(dir,"compact-buffering.png"))
        compose.waitUntil(20000){compose.onAllNodesWithText("Повторить").fetchSemanticsNodes().isNotEmpty()}
        server.enqueue(MockResponse().setHeader("Content-Type","video/mp4").setBody(okio.Buffer().write(context.assets.open("demo.mp4").use {it.readBytes()})))
        compose.onNodeWithText("Повторить").performClick()
        var player:PlaybackActivity?=null
        compose.waitUntil(20000){i.runOnMainSync {player=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull {it.firstFrameSeen}};player!=null}
        compose.onNodeWithTag("playback-status").assertDoesNotExist()
        i.runOnMainSync {player!!.finish()}
    }
}
