package dev.mjwatch.core

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class OfflineDownloads private constructor(private val app: Context) {
    companion object {
        const val OPEN_DOWNLOADS = "dev.mjwatch.action.OPEN_DOWNLOADS"

        @Volatile private var instance: OfflineDownloads? = null

        fun get(context: Context): OfflineDownloads = instance ?: synchronized(this) {
            instance ?: OfflineDownloads(context.applicationContext).also { instance = it }
        }
    }

    private val supported get() = !app.packageName.endsWith(".tv") &&
        (app.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_TELEVISION
    private val dao by lazy { Room.databaseBuilder(app, OfflineDatabase::class.java, "offline.db").build().downloads() }
    private val directory get() = File(app.noBackupFilesDir, "offline-media")
    private val commands = Mutex()
    private val drainLock = Mutex()
    private var active: Pair<String, Job>? = null

    // JobScheduler may still report a finished job until it processes jobFinished.
    private var finishedJob = false

    fun observe(config: Config): Flow<List<OfflineDownload>> =
        if (!eligible(config)) flowOf(emptyList())
        else dao.observe(profile(config)).map { rows -> rows.map { it.item } }

    suspend fun resumePending() = withContext(Dispatchers.IO) {
        val config = Settings(app).config.first()
        commands.withLock {
            if (eligible(config) && active?.second?.isActive != true && dao.next(profile(config)) != null) {
                schedule()
            }
        }
    }

    private fun eligible(config: Config) = supported && !config.demo &&
        if (config.managed) config.scope.isNotBlank() && config.managedUrl.isNotBlank()
        else config.torrServer.isNotBlank()

    suspend fun enqueue(
        config: Config, release: Release, handle: TorrentHandle, file: TorrentFile,
        metadata: Metadata? = null, season: Int? = null, episode: Int? = null
    ): String = withContext(Dispatchers.IO) {
        require(supported && !config.demo) { "Загрузка доступна для реального источника на мобильном устройстве" }
        require(!config.managed || config.scope.isNotBlank())
        require(file.video && file.size > 0 && file.serverFileId > 0 && handle.files.singleOrNull { it.serverFileId == file.serverFileId } == file) {
            "Выберите видеофайл с известным размером"
        }
        val key = contentKey(if (config.managed) requireNotNull(handle.contentHash) else handle.hash, file.path, file.size)
        require(handle.hash.matches(Regex("[a-zA-Z0-9_-]+")))
        val profile = profile(config)
        val source = source(config)
        val id = MessageDigest.getInstance("SHA-256").digest("$profile\u0000$key".toByteArray())
            .joinToString("") { "%02x".format(it) }
        commands.withLock {
            val previous = dao.get(profile, id)
            if (previous?.item?.state == OfflineState.READY && verifiedFile(previous.item) != null) return@withLock
            if (previous?.item?.state in pending) {
                schedule()
                return@withLock
            }
            val number = episodeNumber(file.path)
            val item = OfflineDownload(id, release.id, metadata?.title ?: release.title, metadata?.id,
                file.serverFileId, file.path, if (config.managed) key else "", season ?: number.season,
                number.episode ?: episode, partial(id).length(), file.size, OfflineState.QUEUED, null)
            dao.put(OfflineRecord(item, profile, source, handle.hash, System.currentTimeMillis()))
            schedule()
        }
        id
    }

    suspend fun retry(config: Config, id: String) = withContext(Dispatchers.IO) {
        commands.withLock {
            val row = entry(config, id) ?: return@withLock
            if (row.item.state !in setOf(OfflineState.FAILED, OfflineState.CANCELLED)) return@withLock
            check(matches(config, row)) { "Восстановите подключение к источнику загрузки" }
            dao.update(id, OfflineState.QUEUED, partial(id).length())
            schedule()
        }
    }

    suspend fun cancel(config: Config, id: String) = withContext(Dispatchers.IO) {
        commands.withLock {
            val row = entry(config, id) ?: return@withLock
            if (row.item.state !in pending) return@withLock
            stopTransfer(id)
            // The transfer may have finished between the first read and cancellation.
            if (entry(config, id)?.item?.state == OfflineState.READY) return@withLock
            deleteFile(partial(id))
            deleteFile(media(row.item))
            dao.update(id, OfflineState.CANCELLED, 0)
        }
    }

    suspend fun remove(config: Config, id: String) = withContext(Dispatchers.IO) {
        commands.withLock {
            val row = entry(config, id) ?: return@withLock
            stopTransfer(id)
            deleteFile(partial(id))
            deleteFile(media(row.item))
            dao.delete(id)
        }
    }

    suspend fun localFile(config: Config, id: String): File? = withContext(Dispatchers.IO) {
        commands.withLock {
            val item = entry(config, id)?.item ?: return@withLock null
            if (item.state != OfflineState.READY) return@withLock null
            verifiedFile(item).also {
                if (it == null) dao.update(id, OfflineState.FAILED, 0, "Локальный файл отсутствует или повреждён")
            }
        }
    }

    private fun media(item: OfflineDownload) = File(directory, "${item.id}.${item.path.substringAfterLast('.').lowercase()}")
    private fun partial(id: String) = File(directory, "$id.part")
    private fun verifiedFile(item: OfflineDownload) = media(item).takeIf { it.isFile && it.length() == item.totalBytes }
    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) throw OfflineProblem("Не удалось удалить локальный файл")
    }

    private val pending = setOf(OfflineState.QUEUED, OfflineState.DOWNLOADING)
    private fun source(config: Config) = endpoint(if (config.managed) config.managedUrl else config.torrServer).toString()
    private fun profile(config: Config) = if (config.managed) "${config.profileKey}@${source(config)}" else config.profileKey
    private fun matches(config: Config, row: OfflineRecord) = !config.demo &&
        runCatching { profile(config) == row.profile && source(config) == row.source &&
            (endpoint(row.source).isHttps || config.localHttp) }.getOrDefault(false)
    private suspend fun entry(config: Config, id: String) =
        if (supported && !config.demo && (!config.managed || config.scope.isNotBlank())) dao.get(profile(config), id) else null

    private suspend fun stopTransfer(id: String) {
        active?.takeIf { it.first == id }?.second?.cancelAndJoin()
    }

    private fun schedule() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val scheduler = app.getSystemService(JobScheduler::class.java)
                val id = OfflineDownloadJob.ID
                if (!finishedJob && scheduler.getPendingJob(id) != null) return
                val job = JobInfo.Builder(id, ComponentName(app, OfflineDownloadJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setUserInitiated(true)
                    .build()
                if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) finishedJob = false
            } else {
                app.startForegroundService(Intent(app, OfflineDownloadService::class.java))
            }
        } catch (_: IllegalStateException) {
            // Keep the queue and partial files if the device temporarily disallows a start.
        }
    }

    internal suspend fun drain(onJobFinished: (suspend () -> Unit)? = null, notify: (OfflineDownload) -> Unit) =
        withContext(Dispatchers.IO) {
            drainLock.withLock {
                while (true) {
                    val task = commands.withLock {
                        val config = Settings(app).config.first()
                        val row = if (eligible(config)) dao.next(profile(config)) else null
                        if (row == null) {
                            if (onJobFinished != null) {
                                finishedJob = true
                                onJobFinished()
                            }
                            return@withLock null
                        }
                        launch { download(row, notify) }.also { active = row.item.id to it }
                    } ?: break
                    task.join()
                    commands.withLock { if (active?.second === task) active = null }
                }
            }
        }

    private suspend fun download(row: OfflineRecord, notify: (OfflineDownload) -> Unit) {
        val item = row.item
        val part = partial(item.id)
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw OfflineProblem("Не удалось создать папку загрузок")
            // A process death can separate the atomic rename from the database commit.
            if (verifiedFile(item) == null) {
                dao.update(item.id, OfflineState.DOWNLOADING, part.length())
                notify(item.copy(state = OfflineState.DOWNLOADING, bytesDownloaded = part.length()))
                val configs = Settings(app).config
                val config = configs.first()
                if (!matches(config, row)) throw OfflineProblem("Изменилось подключение. Восстановите источник и повторите загрузку")
                coroutineScope {
                    val monitor = launch {
                        configs.first { !matches(it, row) }
                        throw OfflineProblem("Подключение изменилось во время загрузки")
                    }
                    try {
                        retryOfflineTransfer(onRetry = {
                            notify(item.copy(bytesDownloaded = part.length(), state = OfflineState.DOWNLOADING,
                                error = "Восстанавливаем соединение"))
                        }) {
                            fetch(config, row, part) { bytes ->
                                dao.update(item.id, OfflineState.DOWNLOADING, bytes)
                                notify(item.copy(bytesDownloaded = bytes, state = OfflineState.DOWNLOADING, error = null))
                            }
                        }
                    } finally { monitor.cancel() }
                }
                Files.move(part.toPath(), media(item).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            dao.update(item.id, OfflineState.READY, item.totalBytes)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                dao.update(item.id, OfflineState.QUEUED, part.length())
            }
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is OfflineProblem, is HttpFailure, is NetworkPolicyFailure -> safeError(e)
                is javax.net.ssl.SSLException -> "Не удалось проверить сертификат сервера"
                is IOException -> "Загрузка прервана. Проверьте подключение и свободное место, затем повторите"
                else -> "Не удалось сохранить выбранный файл. Повторите загрузку"
            }
            dao.update(item.id, OfflineState.FAILED, part.length(), message)
        }
    }

    internal suspend fun fetch(config: Config, row: OfflineRecord, part: File, progress: suspend (Long) -> Unit) {
        val secrets = SecretStore(app)
        val api = if (config.managed) ManagedApi(config, secrets) else null
        // Refresh credentials and verify the account before opening a managed stream.
        api?.authorized("me")
        val client = (api?.client ?: guardedClient(config.copy(jackett = "", metadataProvider = "none")))
            .newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
        val resolverClient = if (api != null && config.jackett.isNotBlank() &&
            endpoint(config.jackett).origin() == api.base.origin()) api.client else guardedClient(config)
        val resolver = TorznabRepository(config, secrets.get("jackett:${config.jackett}"), resolverClient)
        val gateway = if (api != null) ManagedGateway(api, resolver) else
            TorrServerGateway(config, secrets.get("torrAuth:${config.torrServer}"), client, resolver)
        val file = TorrentFile(row.item.fileId, row.item.path, row.item.totalBytes)
        val hash = if (config.managed) row.item.contentKey.substringBefore('|') else row.handle
        var handle = TorrentHandle(row.handle, listOf(file))
        var selected = file
        var reopened = false
        var refreshed = false
        while (true) {
            val stream = gateway.stream(handle, selected)
            val request = Request.Builder().url(stream.uri).apply {
                stream.headers.forEach { (key, value) -> header(key, value) }
            }.build()
            try {
                transferOfflineFile(client, request, part, file.size, progress)
                return
            } catch (e: HttpFailure) {
                when {
                    e.status == 401 && api != null && !refreshed -> {
                        api.refresh()
                        refreshed = true
                    }
                    e.status == 404 && !reopened -> {
                        val saved = secrets.get("release:${config.scope}:${row.item.releaseId}")
                        val release = saved.takeIf { it.isNotBlank() }?.let(::releaseFromJson)
                            ?: Release(row.item.releaseId, row.item.title, "MJWatch")
                        val recovery = if (release.magnet == null && release.download == null)
                            release.copy(hash = hash, magnet = "magnet:?xt=urn:btih:$hash") else release.copy(hash = hash)
                        val recovered = reopenOfflineFile(gateway, recovery, row.handle, hash, file)
                        handle = recovered.first
                        selected = recovered.second
                        dao.put(row.copy(item = row.item.copy(fileId = selected.serverFileId,
                            state = OfflineState.DOWNLOADING, bytesDownloaded = part.length()), handle = handle.hash))
                        reopened = true
                    }
                    else -> throw e
                }
            }
        }
    }
}

/** A re-added torrent may assign different file IDs. Only resume the same torrent and file. */
internal suspend fun reopenOfflineFile(
    gateway: StreamGateway, release: Release, oldHandle: String, hash: String, file: TorrentFile
): Pair<TorrentHandle, TorrentFile> {
    return withTimeoutOrNull(60_000) {
        var handle = try {
            gateway.status(oldHandle)
        } catch (e: HttpFailure) {
            if (e.status != 404) throw e
            gateway.add(release)
        }
        while (handle.files.isEmpty()) {
            delay(1000)
            handle = gateway.status(handle.hash)
        }
        val selected = handle.files.singleOrNull { it.path == file.path && it.size == file.size }
        if (!(handle.contentHash ?: handle.hash).equals(hash, true) || selected == null)
            throw OfflineProblem("Источник изменился. Выберите файл заново")
        handle to selected
    } ?: throw OfflineProblem("Сервер не успел подготовить файл. Повторите загрузку")
}
