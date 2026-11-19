package dev.mjwatch.core

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

@RunWith(AndroidJUnit4::class)
class ManagedOfflineDownloadTest {
    @Test fun expiredHandleAndTokenResumeSelectedEpisodeWithAccountCredentials() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val password = "test-only".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            instrumentation.context.assets.open("offline-loopback-test.p12").use { load(it, password) }
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        MockWebServer().use { server ->
            server.useHttps(tls.socketFactory, false)
            server.start()
            val config = Config(connectionMode = "managed", managedUrl = server.url("/root").toString(), serverId = "server", userId = "viewer")
            val secrets = SecretStore(app)
            val origin = endpoint(config.managedUrl).origin()
            val cert = Base64.encodeToString(keys.getCertificate("local-test-only").encoded, Base64.NO_WRAP)
            secrets.put("ca:$origin", "-----BEGIN CERTIFICATE-----\n$cert\n-----END CERTIFICATE-----")
            val auth = "$origin:${config.scope}"
            secrets.put("refresh:$auth", "initial-refresh")
            secrets.put("access:$auth", "expired-access")
            secrets.put("expires:$auth", "1")
            val hash = "a".repeat(40)
            val bytes = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0, 7)
            val file = TorrentFile(7, "Series/S02E04.mkv", bytes.size.toLong())
            val item = OfflineDownload("managed-test", "fixture", "Серия", null, 7, file.path,
                contentKey(hash, file.path, file.size), 2, 4, 2, file.size, OfflineState.QUEUED, null)
            val profile = "${config.profileKey}@${endpoint(config.managedUrl)}"
            val row = OfflineRecord(item, profile, config.managedUrl, "expired-handle", 1)
            server.enqueue(MockResponse().setBody("""{"accessToken":"fresh-access","refreshToken":"rotated-refresh","expiresIn":900}"""))
            server.enqueue(MockResponse().setBody("""{"userId":"viewer","serverId":"server"}"""))
            server.enqueue(MockResponse().setResponseCode(404)) // stream
            server.enqueue(MockResponse().setResponseCode(404)) // status
            server.enqueue(MockResponse().setBody("""{"id":"fresh-handle","hash":"$hash","torrent":{"file_stats":[{"id":3,"path":"Series/S02E03.mkv","length":6},{"id":41,"path":"${file.path}","length":6}]}}"""))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-5/6")
                .setBody(Buffer().write(bytes, 2, 4)))
            val part = File.createTempFile("offline-managed", ".part", app.cacheDir)
            try {
                part.writeBytes(bytes.copyOf(2))
                withContext(Dispatchers.IO) { OfflineDownloads.get(app).fetch(config, row, part) {} }
                assertArrayEquals(bytes, part.readBytes())
                val requests = List(server.requestCount) { server.takeRequest() }
                assertEquals(listOf("/root/api/v1/auth/refresh", "/root/api/v1/me",
                    "/root/api/v1/playback/expired-handle/stream?file=7", "/root/api/v1/playback/expired-handle",
                    "/root/api/v1/playback", "/root/api/v1/playback/fresh-handle/stream?file=41"), requests.map { it.path })
                assertEquals("initial-refresh", JSONObject(requests.first().body.readUtf8()).getString("refreshToken"))
                assertTrue(requests.drop(1).all { it.getHeader("Authorization") == "Bearer fresh-access" })
                assertEquals("bytes=2-", requests.last().getHeader("Range"))
                assertTrue(requests.none { it.path!!.contains("access") || it.path!!.contains("refreshToken") })
            } finally {
                part.delete()
                OfflineDownloads.get(app).remove(config, item.id)
            }
        }
    }
}
