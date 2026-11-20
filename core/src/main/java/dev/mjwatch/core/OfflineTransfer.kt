package dev.mjwatch.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

internal class OfflineProblem(message: String) : IOException(message)

internal suspend fun transferOfflineFile(
    client: OkHttpClient,
    request: Request,
    partial: File,
    total: Long,
    progress: suspend (Long) -> Unit
) = coroutineScope {
    require(total > 0)
    RandomAccessFile(partial, "rw").use { out ->
        // A completed part with no commit marker could have been interrupted before validation.
        if (out.length() >= total) out.setLength(0)
        val offset = out.length()
        val builder = request.newBuilder().header("Accept-Encoding", "identity")
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        val call = client.newCall(builder.build())
        val stopCall = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (response.code != 200 && response.code != 206) throw HttpFailure(response.code)
                val body = response.body ?: throw OfflineProblem("Сервер вернул пустой файл")
                val mime = body.contentType()?.type
                if (mime == "text" || body.contentType()?.subtype in setOf("json", "xml", "html"))
                    throw OfflineProblem("Сервер вернул документ вместо видео")
                val start = if (response.code == 206) {
                    val range = response.header("Content-Range")
                    if (range != "bytes $offset-${total - 1}/$total")
                        throw OfflineProblem("Сервер вернул другой диапазон файла")
                    offset
                } else 0L
                if (body.contentLength() >= 0 && body.contentLength() != total - start)
                    throw OfflineProblem("Размер ответа отличается от размера выбранного файла")
                // A server that ignored Range returned the complete representation.
                if (start == 0L) out.setLength(0)
                if (partial.parentFile!!.usableSpace < total - start)
                    throw OfflineProblem("Недостаточно места на устройстве")
                out.seek(start)
                var bytes = start
                progress(bytes)
                val input = body.byteStream()
                val buffer = ByteArray(128 * 1024)
                var lastUpdate = System.nanoTime()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > total - bytes) throw OfflineProblem("Ответ превышает размер выбранного файла")
                    out.write(buffer, 0, count)
                    bytes += count
                    val now = System.nanoTime()
                    if (now - lastUpdate >= 750_000_000L) {
                        progress(bytes)
                        lastUpdate = now
                    }
                }
                if (bytes != total) throw EOFException("Загрузка прервана")
                out.fd.sync()
            }
        } finally {
            stopCall.cancel()
        }
    }
}


internal suspend fun retryOfflineTransfer(onRetry: () -> Unit, transfer: suspend () -> Unit) {
    val delays = longArrayOf(1_000, 3_000, 8_000)
    var attempt = 0
    while (true) {
        try {
            transfer()
            return
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            val transient = when (e) {
                is HttpFailure -> e.status in setOf(408, 429, 500, 502, 503, 504)
                is EOFException, is java.net.SocketTimeoutException, is java.net.SocketException,
                is java.net.UnknownHostException, is java.net.ProtocolException -> true
                else -> false
            }
            if (!transient || attempt == delays.size) throw e
            onRetry()
            delay(maxOf(delays[attempt++], (e as? HttpFailure)?.retryAfterSeconds?.coerceIn(0, 60)?.times(1000) ?: 0))
        }
    }
}
