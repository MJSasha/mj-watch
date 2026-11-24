package dev.mjwatch.mobile

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.mjwatch.core.Config
import dev.mjwatch.core.Settings
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class PlaybackSplashArtworkUiTest {
    private val server=MockWebServer()
    @get:Rule val fixture=object:ExternalResource() {
        override fun before() {server.start()}
        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {PlaybackActivity.stopActivePlayback()}
            server.shutdown()
        }
    }

    @Test fun artworkSplashStaysUntilFirstFrame() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val device=UiDevice.getInstance(instrumentation)
        val context=instrumentation.targetContext
        runBlocking {
            Settings(context).handleLegacyHistory()
            Settings(context).save(Config(torrServer=server.url("/").toString(),localHttp=true,metadataProvider="tmdb"))
        }
        val backdrop=context.resources.openRawResource(dev.mjwatch.designsystem.R.drawable.demo_orbit).use {it.readBytes()}
        val video=context.assets.open("demo.mp4").use {it.readBytes()}
        val logo=android.graphics.Bitmap.createBitmap(900,180,android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(logo).drawText("Тихая орбита",450f,125f,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color=android.graphics.Color.WHITE
            textAlign=android.graphics.Paint.Align.CENTER
            textSize=113f
            typeface=android.graphics.Typeface.create("serif",android.graphics.Typeface.ITALIC)
        })
        val logoBytes=java.io.ByteArrayOutputStream().apply {logo.compress(android.graphics.Bitmap.CompressFormat.PNG,100,this)}.toByteArray()
        logo.recycle()
        val hash="0123456789012345678901234567890123456789"
        val cachedBytes=AtomicLong(1_000_000)
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse = when(request.path) {
                "/backdrop.png" -> MockResponse().setHeader("Content-Type","image/png").setBody(okio.Buffer().write(backdrop))
                "/logo.png" -> MockResponse().setHeader("Content-Type","image/png").setBody(okio.Buffer().write(logoBytes))
                "/torrents" -> MockResponse().setHeader("Content-Type","application/json")
                    .setBody("""{"hash":"$hash","download_speed":7125000,"active_peers":12,"total_peers":446,"connected_seeders":12,"preloaded_bytes":${cachedBytes.get()}}""")
                "/movie.mp4" -> MockResponse().setHeadersDelay(7,TimeUnit.SECONDS).setHeader("Content-Type","video/mp4").setBody(okio.Buffer().write(video))
                else -> MockResponse().setResponseCode(404)
            }
        }
        context.startActivity(Intent(context,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("uri",server.url("/movie.mp4").toString()).putExtra("title","Тихая орбита")
            .putExtra("artBackdrop",server.url("/backdrop.png").toString()).putExtra("artLogo",server.url("/logo.png").toString())
            .putExtra("torrentHandle",hash).putExtra("fileId",1))
        assertNotNull("The graphic logo is visible",device.wait(Until.findObject(By.desc("Тихая орбита")),5_000))
        assertNotNull("Torrent telemetry is visible",device.wait(Until.findObject(By.text("57 Мбит/с")),5_000))
        assertNotNull("Connected seeders are visible",device.wait(Until.findObject(By.text("Сиды 12")),5_000))
        fun cacheLabel(bytes:Long)="В кэше раздачи ${android.text.format.Formatter.formatShortFileSize(context,bytes)}"
        assertNotNull("The initial cache size is visible",device.wait(Until.findObject(By.text(cacheLabel(cachedBytes.get()))),3_000))
        cachedBytes.set(2_000_000)
        assertNotNull("The cache size updates before the first frame",device.wait(Until.findObject(By.text(cacheLabel(cachedBytes.get()))),3_000))
        assertTrue("The logo clears after the first frame",device.wait(Until.gone(By.desc("Тихая орбита")),20_000))
        if(InstrumentationRegistry.getArguments().getString("capturePlaybackPreview")=="true") Thread.sleep(4500)
    }

}
