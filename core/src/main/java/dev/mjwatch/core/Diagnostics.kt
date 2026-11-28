package dev.mjwatch.core

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant

class Diagnostics private constructor(private val app: Context) {
    enum class Event {
        PLAYER_ERROR, DECODER_RECOVERED, PLAYBACK_TIMEOUT, PLAYBACK_PREPARATION,
        RELEASE_SEARCH, CONNECTION_CHECK, SERVER_ACTION, CATALOG, DOWNLOAD, UPDATE_CHECK, UPDATE_DOWNLOAD
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = Settings(app)
    private val preference = Mutex()
    private val collecting = MutableStateFlow(false)
    val enabled = collecting.asStateFlow()
    private val pending = Channel<String>(LIMIT, BufferOverflow.DROP_OLDEST)
    private val disk = Mutex()
    private val file by lazy { AtomicFile(File(app.noBackupFilesDir, "diagnostics.txt")) }

    init {
        scope.launch {
            preference.withLock {
                try {
                    collecting.value = settings.diagnosticsEnabled.first()
                } catch (_: IOException) {
                    // Leave collection off if the saved preference cannot be read.
                }
            }
        }
        scope.launch {
            for (event in pending) {
                disk.withLock {
                    try {
                        val events = ArrayDeque(read())
                        events.addLast(event)
                        for (index in 1 until LIMIT) {
                            events.addLast(pending.tryReceive().getOrNull() ?: break)
                        }
                        while (events.size > LIMIT) events.removeFirst()
                        val output = file.startWrite()
                        try {
                            output.write(events.joinToString("\n", postfix = "\n").toByteArray())
                            file.finishWrite(output)
                        } catch (_: IOException) {
                            file.failWrite(output)
                        }
                    } catch (_: IOException) {
                        // Diagnostics must not interrupt playback or a download.
                    }
                }
            }
        }
    }

    suspend fun setEnabled(value: Boolean) = preference.withLock {
        settings.setDiagnosticsEnabled(value)
        collecting.value = value
    }

    // Pass only fixed labels, numeric measurements and platform codec identifiers.
    // Never pass exception messages, URLs, filenames or search terms.
    fun record(event: Event, fields: () -> String = { "" }) {
        if (!enabled.value) return
        val details = fields().take(400).replace('\n', ' ').replace('\r', ' ')
        pending.trySend("${Instant.now()} $event $details".trimEnd())
    }

    fun failure(event: Event, error: Throwable, durationMs: Long? = null) = record(event) {
        val cause = error.cause
        buildString {
            append("error=").append(error.javaClass.simpleName)
            if (cause != null) append(" cause=").append(cause.javaClass.simpleName)
            if (error is HttpFailure) append(" http=").append(error.status)
            if (durationMs != null) append(" ms=").append(durationMs)
        }
    }

    suspend fun report(): String? = withContext(Dispatchers.IO) {
        val events = disk.withLock { read() }
        if (events.isEmpty()) return@withContext null
        @Suppress("DEPRECATION")
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
        "MJWatch $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            events.joinToString("\n")
    }

    suspend fun copyReport(): Boolean {
        val report = report() ?: return false
        withContext(Dispatchers.Main) {
            val clipboard = app.getSystemService(ClipboardManager::class.java)
                ?: throw IOException("Clipboard unavailable")
            clipboard.setPrimaryClip(ClipData.newPlainText("Диагностика MJWatch", report))
        }
        return true
    }

    private fun read(): List<String> = try {
        file.openRead().bufferedReader().use { it.readLines().takeLast(LIMIT) }
    } catch (_: FileNotFoundException) {
        emptyList()
    }

    companion object {
        private const val LIMIT = 20
        @Volatile private var instance: Diagnostics? = null

        fun get(context: Context): Diagnostics = instance ?: synchronized(this) {
            instance ?: Diagnostics(context.applicationContext).also { instance = it }
        }
    }
}
