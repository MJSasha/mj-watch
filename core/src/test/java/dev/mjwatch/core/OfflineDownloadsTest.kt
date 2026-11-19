package dev.mjwatch.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class OfflineDownloadsTest {
    @Test fun cancelActiveStreamRetryRemoveAndRejectMissingMedia() = runBlocking {
        MockWebServer().use { server ->
            val app = RuntimeEnvironment.getApplication()
            val config = Config(torrServer = server.url("/torr").toString(), localHttp = true)
            Settings(app).save(config)
            val store = OfflineDownloads.get(app)
            val hash = "1".repeat(40)
            val bytes = ByteArray(10000) { (it % 251).toByte() }
            val file = TorrentFile(41, "TV/S01E02.mp4", bytes.size.toLong())
            val release = Release("series", "Сериал", "Тест", hash)
            val handle = TorrentHandle(hash, listOf(file.copy(serverFileId = 4, path = "TV/S01E01.mp4"), file))
            val id = store.enqueue(config, release, handle, file)
            assertEquals(id, store.enqueue(config, release, handle, file))
            val other = config.copy(connectionMode = "managed", managedUrl = "https://example.invalid", serverId = "other", userId = "u")
            assertTrue(store.observe(other).first().isEmpty())
            store.remove(other, id)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val worker = launch(Dispatchers.IO) { store.drain {} }
            val first = withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) }
            assertEquals("41", first!!.requestUrl!!.queryParameter("index"))
            withTimeout(5000) { store.cancel(config, id); worker.join() }
            assertEquals(OfflineState.CANCELLED, store.observe(config).first().single().state)
            assertNull(store.localFile(config, id))
            store.retry(config, id)
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(bytes)))
            store.drain {}
            val download = store.observe(config).first().single()
            assertEquals(download.error, OfflineState.READY, download.state)
            assertEquals(2, download.episode)
            assertEquals(file.size, download.bytesDownloaded)
            assertNull(store.localFile(other, id))
            val saved = store.localFile(config, id)!!
            assertArrayEquals(bytes, saved.readBytes())
            saved.writeBytes(byteArrayOf(0))
            assertNull(store.localFile(config, id))
            assertEquals(OfflineState.FAILED, store.observe(config).first().single().state)
            store.remove(config, id)
            assertFalse(saved.exists())
            assertTrue(store.observe(config).first().isEmpty())
        }
    }
}
