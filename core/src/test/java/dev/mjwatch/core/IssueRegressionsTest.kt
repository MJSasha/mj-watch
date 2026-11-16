package dev.mjwatch.core

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[35],manifest=org.robolectric.annotation.Config.NONE)
class IssueRegressionsTest {
    @Test fun releaseFiltersRecognizePacksVoicesAndQualityAliases() {
        val pack=Release("a","Сериал S01-S03 4K LostFilm, NewStudio MVO","fixture")
        assertEquals("2160p",pack.resolution)
        assertEquals(setOf(1,2,3),seasonNumbers(pack.title))
        assertEquals(setOf(1,2,3),seasonNumbers("Сезоны: 1-3 / Series"))
        assertEquals(setOf(2),seasonNumbers("2 сезон"))
        assertEquals(setOf(1,3),seasonNumbers("S01, S03"))
        assertEquals(setOf(2),seasonNumbers("Show 2x03"))
        assertTrue(ReleaseFilter("2160p",2,"LostFilm").matches(pack))
        assertFalse(ReleaseFilter("1080p",2,"LostFilm").matches(pack))
        assertFalse(ReleaseFilter(season=4).matches(pack))
        assertFalse(ReleaseFilter(voice="Дубляж").matches(pack))
        assertEquals(setOf("Дубляж","HDrezka"),releaseVoices("[DUB, HDRezka Studio]"))
        assertTrue(seasonNumbers("Film 2026 1920x1080").isEmpty())
        assertEquals("Не указано",Release("b","Film 2026","fixture").resolution)
        assertEquals("1080p",Release("c","Film 1080P","fixture").resolution)
        assertEquals("2160p",Release("d","Film UHD","fixture").resolution)
    }
    @Test fun episodesSortNumericallyAndKeepServerFileIds() {
        val files=listOf(TorrentFile(88,"Season 2/10.mkv",1),TorrentFile(17,"Season 2/2.mkv",1),
            TorrentFile(4,"Show.S01E09.mkv",1),TorrentFile(3,"Сезон 1/Серия 02.mp4",1))
        assertEquals(listOf(3,4,17,88),sortedVideoFiles(files).map {it.serverFileId})
        assertEquals(EpisodeNumber(1,2),episodeNumber(files.last().path))
        assertEquals(EpisodeNumber(2,3),episodeNumber("Show.2x03.mkv"))
        assertEquals(EpisodeNumber(null,null),episodeNumber("Movie.2026.mkv"))
        assertEquals(EpisodeNumber(2,10),episodeNumber("Show S01-S03/Season 2/10.mkv"))
        assertEquals(EpisodeNumber(1,10),episodeNumber("Show S01E01-E10/10.mkv"))
    }
    @Test fun historyGroupsReleasesUnderFilmAndKeepsLatestProgress() {
        val film=Metadata("tmdb:movie:7","Фильм","poster","Описание")
        val records=listOf(LibraryEntry(film.id,film.title,"TMDB",1,metadataId=film.id),
            LibraryEntry("a","Film 1080p LostFilm","tracker",2,metadataId=film.id),
            LibraryEntry("b","Film UHD","tracker",3,true,metadataId=film.id))
        val title=libraryTitles(records,mapOf(film.id to film)).single()
        assertEquals(film,title.metadata);assertEquals("Фильм",title.entry.title)
        assertEquals(3L,title.entry.openedAt);assertTrue(title.entry.bookmarked)
        assertEquals(setOf(film.id,"a","b"),title.releaseIds)
        assertEquals("b",title.progress(listOf(Progress("a","a",17,"a",100,null,1,false),
            Progress("b","b",88,"b",200,null,2,false)))?.releaseId)
        assertEquals(1,libraryTitles(records,emptyMap()).size)
        assertEquals("Фильм",libraryTitles(records,emptyMap()).single().entry.title)
        assertEquals(2,libraryTitles(records+LibraryEntry("unknown","Без каталога","tracker",4),emptyMap()).size)
    }
    @Test fun parserEditorPreservesManagedAccountAndHasUsableDefault() {
        val current=Config(connectionMode="managed",managedUrl="https://watch.example",serverId="server",userId="user",metadataProvider="tmdb")
        val saved=current.withSearchSettings(Config(jackett=" https://custom.example ",releaseApi="jacred"))
        assertEquals(current.scope,saved.scope);assertEquals(current.managedUrl,saved.managedUrl)
        assertEquals("tmdb",saved.metadataProvider);assertEquals("https://custom.example",saved.jackett)
        assertEquals(saved,decodeConfig(encodeConfig(saved)))
        assertEquals("https://jac.red",decodeConfig("{}").jackett)
        assertEquals("https://jac.red",decodeConfig("""{"jackett":{"baseUrl":""}}""").jackett)
    }
}
