package dev.mjwatch.core

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.media.MediaMetadataRetriever
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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
    private val dao by lazy { Room.databaseBuilder(app, OfflineDatabase::class.java, "offline.db")
        .addMigrations(OfflineDatabase.MIGRATION_1_2).build().downloads() }
    private val secrets=SecretStore(app)
    private val directory get() = File(app.noBackupFilesDir, "offline-media")
    private val commands = Mutex()
    private val drainLock = Mutex()
    private val posterWrites = Mutex()
    private var active: Pair<String, Job>? = null

    // JobScheduler may still report a finished job until it processes jobFinished.
    private var finishedJob = false

    fun observe(config: Config, readyOnly: Boolean = false): Flow<List<OfflineDownload>> =
        if (!eligible(config)) flowOf(emptyList())
        else dao.observe(profile(config)).map { rows ->
            rows.map { it.item }.filter { !readyOnly || (it.state == OfflineState.READY && verifiedFile(it) != null) }
        }.flowOn(Dispatchers.IO)

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
    ): String = enqueueFiles(config, release, handle, listOf(file), metadata, season, episode).single()

    suspend fun enqueueSeason(config: Config, release: Release, handle: TorrentHandle, season: Int,
        metadata: Metadata? = null
    ): List<String> = enqueueFiles(config, release, handle, seasonDownloadFiles(handle.files, release.title, season), metadata, season)

    suspend fun enqueueFiles(
        config: Config, release: Release, handle: TorrentHandle, files: List<TorrentFile>,
        metadata: Metadata? = null, season: Int? = null, episode: Int? = null
    ): List<String> = withContext(Dispatchers.IO) {
        require(eligible(config)) { "Загрузка доступна для реального источника на мобильном устройстве" }
        val byId = handle.files.groupBy { it.serverFileId }
        require(files.isNotEmpty() && files.all { it.video && it.size > 0 && it.serverFileId > 0 &&
            byId[it.serverFileId]?.singleOrNull() == it }) { "Выберите видеофайлы с известным размером" }
        require(handle.hash.matches(Regex("[a-zA-Z0-9_-]+")))
        val hash = if (config.managed) requireNotNull(handle.contentHash) else handle.hash
        val profile = profile(config)
        val source = source(config)
        val posterPath = metadata?.poster?.let { offlinePosterPath(config, it) }
        val createdAt = System.currentTimeMillis()
        val records = files.map { file ->
            val key = contentKey(hash, file.path, file.size)
            val id = MessageDigest.getInstance("SHA-256").digest("$profile\u0000$key".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val number = episodeNumber(file.path)
            val item = OfflineDownload(id, release.id, metadata?.title ?: release.title, metadata?.id,
                file.serverFileId, file.path, if (config.managed) key else "", number.season ?: season,
                number.episode ?: episode, 0, file.size, OfflineState.QUEUED, null)
            OfflineRecord(item, profile, source, handle.hash, createdAt, posterPath)
        }.distinctBy { it.item.id }
        commands.withLock {
            val queue = records.filterNot { row ->
                val previous = dao.get(profile, row.item.id)?.item
                previous != null && (previous.state in pending ||
                    previous.state == OfflineState.READY && verifiedFile(previous) != null)
            }.map { row -> row.copy(item = row.item.copy(bytesDownloaded = partial(row.item.id).length())) }
            if (queue.isNotEmpty()) dao.put(queue)
            if (dao.next(profile) != null) schedule()
        }
        records.map { it.item.id }
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
            deleteFile(poster(id))
            deleteFile(posterPart(id))
            dao.update(id, OfflineState.CANCELLED, 0)
        }
    }

    suspend fun remove(config: Config, id: String) = withContext(Dispatchers.IO) {
        commands.withLock {
            val row = entry(config, id) ?: return@withLock
            stopTransfer(id)
            deleteFile(partial(id))
            deleteFile(media(row.item))
            deleteFile(poster(id))
            deleteFile(posterPart(id))
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

    suspend fun localPoster(config: Config, ids: List<String>, allowNetwork: Boolean): File? = withContext(Dispatchers.IO) {
        val rows = ids.mapNotNull { entry(config, it) }
        rows.firstNotNullOfOrNull { row -> existingPoster(row.item.id) }
            ?: if (allowNetwork) {
                val row = rows.firstOrNull { it.posterPath != null }
                    ?: rows.firstOrNull { it.item.metadataId != null }
                row?.let { optionalOfflineMetadata { savePoster(config, it) } }
            } else null
    }

    suspend fun localSegments(config: Config, id: String): List<SkipSegment> = withContext(Dispatchers.IO) {
        val row = entry(config, id)?.takeIf { it.item.state == OfflineState.READY }
        decodeSegments(row?.segments)
    }

    private fun poster(id: String) = File(directory, "$id.poster")
    private fun posterPart(id: String) = File(directory, "$id.poster.part")
    private fun existingPoster(id: String): File? {
        val file = poster(id).takeIf { it.isFile && it.length() > 0 } ?: return null
        val bounds = android.graphics.BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        return file.takeIf { bounds.outWidth > 0 && bounds.outHeight > 0 }
    }
    private fun media(item: OfflineDownload) = File(directory, "${item.id}.${item.path.substringAfterLast('.').lowercase()}")
    private fun partial(id: String) = File(directory, "$id.part")
    private fun verifiedFile(item: OfflineDownload) = media(item).takeIf { it.isFile && it.length() == item.totalBytes }
    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) throw OfflineProblem("Не удалось удалить локальный файл")
    }

    private val pending = setOf(OfflineState.QUEUED, OfflineState.DOWNLOADING)
    private fun source(config: Config):String {
        val url=endpoint(if(config.managed)config.managedUrl else config.torrServer)
        return if(config.managed)secrets.get(downloadSourceKey(url,config.scope)).ifBlank {url.toString()} else url.toString()
    }
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

    private class DownloadServerChanged:IOException()

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
                        val updated=configs.first { !matches(it,row)||it.managedUrl!=config.managedUrl }
                        if(config.managed&&matches(updated,row))throw DownloadServerChanged()
                        throw OfflineProblem("Подключение изменилось во время загрузки")
                    }
                    try {
                        retryOfflineTransfer(onRetry = {
                            notify(item.copy(bytesDownloaded = part.length(), state = OfflineState.DOWNLOADING,
                                error = "Восстанавливаем соединение"))
                        }) {
                            try {
                                fetch(config, row, part) { bytes ->
                                    dao.update(item.id, OfflineState.DOWNLOADING, bytes)
                                    notify(item.copy(bytesDownloaded = bytes, state = OfflineState.DOWNLOADING, error = null))
                                }
                            } catch(e:ServerMove) {
                                // The config observer cancels this attempt as soon as sync verifies the new origin.
                                delay(35_000)
                                throw OfflineProblem("Сервер переехал. После синхронизации повторите загрузку")
                            }
                        }
                    } finally { monitor.cancel() }
                }
                Files.move(part.toPath(), media(item).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            val config = Settings(app).config.first()
            val segments = if (matches(config, row)) {
                optionalOfflineMetadata { savePoster(config, row) }
                optionalOfflineMetadata { downloadSegments(config, item, media(item)) }
            } else null
            dao.ready(item.id, segments)
        } catch (_:DownloadServerChanged) {
            dao.update(item.id,OfflineState.QUEUED,part.length())
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

    private suspend fun savePoster(config: Config, row: OfflineRecord): File? = posterWrites.withLock {
        val id = row.item.id
        val target = poster(id)
        existingPoster(id)?.let { return@withLock it }
        val api = if (config.managed) ManagedApi(config, secrets) else null
        val path = row.posterPath ?: run {
            val metadataId = row.item.metadataId?.takeIf { tmdbDownloadId.matches(it) } ?: return@withLock null
            val adapter = if (api != null) ManagedMetadata(api) else {
                val token = secrets.get("tmdb").takeIf { it.isNotBlank() && config.metadataProvider == "tmdb" }
                    ?: return@withLock null
                TmdbAdapter(token, guardedClient(config))
            }
            adapter.details(metadataId)?.poster?.let { offlinePosterPath(config, it) } ?: return@withLock null
        }
        val bytes = if (api != null) api.poster(path) else {
            require(posterImagePath.matches(path))
            val url = endpoint("https://image.tmdb.org").child("t/p/$path")
            guardedClient(config.copy(metadataProvider = "tmdb")).bytes(Request.Builder().url(url).build(), limit = 2L * 1024 * 1024)
        }
        val image = android.graphics.BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, image)
        if (image.outWidth <= 0 || image.outHeight <= 0) return@withLock null
        commands.withLock commit@ {
            val current = entry(config, id)?.item ?: return@commit null
            if (current.state == OfflineState.CANCELLED) return@commit null
            if (!directory.isDirectory && !directory.mkdirs()) return@commit null
            val part = posterPart(id)
            try {
                part.writeBytes(bytes)
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                target
            } finally {
                part.delete()
            }
        }
    }

    private suspend fun downloadSegments(config: Config, item: OfflineDownload, file: File): String? {
        val metadataId = item.metadataId?.takeIf { it.startsWith("tmdb:tv:") } ?: return null
        val retriever = MediaMetadataRetriever()
        val duration = try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        } ?: return null
        val segments = loadSkipSegments(config, secrets, metadataId, item.path, duration, item.season, item.episode)
        return encodeSegments(segments)
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
        val view = if (api != null) java.util.UUID.randomUUID().toString() else null
        while (true) {
            val stream = gateway.stream(handle, selected)
            val url = if (view == null) stream.uri else stream.uri.toHttpUrl().newBuilder()
                .setQueryParameter("view", view).setQueryParameter("download", "1").build().toString()
            val request = Request.Builder().url(url).apply {
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

// Keep existing download IDs and source isolation after a verified managed-server move.
private val posterImagePath = Regex("[a-zA-Z0-9_-]+/[a-zA-Z0-9._-]+\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
private val tmdbDownloadId = Regex("tmdb:(movie|tv):[1-9][0-9]{0,9}")

internal fun offlinePosterPath(config: Config, raw: String): String? {
    val url = raw.toHttpUrlOrNull() ?: return null
    if (config.managed) return managedArtworkPath(url)?.removePrefix(MANAGED_IMAGE_PREFIX)
    if (url.origin() != "https://image.tmdb.org:443" || url.encodedQuery != null) return null
    return url.encodedPath.takeIf { it.startsWith("/t/p/") }?.removePrefix("/t/p/")
        ?.takeIf { posterImagePath.matches(it) }
}

private suspend fun <T> optionalOfflineMetadata(load: suspend () -> T): T? = withTimeoutOrNull(8_000) {
    try {
        load()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

internal fun encodeSegments(segments: List<SkipSegment>): String? =
    segments.takeIf { it.isNotEmpty() }?.let {
        JSONArray(it.map { segment ->
            JSONObject().put("kind", segment.kind.apiKey).put("startMs", segment.startMs)
                .put("endMs", segment.endMs)
        }).toString()
    }

internal fun decodeSegments(encoded: String?): List<SkipSegment> = if (encoded == null) emptyList() else runCatching {
    val rows = JSONArray(encoded)
    (0 until rows.length()).mapNotNull { index ->
        val row = rows.getJSONObject(index)
        val kind = SegmentKind.entries.firstOrNull { it.apiKey == row.optString("kind") } ?: return@mapNotNull null
        SkipSegment(kind, row.getLong("startMs"), row.getLong("endMs"))
    }
}.getOrDefault(emptyList())

internal fun downloadSourceKey(url:HttpUrl,scope:String)="download-source:$url:$scope"

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
