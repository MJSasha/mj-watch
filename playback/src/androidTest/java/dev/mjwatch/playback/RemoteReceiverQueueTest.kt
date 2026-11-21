package dev.mjwatch.playback

import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.contentKey
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteReceiverQueueTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val base = "https://receiver.example/".toHttpUrl()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = listOf(
        82 to "Show.S01E10.mkv", 18 to "Show.S01E02.1080p.mkv", 45 to "Show.S01E01.mp4",
        19 to "Show.S01E02.720p.mkv", 90 to "sample/Show.S01E03.mp4", 30 to "Show.S01E03.srt",
        86 to "Extras/Interview.mp4"
    )
    private fun load(fileId: Int) = remotePlaybackIntent(
        context, base,
        JSONObject().put("hash", hash).put("torrent", JSONObject().put("file_stats", JSONArray().apply {
            files.forEach { (id, path) -> put(JSONObject().put("id", id).put("path", path).put("length", 2500)) }
        })),
        JSONObject().put("id", "command").put("handle", "handle").put("fileId", fileId)
            .put("title", "Show").put("position", 9000).put("releaseId", "release")
            .put("uri", "https://controller.example/forged").put("episodes", "[{}]")
    )

    @Test fun selectedVersionAndNextEpisodeUseReceiverStreamAndProgressIdentities() {
        val intent = load(19)
        val queue = checkNotNull(PlaybackQueue.open(context.cacheDir, intent.getStringExtra(PlaybackQueue.EXTRA)))
        val episodes = try {
            queue.read()
        } finally {
            queue.delete()
        }
        assertNull(intent.getStringExtra("episodes"))
        assertEquals(listOf(45, 19, 82), episodes.map { it.fileId })
        episodes.forEach {
            assertEquals("https://receiver.example/api/v1/playback/handle/stream?file=${it.fileId}", it.uri)
            assertEquals(contentKey(hash, it.path, 2500), it.contentKey)
        }
        assertEquals(episodes[1].uri, intent.getStringExtra("uri"))
        assertEquals(episodes[1].path, intent.getStringExtra("path"))
        assertEquals(episodes[1].contentKey, intent.getStringExtra("contentKey"))
        assertEquals("$hash:19", intent.getStringExtra("remoteContentId"))
        assertEquals(9000L, intent.getLongExtra("remotePosition", -1L))
    }

    @Test fun standaloneVideoAndSampleHaveNoNextEpisode() {
        for (id in listOf(86, 90)) {
            val intent = load(id)
            assertEquals(id, intent.getIntExtra("fileId", -1))
            assertNull(intent.getStringExtra(PlaybackQueue.EXTRA))
            assertNull(intent.getStringExtra("episodes"))
        }
    }

    @Test fun receiverRejectsUnknownAndNonVideoSelections() {
        assertTrue(runCatching { load(10) }.isFailure)
        assertTrue(runCatching { load(30) }.isFailure)
    }
}
