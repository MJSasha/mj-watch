package dev.mjwatch.core

data class Release(val id: String, val title: String, val source: String, val hash: String? = null,
    val magnet: String? = null, val download: String? = null, val size: Long? = null, val seeds: Int? = null) {
    val resolution: String get() = Regex("(?i)(2160p|1080p|720p|4K)").find(title)?.value ?: "Не указано"
}
data class TorrentFile(val serverFileId: Int, val path: String, val size: Long) {
    val video get() = path.substringAfterLast('.').lowercase() in setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts")
    val sample get() = Regex("(?i)(^|[/_. -])sample([/_. -]|$)").containsMatchIn(path)
}
sealed interface TorrentInput {
    data class Magnet(val link:String):TorrentInput
    data class File(val bytes:ByteArray):TorrentInput
}
data class TorrentHandle(val hash: String, val files: List<TorrentFile>)
data class StreamRequest(val uri: String, val headers: Map<String, String> = emptyMap())
data class SearchResult(val releases: List<Release>, val failures: List<String> = emptyList(), val more: Boolean = false)
data class Config(val schemaVersion: Int = 1, val jackett: String = "", val indexers: String = "",
    val torrServer: String = "", val localHttp: Boolean = false, val demo: Boolean = false,
    val theme: String = "system", val metadataProvider: String = "none", val torznabPaths: Map<String,String> = emptyMap(), val releaseApi:String="auto")
interface ReleaseRepository {
    suspend fun search(query: String): SearchResult
    suspend fun check(): String
}
interface StreamGateway {
    suspend fun check(): String
    suspend fun add(release: Release): TorrentHandle
    suspend fun status(hash: String): TorrentHandle
    fun stream(handle: TorrentHandle, file: TorrentFile): StreamRequest
}
interface MetadataAdapter { suspend fun search(query: String): List<Metadata>; suspend fun home():List<Metadata> = emptyList() }
data class Metadata(val id: String, val title: String, val poster: String?, val overview: String?, val kind:String="movie", val year:Int?=null, val backdrop:String?=null, val animation:Boolean=false, val originalLanguage:String?=null)
object NoMetadata : MetadataAdapter { override suspend fun search(query: String) = emptyList<Metadata>() }
object DemoReleases : ReleaseRepository {
    val items = listOf(Release("demo:orbit", "Тихая орбита • тестовое видео 720p", "Локальный fixture", seeds = 8),
        Release("demo:series", "Тестовый сериал S01 • 1080p", "Локальный fixture", seeds = 0))
    override suspend fun check() = "Демонстрация · сеть не используется"
    override suspend fun search(query: String): SearchResult {
        kotlinx.coroutines.delay(250)
        return SearchResult(if (query.isBlank() || query == "тест") items else items.filter { it.title.contains(query, true) })
    }
}
object DemoGateway : StreamGateway {
    override suspend fun check() = "Локальный MP4 · сеть не используется"
    override suspend fun add(release: Release): TorrentHandle { kotlinx.coroutines.delay(400); return status(release.id) }
    override suspend fun status(hash: String) = TorrentHandle(hash, listOf(
        TorrentFile(17, "Сезон 1/Серия 02.mp4", 2_400_000),
        TorrentFile(3, "Сезон 1/Серия 01.mp4", 2_400_000), TorrentFile(9, "sample.mp4", 2_400_000)))
    override fun stream(handle: TorrentHandle, file: TorrentFile) = StreamRequest("asset:///demo.mp4")
}
