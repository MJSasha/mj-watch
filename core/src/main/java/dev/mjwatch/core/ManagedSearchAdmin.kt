package dev.mjwatch.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

class ManagedSearchAdmin(private val api: ManagedApi) {
    suspend fun open(prowlarr: Boolean, onReady: (SearchAdminAddress) -> Unit) {
        val service = if (prowlarr) "prowlarr" else "jacred"
        val session = if (prowlarr) {
            check(api.authorized("admin/host/prowlarr/status").optBoolean("ready")) { "Запустите Prowlarr на сервере." }
            null
        } else api.authorized("admin/host/jacred/web", "POST")
        val id = session?.getString("id")
        try {
            withContext(Dispatchers.IO) {
                ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
                    listener.soTimeout = 500
                    val cookie = session?.getString("cookie") ?: "mjwatch_admin_${listener.localPort}"
                    val token = session?.getString("token") ?: UUID.randomUUID().toString()
                    val localHost = "127.0.0.1:${listener.localPort}"
                    onReady(SearchAdminAddress(listener.localPort, cookie, token))
                    val connections = Semaphore(8)
                    coroutineScope {
                        while (isActive) {
                            val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                            if (!connections.tryAcquire()) {
                                socket.close()
                                continue
                            }
                            launch {
                                try {
                                    socket.use { relay(it, service, id, localHost, "$cookie=$token") }
                                } catch (_: IOException) {
                                    // A failed WebView connection does not end the administration session.
                                } finally {
                                    connections.release()
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            if (id != null) withContext(NonCancellable) {
                runCatching { withTimeout(3_000) { api.authorized("admin/host/jacred/web?id=$id", "DELETE") } }
            }
        }
    }

    private suspend fun relay(socket: Socket, service: String, id: String?, host: String, cookie: String) {
        socket.soTimeout = 5_000
        val input = socket.getInputStream().buffered()
        val header = readHeader(input)
        val fields = header.toString(Charsets.ISO_8859_1).split("\r\n").drop(1)
        val matchesHost = fields.count { it.substringBefore(':').equals("Host", true) } == 1 &&
            fields.any { it.substringBefore(':').equals("Host", true) && it.substringAfter(':').trim() == host }
        val matchesCookie = fields.filter { it.substringBefore(':').equals("Cookie", true) }.any {
            cookie in it.substringAfter(':').split(';').map(String::trim)
        }
        if (!matchesHost || !matchesCookie) {
            socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val opened = CompletableDeferred<Unit>()
        val output = socket.getOutputStream()
        val ws = api.searchAdminSocket(service, id, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try { output.write(bytes.toByteArray()) } catch (_: IOException) { webSocket.cancel() }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                socket.close()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.completeExceptionally(IOException("Не удалось открыть HTTPS-сеанс админки.", t))) socket.close()
                response?.close()
            }
        })
        try {
            try {
                withTimeout(10_000) { opened.await() }
            } catch (_: IOException) {
                output.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
            if (!ws.send(header.toByteString())) return
            val buffer = ByteArray(16 * 1024)
            var idle = 0
            while (true) {
                val count = try { runInterruptible { input.read(buffer) } } catch (_: SocketTimeoutException) {
                    if (++idle >= 24) break
                    continue
                }
                if (count < 0 || ws.queueSize() > (2L shl 20) || !ws.send(buffer.toByteString(0, count))) break
                idle = 0
            }
        } finally {
            ws.cancel()
        }
    }

    private fun readHeader(input: BufferedInputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        var ending = 0
        while (buffer.size() < 32 * 1024) {
            val byte = input.read()
            if (byte < 0) break
            buffer.write(byte)
            ending = (ending shl 8) or byte
            if (ending == 0x0d0a0d0a) return buffer.toByteArray()
        }
        throw IOException("Не удалось прочитать запрос админки.")
    }
}
