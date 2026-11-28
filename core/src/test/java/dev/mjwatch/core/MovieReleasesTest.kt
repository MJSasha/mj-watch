package dev.mjwatch.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MovieReleasesTest {
    private val shrek = Metadata("tmdb:movie:808", "Шрек", null, null, year = 2001, originalTitle = "Shrek")
    private val matcher = MovieReleaseMatcher(shrek)

    @Test fun ranksTheFilmBeforePopularUnconfirmedReleasesAndCollections() {
        val releases = listOf(
            release("game", "Шрек 2 / Shrek 2: The Video Game (2004) PC | RePack", 78),
            release("third", "Шрек Третий / Shrek the Third (2007) BDRip", 31),
            release("second", "Шрек 2 / Shrek 2 (2004) BDRip", 30),
            release("pack", "Шрек / Shrek (2001 - 2010) BDRip [H.264/720p] [Коллекция]", 29),
            release("quadrilogy", "Шрек: Квадрология / Shrek: Quadrilogy (2001–2010) BDRip 720p", 27),
            release("unconfirmed", "Шрек BDRip 720p", 40),
            release("named-game", "Shrek (2001)", 99).copy(categories = setOf(4050)),
            release("original", "Shrek.2001.1080p.BluRay", 5),
            release("localized", "Шрек (2001) BDRip 720p", 12),
        )
        assertEquals(listOf("localized", "original", "unconfirmed", "pack", "quadrilogy"), matcher.rank(releases).map { it.id })
        assertEquals(MovieReleaseMatch.Collection, matcher.match(releases[3]))
        assertNull(matcher.match(release("wrong-year", "Шрек (2004) BDRip")))
        assertNull(matcher.match(release("outside-range", "Shrek (2004–2010) BDRip")))
        assertNull(matcher.match(release("yearless-sequel", "Shrek 2 BDRip")))
    }

    @Test fun recognizesMovieNamesThatContainYearsOrCollectionWords() {
        val film = Metadata("1917", "1917", null, null, year = 2019)
        assertEquals(MovieReleaseMatch.Exact, MovieReleaseMatcher(film).match(release("film", "1917.2019.1080p")))
        assertEquals(MovieReleaseMatch.Unconfirmed, MovieReleaseMatcher(film).match(release("film", "1917 BluRay")))
        val collection = Metadata("collection", "The Collection", null, null, year = 2012)
        assertEquals(MovieReleaseMatch.Exact, MovieReleaseMatcher(collection).match(release("film", "The Collection (2012) BDRip")))
    }

    @Test fun fallsBackAndKeepsBothPaginationQueriesAndTheirCursors() = runBlocking {
        val requests = mutableListOf<Triple<String, Int, String?>>()
        val fetch: suspend (String, Int, String?) -> SearchResult = { query, page, cursor ->
            requests.add(Triple(query, page, cursor))
            when (requests.size) {
                1 -> SearchResult(listOf(release("exact", "Шрек (2001) BluRay")), more = true, nextCursor = "focused")
                2 -> SearchResult(listOf(release("exact", "Шрек (2001) BluRay"), release("yearless", "Шрек 720p")), more = true, nextCursor = "broad")
                3 -> SearchResult(emptyList(), listOf("tracker: offline"))
                4 -> SearchResult(listOf(release("wrong", "Шрек 2 (2004) BluRay")))
                else -> SearchResult(listOf(release("new", "Shrek (2001) 1080p", 10)))
            }
        }
        val initial = MovieReleaseSearch.start(shrek, fetch)
        assertEquals(listOf("exact", "yearless"), initial.result.releases.map { it.id })
        assertEquals("broad", initial.result.nextCursor)
        val failed = initial.loadMore(fetch)
        assertEquals(initial.result.releases, failed.result.releases)
        assertEquals(listOf("tracker: offline"), failed.result.failures)
        val final = failed.loadMore(fetch).loadMore(fetch).result
        assertFalse(final.more)
        assertEquals(listOf("new", "exact", "yearless"), final.releases.map { it.id })
        assertEquals(listOf(
            Triple("Шрек 2001", 0, null), Triple("Шрек", 0, null), Triple("Шрек", 1, "broad"),
            Triple("Шрек", 1, "broad"), Triple("Шрек 2001", 1, "focused"),
        ), requests)
    }

    @Test fun usesOnlyTheDatedQueryWhenItHasEnoughExactReleases() = runBlocking {
        val result = MovieReleaseSearch.start(shrek) { query, _, _ ->
            assertEquals("Шрек 2001", query)
            SearchResult((1..3).map { release(it.toString(), "Шрек (2001) BDRip", it) })
        }
        assertEquals(listOf("3", "2", "1"), result.result.releases.map { it.id })
    }

    @Test fun keepsTitleOnlyResultsAndPaginationForMetadataWithoutAYear() = runBlocking {
        val film = Metadata("fixture", "Paging fixture", null, null)
        val result = MovieReleaseSearch.start(film) { query, _, _ ->
            assertEquals(film.title, query)
            SearchResult(listOf(release("one", "Paging fixture 01 1080p")), more = true, nextCursor = "next")
        }.result
        assertEquals(listOf("one"), result.releases.map { it.id })
        assertEquals("next", result.nextCursor)
    }

    private fun release(id: String, title: String, seeds: Int = 0) = Release(id, title, "fixture", seeds = seeds)
}
