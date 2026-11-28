package dev.mjwatch.core

private val titlePunctuation = Regex("[^\\p{L}\\p{N}]+")
private val releaseYear = Regex("(?<![\\p{L}\\p{N}])(?:18|19|20)\\d{2}(?![\\p{L}\\p{N}])")
private val yearRange = Regex("${releaseYear.pattern}\\s*[-–—]\\s*${releaseYear.pattern}")
private val collectionLabel = Regex(
    """(?i)(?<![\p{L}\p{N}])(?:коллекция|дилогия|трилогия|квадрология|тетралогия|пенталогия|сборник|антология|collection|duology|trilogy|quadrilogy|tetralogy|anthology)(?![\p{L}\p{N}])"""
)
private val gameLabel = Regex(
    """(?i)(?<![\p{L}\p{N}])(?:(?:the[ ._-]+)?video[ ._-]+game|видеоигра|repack|gog|steamrip|xbox[ ._-]*(?:360|one)|ps[345])(?![\p{L}\p{N}])"""
)
private val videoLabel = Regex(
    """(?i)(?<![\p{L}\p{N}])(?:[248]K|(?:480|576|720|1080|2160|4320)[pi]|UHD|Blu[ ._-]*Ray|[BH]D[ ._-]*Rip|BRRip|BDRemux|Remux|HDTV|WEB[ ._-]*(?:DL|Rip)|DVD(?:Rip|Remux|[59])?|DVDScr|CAMRip|HDRip|H[ ._-]*26[45]|x26[45]|HEVC|AV1|mkv|mp4|avi)(?![\p{L}\p{N}])"""
)

private fun String.normalizedTitle() = lowercase().replace('ё', 'е').replace(titlePunctuation, " ").trim()

enum class MovieReleaseMatch { Exact, Unconfirmed, Collection }

class MovieReleaseMatcher(movie: Metadata) {
    private val names = listOfNotNull(movie.title, movie.originalTitle).map { it.normalizedTitle() }.toSet() - ""
    private val year = movie.year

    fun match(release: Release): MovieReleaseMatch? {
        if (release.categories.any { it in 1000..1999 || it == 4050 }) return null
        val title = release.title
        val videoStart = videoLabel.find(title)?.range?.first
        val head = videoStart?.let { title.substring(0, it) } ?: title
        if (gameLabel.containsMatchIn(head)) return null
        val range = yearRange.find(head)?.value?.let { releaseYear.findAll(it).map { match -> match.value.toInt() }.toList() }
        val segments = head.split('/').let { if (it.size > 1) it + head else it }
        val years = mutableSetOf<Int>()
        var matchingTitle = false
        var collectionSuffix = false
        for (segment in segments) {
            val name = segment.normalizedTitle()
            val yearMatch = releaseYear.findAll(segment).firstOrNull { match ->
                val prefix = segment.substring(0, match.range.first).normalizedTitle()
                val throughYear = segment.substring(0, match.range.last + 1).normalizedTitle()
                prefix.isNotEmpty() && names.none { it == throughYear || it.startsWith("$throughYear ") }
            }
            yearMatch?.value?.toInt()?.let(years::add)
            val beforeYear = yearMatch?.let { segment.substring(0, it.range.first) } ?: segment
            val beforeLabel = collectionLabel.split(beforeYear, limit = 2).first().normalizedTitle()
            if (beforeYear.normalizedTitle() in names || name in names) {
                matchingTitle = true
                if (yearMatch != null && collectionLabel.containsMatchIn(segment.substring(yearMatch.range.last + 1))) {
                    collectionSuffix = true
                }
            } else if (beforeLabel in names) {
                matchingTitle = true
                collectionSuffix = true
            } else if (year == null && names.any { beforeYear.normalizedTitle().startsWith("$it ") }) {
                matchingTitle = true
            }
        }
        if (!matchingTitle) return null
        if (collectionSuffix || range != null || videoStart?.let {collectionLabel.containsMatchIn(title.substring(it))} == true) {
            if (range != null && year != null && year !in range.first()..range.last()) return null
            return MovieReleaseMatch.Collection
        }
        return when {
            year == null || years.isEmpty() -> MovieReleaseMatch.Unconfirmed
            years.all { it == year } -> MovieReleaseMatch.Exact
            else -> null
        }
    }

    fun rank(releases: List<Release>): List<Release> = releases
        .mapNotNull { release -> match(release)?.let { release to it } }
        .sortedWith(compareBy<Pair<Release, MovieReleaseMatch>> { it.second }
            .thenByDescending { it.first.seeds ?: -1 })
        .map { it.first }
        .distinctBy { it.hash ?: it.id }
}

class MovieReleaseSearch private constructor(
    private val matcher: MovieReleaseMatcher,
    private val pending: List<Page>,
    val result: SearchResult,
) {
    private data class Page(val query: String, val offset: Int = 0, val cursor: String? = null) {
        fun next(result: SearchResult): Page? =
            if (result.more) copy(offset = offset + 1, cursor = result.nextCursor) else null
    }

    suspend fun loadMore(fetch: suspend (String, Int, String?) -> SearchResult): MovieReleaseSearch {
        val page = pending.firstOrNull() ?: return this
        val next = fetch(page.query, page.offset, page.cursor)
        val remaining = when {
            next.failures.isNotEmpty() && next.releases.isEmpty() -> pending
            else -> listOfNotNull(page.next(next)) + pending.drop(1)
        }
        return MovieReleaseSearch(matcher, remaining, SearchResult(
            matcher.rank(result.releases + next.releases), next.failures,
            more = remaining.isNotEmpty(), nextCursor = remaining.firstOrNull()?.cursor))
    }

    companion object {
        suspend fun start(movie: Metadata, fetch: suspend (String, Int, String?) -> SearchResult): MovieReleaseSearch {
            val matcher = MovieReleaseMatcher(movie)
            val broad = Page(movie.title)
            val focused = movie.year?.let { Page("${movie.title} $it") }
            val first = focused?.let { fetch(it.query, 0, null) }
            val ranked = matcher.rank(first?.releases.orEmpty())
            val fallback = if (ranked.count { matcher.match(it) == MovieReleaseMatch.Exact } < 3)
                fetch(broad.query, 0, null) else null
            val pending = listOfNotNull(fallback?.let(broad::next), focused?.let { page -> first?.let(page::next) })
            val releases = matcher.rank(ranked + fallback?.releases.orEmpty())
            val failures = (fallback ?: first)?.failures.orEmpty()
            return MovieReleaseSearch(matcher, pending, SearchResult(releases, failures,
                more = pending.isNotEmpty(), nextCursor = pending.firstOrNull()?.cursor))
        }
    }
}
