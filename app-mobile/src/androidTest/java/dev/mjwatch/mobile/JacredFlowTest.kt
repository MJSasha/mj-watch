package dev.mjwatch.mobile

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
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Real HTTP adapters, blank credentials/indexers. Synthetic JSON and local MP4, no external servers. */
@RunWith(AndroidJUnit4::class)
class JacredFlowTest {
 private val server=MockWebServer()
 private val paths=CopyOnWriteArrayList<String>()
 private val hash="08ada5a7a6183aae1e09d831df6748d566095a10"
 @get:Rule(order=0) val setup=object:org.junit.rules.ExternalResource() {
    override fun before()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val video=context.assets.open("demo.mp4").use {it.readBytes()}
        server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {
            paths.add(request.path!!)
            return when {
                request.requestUrl!!.encodedPath=="/parser/api/v2.0/indexers/all/results" -> MockResponse().setBody("""{"Results":[{"Title":"Sintel HTTP fixture 720p","Tracker":"Fixture","Seeders":1,"MagnetUri":"magnet:?xt=urn:btih:$hash"}],"jacred":true}""")
                request.path=="/ts/echo" -> MockResponse().setBody("MatriX.145.2")
                request.path=="/ts/torrents" -> MockResponse().setBody("""{"hash":"$hash","file_stats":[{"id":17,"path":"Sintel.mp4","length":${video.size}}]}""")
                request.requestUrl!!.encodedPath=="/ts/stream/Sintel.mp4" -> MockResponse().setHeader("Content-Type","video/mp4").setBody(okio.Buffer().write(video))
                else -> MockResponse().setResponseCode(404)
            }
        }}
        server.start()
        Settings(context).save(Config(jackett=server.url("/parser").toString(),torrServer=server.url("/ts").toString(),localHttp=true,releaseApi="jacred"))
        Settings(context).destination("settings")
    }
    override fun after() {server.shutdown()}
 }
 @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
 @Test fun checksSearchAndVideoUseHttpAdaptersWithoutKeys() {
    compose.onNodeWithText("Jackett / JacRed").performClick()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Проверить поиск"))
    compose.onNodeWithText("Проверить поиск").performClick()
    compose.waitUntil(10000) {compose.onAllNodesWithText("JacRed JSON: подключение проверено").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить"))
    compose.onNodeWithText("Сохранить").performClick()
    compose.waitUntil(10000) {compose.onAllNodesWithText("TorrServer").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("TorrServer").performClick()
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Проверить TorrServer"))
    compose.onNodeWithText("Проверить TorrServer").performClick()
    compose.waitUntil(10000) {compose.onAllNodesWithText("MatriX.145.2").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить"))
    compose.onNodeWithText("Сохранить").performClick()
    compose.waitUntil(10000) {compose.onAllNodesWithText("Настройки").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("Главная").performClick()
    compose.waitUntil(15000) {compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("Поиск").performClick()
    compose.waitUntil(10000) {compose.onAllNodesWithText("Название фильма или раздачи").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("Название фильма или раздачи").performTextInput("Sintel")
    compose.waitUntil(10000) {compose.onAllNodesWithText("Sintel HTTP fixture 720p").fetchSemanticsNodes().isNotEmpty()}
    compose.onNodeWithText("Sintel HTTP fixture 720p").performClick()
    compose.onNodeWithTag("detail-sources").performClick()
    compose.onNodeWithTag("release-list").performScrollToNode(hasText("Sintel HTTP fixture 720p"))
    compose.onNodeWithText("Sintel HTTP fixture 720p").performClick()
    compose.onNodeWithTag("source-continue").assertIsDisplayed()
    compose.onNodeWithTag("source-continue").performClick()
    val i=InstrumentationRegistry.getInstrumentation();var frame=false
    compose.waitUntil(20000) {i.runOnMainSync {frame=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().any {it.firstFrameSeen}};frame}
    Assert.assertTrue(paths.any {it.startsWith("/parser/api/v2.0/indexers/all/results?")&&it.contains("Query=Sintel")})
    Assert.assertTrue(paths.any {it.startsWith("/ts/stream/Sintel.mp4?")&&it.contains("index=17")})
    Assert.assertFalse(paths.any {it.contains("apikey")||it.contains("caps")})
    UiDevice.getInstance(i).pressBack()
 }
}
