package dev.mjwatch.core

data class Release(val id: String, val title: String, val source: String, val hash: String? = null,
    val magnet: String? = null, val download: String? = null, val size: Long? = null, val seeds: Int? = null) {
    val resolution: String get() = releaseResolution(title)
}
private val videoExtensions=setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts")
private val samplePattern=Regex("(?i)(^|[/_. -])sample([/_. -]|$)")
data class TorrentFile(val serverFileId: Int, val path: String, val size: Long) {
    val video get() = path.substringAfterLast('.').lowercase() in videoExtensions
    val sample get() = samplePattern.containsMatchIn(path)
}
sealed interface TorrentInput {
    data class Magnet(val link:String):TorrentInput
    data class File(val bytes:ByteArray):TorrentInput
}
data class TorrentHandle(val hash: String, val files: List<TorrentFile>,val contentHash:String?=null) {
    internal val fileIds by lazy {files.mapTo(HashSet()) {it.serverFileId}}
}
data class StreamRequest(val uri: String, val headers: Map<String, String> = emptyMap())
data class SearchResult(val releases: List<Release>, val failures: List<String> = emptyList(),
    val more: Boolean = false, val nextCursor: String? = null)
data class Config(val schemaVersion: Int = 1, val jackett: String = "https://jac.red", val indexers: String = "",
    val torrServer: String = "", val localHttp: Boolean = false, val demo: Boolean = false,
    val theme: String = "system", val metadataProvider: String = "none", val torznabPaths: Map<String,String> = emptyMap(), val releaseApi:String="auto", val connectionMode:String="direct", val managedUrl:String="", val serverId:String="", val userId:String="")

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
interface MetadataAdapter {
    suspend fun search(query: String): List<Metadata>
    suspend fun home(): List<Metadata> = emptyList()
    suspend fun browse(section: CatalogSection): List<Metadata> = home().filter(section::matches)
    suspend fun details(id: String): Metadata? = null
    suspend fun artworkDetails(id: String): Metadata? = details(id)
    suspend fun cast(id: String): List<CastMember> = emptyList()
    suspend fun actorFilmography(personId: Long): List<Metadata> = emptyList()
}
data class CastMember(val id: Long, val name: String, val character: String, val profile: String?)
data class Metadata(val id: String, val title: String, val poster: String?, val overview: String?, val kind:String="movie", val year:Int?=null, val backdrop:String?=null, val animation:Boolean=false, val originalLanguage:String?=null, val genre:String?=null, val runtimeMinutes:Int?=null, val logo:String?=null)
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
    val files = listOf(
        TorrentFile(17, "Сезон 1/Серия 02.mp4", 2_400_000),
        TorrentFile(3, "Сезон 1/Серия 01.mp4", 2_400_000), TorrentFile(9, "sample.mp4", 2_400_000))
    override suspend fun status(hash: String) = TorrentHandle(hash, files)
    override fun stream(handle: TorrentHandle, file: TorrentFile) = StreamRequest("asset:///demo.mp4")
}

val Config.managed get()=connectionMode=="managed"
val Config.scope get()=if(managed&&serverId.isNotBlank()&&userId.isNotBlank()) "$serverId:$userId" else ""
val Config.historyEnabled get()=managed&&scope.isNotBlank()

fun contentKey(hash:String,path:String,size:Long):String {require(hash.matches(Regex("[a-fA-F0-9]{40}")));val normalized=path.replace('\\','/').split('/').filter {it.isNotEmpty()&&it!="."}.joinToString("/");require(normalized.split('/').none {it==".."});return "${hash.lowercase()}|$normalized|$size"}

fun normalizeDirectAddress(raw:String):String {val value=raw.trim();if(value.isEmpty()||value.contains("://"))return value;require(value.none {it in "/?#@ \t\r\n"});val host=if(value.count {it==':'}>1&&!value.startsWith("[")) "[$value]" else value;val url=endpoint("http://$host");val port=if(url.port==80&&!Regex(":([0-9]+)$").containsMatchIn(host))8090 else url.port;val address=if(url.host.contains(':'))"[${url.host}]" else url.host;return "http://$address:$port"}

val Config.profileKey get()="$connectionMode:$scope"
