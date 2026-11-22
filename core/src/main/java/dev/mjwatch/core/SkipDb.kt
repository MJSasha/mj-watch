package dev.mjwatch.core

import android.util.LruCache
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private data class CachedSkipResponse(val json: JSONObject, val expiresAt: Long)
private val skipCache = LruCache<String, CachedSkipResponse>(256)
private const val SKIP_DB = "https://api.skipdb.tv"
private val skipClient by lazy {
    guardedClient(Config(jackett = SKIP_DB, torrServer = "")).newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
}
private val tmdbSeriesId = Regex("tmdb:tv:([1-9][0-9]{0,9})")
private val imdbSeriesId = Regex("tt[0-9]{7,12}")
private val animeEpisode = Regex("""\s[-–]\s([0-9]{1,3})(?:v[0-9]+)?(?:\s*[\[(]|\s*$)""", RegexOption.IGNORE_CASE)

private suspend fun cachedSkipResponse(key: String, read: suspend () -> JSONObject): JSONObject {
    val now = android.os.SystemClock.elapsedRealtime()
    skipCache.get(key)?.takeIf { it.expiresAt > now }?.let { return it.json }
    return read().also { skipCache.put(key, CachedSkipResponse(it, now + TimeUnit.HOURS.toMillis(12))) }
}

suspend fun loadSkipSegments(
    config: Config, secrets: SecretStore, metadataId: String?, path: String, durationMs: Long,
    seasonHint: Int?, episodeHint: Int?,
): List<SkipSegment> {
    val id = tmdbSeriesId.matchEntire(metadataId.orEmpty())?.groupValues?.get(1) ?: return emptyList()
    val number = episodeNumber(path)
    val episode = number.episode ?: episodeHint ?: animeEpisode.find(path.substringAfterLast('/').substringBeforeLast('.'))
        ?.groupValues?.get(1)?.toIntOrNull() ?: return emptyList()
    if (episode !in 1..9999 || durationMs < 1_000) return emptyList()
    if (!config.managed && config.metadataProvider != "tmdb") return emptyList()

    val series = cachedSkipResponse("series:$id") {
        if (config.managed) {
            ManagedApi(config, secrets).authorized("metadata/tv/$id?external=1")
        } else {
            val client = guardedClient(config.copy(jackett = "", torrServer = ""))
            val url = endpoint("https://api.themoviedb.org").child("3/tv/$id").newBuilder()
                .addQueryParameter("append_to_response", "external_ids").build()
            JSONObject(String(client.bytes(Request.Builder().url(url)
                .header("Authorization", "Bearer ${secrets.get("tmdb")}").build(), limit = 512 * 1024L)))
        }
    }
    val imdb = series.optJSONObject("external_ids")?.optString("imdb_id")
        ?.takeIf(imdbSeriesId::matches) ?: return emptyList()
    val season = number.season ?: seasonHint ?: 1.takeIf { series.optInt("number_of_seasons") == 1 }
        ?: return emptyList()
    if (season !in 1..999) return emptyList()
    val seconds = (durationMs + 500) / 1_000
    val response = cachedSkipResponse("skip:$imdb:$season:$episode:$seconds") {
        val url = endpoint(SKIP_DB).child("api/segments").newBuilder()
            .addQueryParameter("imdb_id", imdb)
            .addQueryParameter("season", season.toString())
            .addQueryParameter("episode", episode.toString())
            .addQueryParameter("duration", seconds.toString()).build()
        JSONObject(String(skipClient.bytes(Request.Builder().url(url).build(), limit = 16 * 1024L)))
    }
    return parseSkipSegments(response, durationMs)
}

fun parseSkipSegments(response: JSONObject, durationMs: Long): List<SkipSegment> {
    val segments = response.optJSONObject("segments") ?: return emptyList()
    val matches = SegmentKind.entries.mapNotNull { kind ->
        val marker = segments.optJSONObject(kind.apiKey) ?: return@mapNotNull null
        val start = marker.optLong("start_ms", -1)
        val end = marker.optLong("end_ms", -1)
        val match = marker.optString("match")
        val exact = match == "exact"
        val shifted = match == "shifted" && marker.optLong("offset_ms", Long.MAX_VALUE) in -15_000L..15_000L
        if ((!exact && !shifted) || !(marker.optDouble("confidence") >= 0.7) ||
            start !in 0 until durationMs || end <= start || end > durationMs + 2_000 ||
            minOf(end, durationMs) - start < 5_000) {
            return@mapNotNull null
        }
        SkipSegment(kind, start, end.coerceAtMost(durationMs))
    }
    val preview = matches.firstOrNull { it.kind == SegmentKind.PREVIEW }
    return matches.mapNotNull { segment ->
        if (segment.kind != SegmentKind.OUTRO || preview == null || preview.startMs >= segment.endMs ||
            preview.endMs <= segment.startMs) segment
        else if (preview.startMs > segment.startMs) segment.copy(endMs = preview.startMs)
        else null
    }.sortedBy { it.startMs }
}

enum class SegmentKind(val apiKey: String, val label: String, val action: String) {
    INTRO("intro", "Опенинг", "Пропустить опенинг"),
    RECAP("recap", "В предыдущих сериях", "Пропустить «В предыдущих сериях»"),
    OUTRO("outro", "Титры", "Пропустить титры"),
    PREVIEW("preview", "Анонс следующей серии", "Пропустить анонс"),
}

data class SkipSegment(
    val kind: SegmentKind, val startMs: Long, val endMs: Long,
) {
    fun isValidFor(duration: Long) = startMs >= 0 && endMs > startMs && endMs <= duration

    fun contains(position: Long, duration: Long) = isValidFor(duration) && position in startMs until endMs
}
