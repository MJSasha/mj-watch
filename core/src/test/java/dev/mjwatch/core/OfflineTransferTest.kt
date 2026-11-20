package dev.mjwatch.core

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ByteArray(257) { it.toByte() }

    @Test fun resumesExactRangeOrReplacesPartialWhenRangeIgnored() = runBlocking {
        MockWebServer().use { server ->
            for (range in listOf(true, false)) {
                val part = temporary.newFile()
                part.writeBytes(payload.copyOf(41))
                val response = if (range) MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 41-256/257")
                    .setBody(Buffer().write(payload, 41, 216)) else MockResponse().setBody(Buffer().write(payload))
                server.enqueue(response)
                var start = -1L
                transferOfflineFile(OkHttpClient(), Request.Builder().url(server.url("/stream")).build(),
                    part, 257) { start = it }
                assertEquals(if (range) 41L else 0L, start)
                assertArrayEquals(payload, part.readBytes())
                assertEquals("bytes=41-", server.takeRequest().getHeader("Range"))
            }
        }
    }

    @Test fun interruptedBodyResumesFromBytesActuallyWritten() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
            val part = temporary.newFile()
            val client = OkHttpClient()
            val request = Request.Builder().url(server.url("/stream")).build()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val offset = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                    return if (offset == null) MockResponse().setBody(Buffer().write(bytes))
                        .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    else MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
                        .setBody(Buffer().write(bytes, offset, bytes.size - offset))
                }
            }
            var interruptedAt = 0L
            retryOfflineTransfer(onRetry = { interruptedAt = part.length() }) {
                transferOfflineFile(client, request, part, bytes.size.toLong()) {}
            }
            assertTrue(interruptedAt in 1 until bytes.size)
            assertNull(server.takeRequest().getHeader("Range"))
            assertEquals("bytes=$interruptedAt-", server.takeRequest().getHeader("Range"))
            assertArrayEquals(bytes, part.readBytes())
        }
    }

    @Test fun invalidResponsesPreservePartialAndNeverReportSuccess() = runBlocking {
        MockWebServer().use { server ->
            for (bad in listOf(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 1-256/257").setBody("wrong range"),
                MockResponse().setResponseCode(200).setBody("wrong length"),
                MockResponse().setHeader("Content-Type", "text/html").setBody("sign in"),
                MockResponse().setResponseCode(403)
            )) {
                val part = temporary.newFile()
                part.writeBytes(payload.copyOf(41))
                server.enqueue(bad)
                val result = runCatching { transferOfflineFile(OkHttpClient(), Request.Builder().url(server.url("/stream")).build(), part, 257) {} }
                assertTrue(result.isFailure)
                assertArrayEquals(payload.copyOf(41), part.readBytes())
            }
        }
    }

    @Test fun recoveryRejectsChangedTorrentFilePathOrLength() = runBlocking {
        val hash = "1".repeat(40)
        val file = TorrentFile(3, "S01E04.mp4", 128)
        val release = Release("show", "Show", "Fixture", hash)
        for (handle in listOf(
            TorrentHandle("new", listOf(file), "2".repeat(40)),
            TorrentHandle("new", listOf(file.copy(path = "S01E03.mp4")), hash),
            TorrentHandle("new", listOf(file.copy(size = 129)), hash)
        )) {
            val gateway = object : StreamGateway {
                override suspend fun status(hash: String): TorrentHandle = throw HttpFailure(404)
                override suspend fun add(release: Release) = handle
                override suspend fun check() = error("Unexpected")
                override fun stream(handle: TorrentHandle, file: TorrentFile) = error("Unexpected")
            }
            assertTrue(runCatching { reopenOfflineFile(gateway, release, "expired", hash, file) }.exceptionOrNull() is OfflineProblem)
        }
    }
}
