@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.mobile

import android.app.Notification
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.PlaybackActivity
import dev.mjwatch.playback.PlaybackEpisode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Run the two phases in separate instrumentation invocations, with an ADB force-stop and no network between them. */
@RunWith(AndroidJUnit4::class)
class OfflinePlaybackUiTest {
    @get:Rule val ui=createEmptyComposeRule()

    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private val film=Movie("Тихая орбита","1".repeat(40),listOf(17 to "Тихая орбита.mp4"))
    private val series=Movie("Северный сигнал","2".repeat(40),listOf(24 to "Северный сигнал S01E01.mp4",31 to "Северный сигнал S01E02.mp4"))
    private val video by lazy {context.assets.open("demo.mp4").use {it.readBytes()}}
    private val fileIds get()=(film.files+series.files).map {it.first}
    private val viewport get()=InstrumentationRegistry.getArguments().getString("offlineViewport","phone")
    private val playerField=PlaybackActivity::class.java.getDeclaredField("player").apply {isAccessible=true}

    private data class Movie(val title:String,val hash:String,val files:List<Pair<Int,String>>)

    @Before fun isolatedEmulatorOnly() {
        assumeTrue("Requires a disposable emulator and the two-phase ADB procedure",
            InstrumentationRegistry.getArguments().getString("isolatedOffline")=="true")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Test fun downloadMovieAndSeason() {
        val selectSubset=viewport=="phone"
        val unselectedEpisode=42 to "Северный сигнал S01E03.mp4"
        val bytes=(fileIds+unselectedEpisode.first).associateWith {id ->
            // A valid trailing MP4 free box distinguishes the server responses.
            video+Buffer().writeInt(12).writeUtf8("free").writeInt(id).readByteArray()
        }
        MockWebServer().use {server ->
            val streamed=java.util.concurrent.ConcurrentLinkedQueue<Int>()
            server.dispatcher=object : Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val url=request.requestUrl ?: return MockResponse().setResponseCode(400)
                    return when {
                        url.encodedPath=="/ts/echo" -> MockResponse().setBody("MatriX.145.2")
                        url.encodedPath=="/ts/torrents" -> {
                            val raw=request.body.readUtf8()
                            val media=listOf(film,series).singleOrNull {it.hash in raw}
                                ?: return MockResponse().setResponseCode(404)
                            val available=if(media===series&&selectSubset)media.files+unselectedEpisode else media.files
                            val files=JSONArray().apply {available.forEach {(id,path) ->
                                put(JSONObject().put("id",id).put("path",path).put("length",bytes.getValue(id).size))
                            }}
                            MockResponse().setBody(JSONObject().put("hash",media.hash).put("file_stats",files).toString())
                        }
                        url.encodedPath.startsWith("/ts/stream/") -> {
                            val id=url.queryParameter("index")?.toIntOrNull() ?: return MockResponse().setResponseCode(400)
                            val payload=bytes.getValue(id)
                            if(request.method=="GET")streamed.add(id)
                            MockResponse().setHeader("Content-Type","video/mp4")
                                .setBody(Buffer().write(payload)).apply {
                                    if(id==17 || id==31) throttleBody(128*1024,if(id==31)1200 else 850,TimeUnit.MILLISECONDS)
                                }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val config=Config(jackett="",torrServer=server.url("/ts").toString(),localHttp=true,theme="dark")
            runBlocking {
                context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
                context.getSharedPreferences("playback-preferences",0).edit().remove("quality").commit()
                val settings=Settings(context)
                settings.handleLegacyHistory()
                settings.save(config)
                settings.destination("home")
                val store=OfflineDownloads.get(context)
                for(item in store.observe(config).first())store.remove(config,item.id)
            }
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                awaitTag("app-content")
                for(media in listOf(film,series)) {
                    val inlineReleaseTab=media===film&&selectSubset
                    scenario.onActivity { activity ->
                        val model=ViewModelProvider(activity)[WatchModel::class.java]
                        model.open(Release("offline-fixture:${media.hash}","${media.title} 720p","Тестовый TorrServer",
                            hash=media.hash,magnet="magnet:?xt=urn:btih:${media.hash}"),
                            metadata=if(inlineReleaseTab) null else Metadata("tmdb:${if(media===film)"movie" else "tv"}:${media.files.first().first}",media.title,null,null,
                                kind=if(media===film)"movie" else "tv"))
                    }
                    ui.onNodeWithTag(if(inlineReleaseTab) "detail-tab-releases" else "detail-sources").performClick()
                    awaitTag("source-download")
                    val release=hasText("${media.title} 720p") and hasClickAction()
                    ui.onNodeWithTag("release-list").performScrollToNode(release)
                    ui.onNode(release).performClick()
                    if(inlineReleaseTab) capture("$viewport-inline-release-actions")
                    ui.onNodeWithTag("source-download").assertIsEnabled().performTouchInput { click() }
                    awaitTag("file-list")
                    if (media === film) {
                        val id = media.files.single().first
                        ui.waitUntil(5000) {
                            runCatching { ui.onNodeWithTag("file-list").performScrollToNode(hasTestTag("file-$id")) }.isSuccess
                        }
                        ui.onNodeWithTag("file-$id").assertIsOn()
                        ui.onNodeWithTag("file-download").performClick()
                    } else if (selectSubset) {
                        val list=ui.onNodeWithTag("file-list")
                        for ((id, _) in media.files) {
                            list.performScrollToNode(hasTestTag("file-$id"))
                            ui.onNodeWithTag("file-$id").assertIsOff().performClick().assertIsOn()
                        }
                        list.performScrollToNode(hasTestTag("file-${unselectedEpisode.first}"))
                        ui.onNodeWithTag("file-${unselectedEpisode.first}").assertIsOff()
                        ui.onNodeWithTag("file-download").assertTextEquals("Скачать (2)").performClick()
                    } else {
                        awaitTag("file-download-season")
                        ui.onNodeWithTag("file-download-season").performClick()
                        ui.onNodeWithTag("confirm-season-download").performClick()
                    }
                    awaitTag("offline-list")
                    val store = OfflineDownloads.get(context)
                    for ((id, _) in media.files) {
                        if(id==17 || id==31) {
                            runBlocking {withTimeout(12000) {
                                store.observe(config).first {list -> list.any {
                                    it.fileId==id&&it.state==OfflineState.DOWNLOADING&&it.bytesDownloaded>it.totalBytes/4
                                }}
                            }}
                            awaitTag("download-estimate")
                            ui.onNodeWithTag("offline-list").performScrollToNode(hasTestTag("download-estimate"))
                            capture(if(id==17) "$viewport-movie-download-estimate" else "$viewport-downloading")
                        }
                        if(id==31) {
                            assertTrue(device.pressHome())
                            ui.waitUntil(5000) {
                                var background = false
                                instrumentation.runOnMainSync {
                                    background = ActivityLifecycleMonitorRegistry.getInstance()
                                        .getActivitiesInStage(Stage.RESUMED).none { it is MainActivity }
                                }
                                background
                            }
                            val manager = context.getSystemService(NotificationManager::class.java)
                            val ongoing = manager.activeNotifications.single {
                                it.notification.channelId == "offline-downloads"
                            }.notification
                            assertTrue(ongoing.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Серия 2"))
                            assertTrue(ongoing.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("%"))
                            if (android.os.Build.VERSION.SDK_INT >= 36) {
                                assertEquals("android.app.Notification\$ProgressStyle", ongoing.extras.getString(Notification.EXTRA_TEMPLATE))
                            }
                            if (device.openNotification()) capture("$viewport-download-notification")
                        }
                        val item=runBlocking {withTimeout(30000) {
                            store.observe(config).first {list -> list.any {it.fileId==id&&it.state in setOf(OfflineState.READY,OfflineState.FAILED)}}
                                .single {it.fileId==id}
                        }}
                        assertEquals(item.error,OfflineState.READY,item.state)
                        assertFalse("Downloading must not start the player",PlaybackActivity.hasActivePlayback())
                        val local=runBlocking {store.localFile(config,item.id)} ?: error("Download is not readable")
                        assertArrayEquals("Downloaded bytes for #$id",bytes.getValue(id),local.readBytes())
                        if(id==24) File(context.noBackupFilesDir,"offline-media/${item.id}.poster").writeText("invalid artwork")
                        if(id==31) {
                            // A valid cover on a later episode is available even if the first image failed.
                            val bitmap=android.graphics.Bitmap.createBitmap(12,18,android.graphics.Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(android.graphics.Color.CYAN)
                                File(context.noBackupFilesDir,"offline-media/${item.id}.poster").outputStream().use {
                                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
                                }
                            } finally {bitmap.recycle()}
                        }
                    }
                }
            }
            assertEquals(fileIds.toSet(),streamed.toSet())
            assertEquals("Only the selected files were requested once",fileIds.size,streamed.size)
        }
    }

    @Test fun playEachDownloadAfterColdStartWithoutNetwork() {
        val connectivity=context.getSystemService(ConnectivityManager::class.java)
        assertNull("Disable Wi-Fi and cellular before the second instrumentation invocation",connectivity.activeNetwork)
        val config=runBlocking {Settings(context).config.first()}
        val store=OfflineDownloads.get(context)
        val downloads=runBlocking {store.observe(config).first()}
        assertEquals(fileIds.toSet(),downloads.map {it.fileId}.toSet())
        assertTrue(downloads.all {it.state==OfflineState.READY})
        assertEquals(listOf(1,2),downloads.filter {it.title==series.title}.mapNotNull {it.episode}.sorted())
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("offline-list")
            ui.onNodeWithTag("offline-home").assertIsDisplayed()
            ui.onNodeWithText("Без сети").assertIsDisplayed()
            ui.onNodeWithTag("bottom-navigation").assertDoesNotExist()
            ui.waitUntil(8000) {ui.onAllNodesWithText(series.title).fetchSemanticsNodes().isNotEmpty()}
            val seriesEpisodes=downloads.filter { it.title==series.title }.sortedBy { it.episode }
            val poster=runBlocking {store.localPoster(config,seriesEpisodes.map {it.id},allowNetwork=false)}
            assertEquals("${seriesEpisodes[1].id}.poster",poster?.name)
            val list=ui.onNodeWithTag("offline-list")
            list.performScrollToNode(hasTestTag("offline-season"))
            ui.onNode(hasTestTag("offline-poster") and hasAnyAncestor(hasTestTag("offline-season")),
                useUnmergedTree=true).assertIsDisplayed()
            capture("$viewport-ready")
            for(id in fileIds) {
                val download=downloads.single {item->item.fileId==id}
                val file=runBlocking {store.localFile(config,download.id)} ?: error("Missing local file #$id after cold start")
                val button="offline-play-${download.id}"
                ui.onNodeWithTag("offline-list").performScrollToNode(hasTestTag(button))
                ui.onNodeWithTag(button).performClick()
                awaitPlaying(id,file)
                assertNull(connectivity.activeNetwork)
                if(id==17)capture("$viewport-player")
                leavePlayer()
                awaitTag("offline-list")
            }
            ui.onNodeWithTag("offline-select").performClick()
            list.performScrollToNode(hasTestTag("offline-select-season"))
            ui.onNodeWithTag("offline-select-season").performClick().assertIsOn()
            ui.onNodeWithTag("offline-delete-selected").assertTextEquals("Удалить (2)").performClick()
            ui.onNodeWithTag("offline-confirm-delete").performClick()
            runBlocking {withTimeout(5000) {store.observe(config).first { rows -> rows.map {it.fileId}==listOf(17) }}}
            ui.onNodeWithTag("offline-season").assertDoesNotExist()
        }

        // The managed player has an expired token and no usable server listener.
        // Loopback still works in airplane mode, so any refresh attempt reaches this socket.
        val download=downloads.single {it.fileId==17}
        val file=runBlocking {store.localFile(config,download.id)} ?: error("Missing local movie")
        verifyNoManagedSession(config,download,file)
    }

    private fun verifyNoManagedSession(previous:Config,download:OfflineDownload,file:File) {
        ServerSocket(0,5,InetAddress.getByName("127.0.0.1")).use {listener ->
            val config=Config(connectionMode="managed",managedUrl="https://127.0.0.1:${listener.localPort}",
                serverId="offline-test",userId="offline-test")
            val secrets=SecretStore(context)
            val expiry="expires:${endpoint(config.managedUrl).origin()}:${config.scope}"
            secrets.put(expiry,"0")
            try {
                runBlocking {Settings(context).save(config)}
                val episode=PlaybackEpisode(Uri.fromFile(file).toString(),download.path,download.fileId)
                ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.offlineIntent(
                    context,episode,download.title,download.releaseId,mobile=true)).use {
                    awaitPlaying(download.fileId,file)
                }
                listener.soTimeout=750
                assertThrows("Local playback must not attempt a managed connection",SocketTimeoutException::class.java) {
                    listener.accept().close()
                }
            } finally {
                secrets.put(expiry,"")
                runBlocking {Settings(context).save(previous)}
            }
        }
    }

    private fun leavePlayer() {
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PlaybackActivity>().single()
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun awaitPlaying(id:Int,file:File) {
        var started=false
        ui.waitUntil(25000) {
            instrumentation.runOnMainSync {
                val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PlaybackActivity>().singleOrNull()
                if(activity?.firstFrameSeen==true) {
                    val player=playerField.get(activity) as Player
                    assertEquals(id,activity.intent.getIntExtra("fileId",-1))
                    assertEquals(file,File(player.currentMediaItem!!.localConfiguration!!.uri.path!!))
                    assertFalse(activity.intent.hasExtra("torrentHandle"))
                    assertFalse(activity.intent.hasExtra("remoteSession"))
                    started=player.duration>0&&player.isPlaying&&player.currentPosition>400
                }
            }
            started
        }
    }

    private fun awaitTag(tag:String) = ui.waitUntil(15000) {
        ui.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }

    private fun capture(name:String) {
        ui.waitForIdle()
        val dir=File(context.getExternalFilesDir(null),"offline-0.10.0").apply {mkdirs()}
        assertTrue("Screenshot $name",device.takeScreenshot(File(dir,"$name.png")))
    }
}
