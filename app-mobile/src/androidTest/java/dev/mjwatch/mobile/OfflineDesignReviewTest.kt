@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.PlaybackActivity
import dev.mjwatch.playback.RemotePlaybackService
import dev.mjwatch.playback.RemoteVideo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

/** Visual comparison and route check against the isolated managed HTTPS fixture. */
class OfflineDesignReviewTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val before get() = args.getString("reviewPhase") == "before"
    private val viewport get() = args.getString("reviewViewport", "phone")

    private fun bootstrap() = JSONObject(URL("http://127.0.0.1:18444/bootstrap").readText())

    @Test fun compareReleaseActionsAndLocalDownloads() {
        assumeTrue(args.getString("isolatedDesignReview") == "true")
        val (config, api) = runBlocking(Dispatchers.IO) {
            val account = bootstrap()
            val config = Config(connectionMode = "managed", managedUrl = account.getString("url"),
                serverId = account.getString("serverId"), userId = account.getString("userId"),
                metadataProvider = "none", theme = "dark")
            val secrets = SecretStore(context)
            secrets.put("ca:${endpoint(config.managedUrl).origin()}", account.getString("ca"))
            val api = ManagedApi(config, secrets)
            api.saveTokens(account)
            Settings(context).apply { handleLegacyHistory(); save(config); destination("home") }
            context.getSharedPreferences("updates", 0).edit().putBoolean("automatic", false).commit()
            context.getSharedPreferences("playback-preferences", 0).edit().remove("quality").commit()
            val store = OfflineDownloads.get(context)
            store.observe(config).first().forEach { store.remove(config, it.id) }
            config to api
        }

        val received = ConcurrentLinkedQueue<JSONObject>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("app-content")
            selectRelease(scenario, "Тихая орбита 720p")
            if (!before) assertTwoActions()
            capture("01-actions-without-tv")
            if (!before) {
                ui.onNodeWithTag("source-continue").performClick()
                awaitLocalFrame()
                device.pressBack()
                awaitTag("detail-title")
            }

            val receiver = runBlocking(Dispatchers.IO) { bootstrap() }
            val id = receiver.getString("deviceId")
            val headers = mapOf("Authorization" to "Bearer ${receiver.getString("accessToken")}")
            val polling = CoroutineScope(Dispatchers.IO)
            try {
                val instance = runBlocking(Dispatchers.IO) {
                    api.request("devices/$id", "PATCH", JSONObject().put("name","Гостиная"), headers = headers)
                    api.request("devices/self/profile", "PUT", JSONObject().put("type","tv"), headers = headers)
                    api.request("remote/receiver/open", "POST", headers = headers).getString("instance")
                }
                polling.launch {
                    var video = RemoteVideo()
                    while (isActive) {
                        val result = api.request("remote/receiver", "POST", JSONObject()
                            .put("instance",instance).put("ready",true).put("ack",video.session).put("state",video.json()),
                            headers = headers)
                        result.optJSONObject("command")?.takeIf { it.optString("action") == "load" }?.let { command ->
                            received.add(command)
                            video = RemoteVideo(session = command.getString("id"), contentId = command.getString("contentId"),
                                title = command.getString("title"), status = "playing", duration = 10000L,
                                releaseId = command.getString("releaseId"), metadataId = command.optString("metadataId"))
                        }
                        delay(350)
                    }
                }
                if (before) device.pressBack()
                awaitTag("detail-cast")
                ui.onNodeWithTag("detail-cast").performTouchInput { click() }
                awaitTag("target-$id")
                ui.onNodeWithTag("target-$id").performClick()
                ui.waitUntil(12000) { RemotePlaybackService.state.value.canLaunch }

                selectRelease(scenario, "Тихая орбита")
                if (!before) assertTwoActions()
                capture("02-unknown-quality-with-tv")

                ui.onNodeWithTag("source-download").performClick()
                awaitTag("file-download")
                ui.onNodeWithTag("file-download").performClick()
                awaitTag("offline-list")
                val store = OfflineDownloads.get(context)
                val download = runBlocking { withTimeout(20000) {
                    store.observe(config).first { list -> list.any { it.state == OfflineState.READY || it.state == OfflineState.FAILED } }.single()
                }}
                assertEquals(download.error, OfflineState.READY, download.state)
                val local = runBlocking { store.localFile(config, download.id) } ?: error("No stored copy")
                assertArrayEquals(context.assets.open("demo.mp4").use { it.readBytes() }, local.readBytes())
                assertFalse(PlaybackActivity.hasActivePlayback())
                assertTrue("Download must leave the TV connected", RemotePlaybackService.state.value.canLaunch)
                assertTrue("Download must not issue a TV load command", received.isEmpty())
                if (!before) {
                    ui.onNode(hasText("Моё") and !hasClickAction()).assertIsDisplayed()
                    ui.onNodeWithText("Загрузки").assertIsSelected()
                    ui.onNodeWithText("Буду смотреть").assertExists()
                    ui.onNodeWithText("Буду смотреть").performClick()
                    ui.onNodeWithTag("offline-list").assertDoesNotExist()
                    ui.onNodeWithText("Загрузки").performClick()
                    ui.onNodeWithText("Загрузки").assertIsSelected()
                    ui.onNodeWithTag("offline-play-${download.id}").assertIsDisplayed()
                }
                capture("04-ready-downloads")

                if (!before) {
                    selectRelease(scenario, "Тихая орбита")
                    ui.onNodeWithTag("source-continue").performClick()
                    ui.onNodeWithTag("source-continue-local").assertIsDisplayed()
                    ui.onNodeWithTag("source-continue-tv").assertIsDisplayed()
                    capture("03-playback-destination")
                    ui.onNodeWithTag("source-continue-tv").performClick()
                    ui.waitUntil(15000) { received.isNotEmpty() }
                    val command = received.single()
                    assertEquals(17, command.getInt("fileId"))
                    ui.waitUntil(12000) {
                        RemotePlaybackService.state.value.let { !it.busy && it.target?.video?.session == command.getString("id") }
                    }
                    assertFalse("TV selection opened a phone player", PlaybackActivity.hasActivePlayback())

                    selectRelease(scenario, "Тихая орбита")
                    ui.onNodeWithTag("source-continue").performClick()
                    ui.onNodeWithTag("source-continue-local").performClick()
                    awaitLocalFrame()
                    assertEquals("Local selection did not issue another TV load", 1, received.size)
                    device.pressBack()
                }
            } finally {
                instrumentation.runOnMainSync { RemotePlaybackService.disconnect(context) }
                polling.cancel()
            }
        }
    }

    private fun selectRelease(scenario: ActivityScenario<MainActivity>, title: String) {
        scenario.onActivity { activity ->
            ViewModelProvider(activity)[WatchModel::class.java].open(
                Release("fixture:design-review", title, "Тестовый TorrServer",
                    magnet = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789"),
                metadata = Metadata("tmdb:movie:120","Тихая орбита",null,"История о встрече за пределами знакомого мира.")
            )
        }
        ui.onNodeWithTag("detail-sources").performClick()
        awaitTag("source-continue")
        val release = hasText(title) and hasClickAction()
        ui.onNodeWithTag("release-list").performScrollToNode(release)
        val card = ui.onNode(release)
        if (viewport == "phone") {
            val center = card.fetchSemanticsNode().boundsInWindow.center
            assertTrue(device.click(center.x.toInt(), center.y.toInt()))
        } else card.performTouchInput { click() }
    }

    private fun assertTwoActions() {
        val watch = ui.onNodeWithTag("source-continue").assertIsDisplayed().assertTextEquals("Смотреть")
        val download = ui.onNodeWithTag("source-download").assertIsDisplayed().assertTextEquals("Скачать")
        val minimumTouchHeight = 48 * context.resources.displayMetrics.density
        val watchBounds = watch.fetchSemanticsNode().boundsInRoot
        val downloadBounds = download.fetchSemanticsNode().boundsInRoot
        assertTrue(watchBounds.height >= minimumTouchHeight - 1)
        assertTrue(downloadBounds.height >= minimumTouchHeight - 1)
        assertEquals("Actions share the same row at the review viewport", watchBounds.top, downloadBounds.top, 1f)
        ui.onNodeWithTag("source-continue-tv").assertDoesNotExist()
        ui.onNodeWithText("Размер не указан").assertDoesNotExist()
        ui.onNodeWithText("Сиды не указаны").assertDoesNotExist()
    }

    private fun awaitLocalFrame() {
        ui.waitUntil(20000) {
            var playing = false
            instrumentation.runOnMainSync {
                playing = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PlaybackActivity>().any { it.firstFrameSeen }
            }
            playing
        }
    }

    private fun awaitTag(tag: String) = ui.waitUntil(15000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(name: String) {
        ui.mainClock.advanceTimeBy(400L)
        ui.waitForIdle()
        val phase = if (before) "before" else "after"
        val folder = File(context.getExternalFilesDir(null), "offline-design-review").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(folder, "$phase-$viewport-$name.png")))
    }
}
