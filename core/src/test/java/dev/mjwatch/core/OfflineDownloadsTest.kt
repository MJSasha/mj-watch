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
    @Test fun seasonQueuesOnlyItsVideosAndKeepsExistingItems() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val config = Config(torrServer = "http://127.0.0.1:8090", localHttp = true)
        Settings(app).save(config)
        val store = OfflineDownloads.get(app)
        val hash = "2".repeat(40)
        val release = Release("season", "Сериал Season 2", "Тест", hash)
        val first = TorrentFile(1, "Сериал/01.mp4", 100)
        val second = TorrentFile(2, "Сериал/S02E02.mkv", 200)
        val handle = TorrentHandle(hash, listOf(first, second, TorrentFile(3, "Сериал/sample/S02E03.mp4", 20),
            TorrentFile(4, "Сериал/S02E03.srt", 20)))
        val existing = store.enqueue(config, release, handle, first, season = 2)
        val ids = store.enqueueSeason(config, release, handle, 2)
        assertEquals(existing, ids.first())
        assertEquals(listOf(1, 2), store.observe(config).first().mapNotNull { it.episode }.sorted())
        assertTrue(store.observe(config, readyOnly = true).first().isEmpty())
        val mixed = handle.files + TorrentFile(5, "Сериал/S03E01.mp4", 100)
        assertEquals(listOf(second), seasonDownloadFiles(mixed, release.title, 2))
        ids.forEach { store.remove(config, it) }
    }

    @Test fun savedSkipTimelineKeepsSegmentsFromPreviousVersions() {
        val segments = listOf(
            SkipSegment(SegmentKind.INTRO, 5_000, 65_000),
            SkipSegment(SegmentKind.OUTRO, 95_000, 110_000),
            SkipSegment(SegmentKind.PREVIEW, 110_000, 130_000)
        )
        assertEquals(segments, decodeSegments(encodeSegments(segments)))
        assertEquals(listOf(segments[1]), decodeSegments(
            """[{"kind":"outro","startMs":95000,"endMs":110000,"auto":false}]"""
        ))
        assertTrue(decodeSegments(null).isEmpty())
    }

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
