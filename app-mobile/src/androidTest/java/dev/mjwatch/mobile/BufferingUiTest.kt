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
    @Test fun temporaryHttpFailureAndManualRetryRetainActivityPositionAndPause() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val data=context.assets.open("demo.mp4").use {it.readBytes()}
        val mode=java.util.concurrent.atomic.AtomicInteger(4)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val lost=java.util.concurrent.atomic.AtomicBoolean(true)
        val adds=java.util.concurrent.atomic.AtomicInteger()
        val hash="0123456789012345678901234567890123456789"
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                if(request.path=="/torrents") {
                    val body=org.json.JSONObject(request.body.readUtf8())
                    if(body.getString("action")=="get"&&lost.get())return MockResponse().setResponseCode(404)
                    if(body.getString("action")=="add"){adds.incrementAndGet();lost.set(false)}
                    return MockResponse().setBody("""{"hash":"$hash","file_stats":[{"id":7,"path":"movie.mp4","length":${data.size}}]}""")
                }
                calls.incrementAndGet()
                if(mode.get()==-1)return MockResponse().setResponseCode(403)
                if(mode.getAndUpdate {if(it>0)it-1 else it}>0)return MockResponse().setResponseCode(503)
                val start=request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                return MockResponse().setResponseCode(if(start>0)206 else 200).setHeader("Content-Type","video/mp4")
                    .setHeader("Accept-Ranges","bytes").apply {if(start>0)setHeader("Content-Range","bytes $start-${data.lastIndex}/${data.size}")}
                    .setBody(okio.Buffer().write(data,start,data.size-start))
            }
        }
        context.startActivity(Intent(context,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("uri",server.url("/movie.mp4").toString()).putExtra("title","Recovery test").putExtra("path","movie.mp4").putExtra("torrentHandle",hash)
            .putExtra("recoveryRelease",org.json.JSONObject().put("id","recovery").put("title","Recovery").put("source","fixture").put("hash",hash).put("magnet","magnet:?xt=urn:btih:$hash").toString()))
        var activity:PlaybackActivity?=null
        compose.waitUntil(45000){i.runOnMainSync {activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull {it.firstFrameSeen}};activity!=null}
        Assert.assertTrue("Temporary HTTP errors must recover automatically",calls.get()>=5)
        Assert.assertEquals("A missing torrent is prepared again inside the player",1,adds.get())
        val same=activity!!
        Assert.assertEquals("Exact file is reselected with its current server ID",7,same.intent.getIntExtra("fileId",-1))
        lateinit var player:androidx.media3.common.Player
        i.runOnMainSync {
            val field=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}
            player=field.get(same) as androidx.media3.common.Player
            player.pause();mode.set(-1)
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(server.url("/movie.mp4").toString()),2000)
            player.prepare()
        }
        compose.waitUntil(15000){compose.onAllNodesWithText("Повторить").fetchSemanticsNodes().isNotEmpty()}
        mode.set(0)
        compose.onNodeWithText("Повторить").performClick()
        compose.waitUntil(20000){var ready=false;i.runOnMainSync {ready=player.playbackState==androidx.media3.common.Player.STATE_READY};ready}
        i.runOnMainSync {
            Assert.assertFalse("Retry preserves pause",player.playWhenReady)
            Assert.assertTrue("Retry preserves position",player.currentPosition>=1900)
            Assert.assertFalse(same.isFinishing);same.finish()
        }
    }

}
