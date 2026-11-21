package dev.mjwatch.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import java.io.File

class StreamLimitUiTest {
 private val server=MockWebServer()
 private val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
 private fun streamRequests()=requests.count{it.substringBefore("?")=="/stream"}
 @get:Rule(order=0)val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking{
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{requests.add(request.path.orEmpty());return MockResponse().setResponseCode(429).addHeader("Content-Type","application/json").addHeader("X-MJWatch-Limit-Scope","account").setBody("""{"code":"stream_limit"}""")}}
  server.start()
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  Settings(context).save(Config(demo=false,connectionMode="direct",torrServer=server.url("/").toString(),localHttp=true));Settings(context).destination("home")
 }}
 @get:Rule(order=1)val ui=createEmptyComposeRule()
 @After fun close(){InstrumentationRegistry.getInstrumentation().runOnMainSync{PlaybackActivity.stopActivePlayback()};server.shutdown()}
 @Test fun capacityErrorMakesOneRequestAndOffersAnExplicitRetry(){
  val i=InstrumentationRegistry.getInstrumentation()
  i.targetContext.startActivity(Intent(i.targetContext,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
   .putExtra("mobile",true).putExtra("uri",server.url("/stream").toString()).putExtra("path","movie.mp4").putExtra("fileId",17))
  ui.waitUntil(15000){ui.onAllNodesWithText("Достигнут лимит просмотров").fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithText("На вашем аккаунте",substring=true).assertIsDisplayed()
  Thread.sleep(1500)
  Assert.assertEquals("Media3 and activity must not retry a capacity refusal: $requests",1,streamRequests())
  val dir=File(i.targetContext.getExternalFilesDir(null),"torr-settings").apply{mkdirs()}
  Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"stream-limit.png")))
  ui.onNodeWithText("Повторить").performClick()
  ui.waitUntil(10000){streamRequests()==2}
  Thread.sleep(1500);Assert.assertEquals("Unexpected requests: $requests",2,streamRequests())
 }
}
