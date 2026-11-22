package dev.mjwatch.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class ContractsTest {
    private fun fixture(name:String)=javaClass.classLoader!!.getResource(name)!!.readText()
    @Test fun jacredJsonContractAndConfigRoundTrip() {
        val items=JacredJson.releases(fixture("jacred.json"),"all")
        assertEquals(1,items.size);val r=items.single()
        assertEquals("Sintel • fixture 720p",r.title);assertEquals("Fixture",r.source)
        assertEquals(0,r.seeds);assertEquals(129241752L,r.size)
        assertEquals("08ada5a7a6183aae1e09d831df6748d566095a10",r.hash)
        assertNull(r.download);assertFalse(r.id.contains("magnet"))
        assertEquals(items,JacredJson.releases(fixture("jacred.json").replace("Results","results"),"all"))
        assertTrue(runCatching {JacredJson.releases("<html>denied</html>","all")}.isFailure)
        assertTrue(runCatching {JacredJson.releases("{}","all")}.isFailure)
        assertTrue(dev.mjwatch.core.Config(jackett="https://jac.red").usesJacred())
        assertFalse(dev.mjwatch.core.Config(jackett="https://jac.red",releaseApi="torznab").usesJacred())
        val c=dev.mjwatch.core.Config(jackett="https://custom.invalid/proxy",releaseApi="jacred")
        assertEquals(c,decodeConfig(encodeConfig(c)))
    }
    @Test fun rateLimitRetriesLookupOnceAndRespectsLongCooldown()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val config=dev.mjwatch.core.Config(jackett=server.url("/").toString(),localHttp=true,releaseApi="jacred")
            val repo=TorznabRepository(config,"",guardedClient(config))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","0"))
            server.enqueue(MockResponse().setBody(fixture("jacred.json")))
            assertEquals(1,repo.search("Sintel").releases.size);assertEquals(2,server.requestCount)
            repeat(2) {server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","0"))}
            assertTrue(repo.search("Sintel").failures.single().contains("HTTP 429"));assertEquals(4,server.requestCount)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","60"))
            assertTrue(repo.search("Sintel").failures.single().contains("60"));assertEquals(5,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun jacredEmptyCredentialsAndSourcesUseJsonWithoutCaps()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val c=dev.mjwatch.core.Config(jackett=server.url("/prefix").toString(),localHttp=true,releaseApi="jacred")
            val repo=TorznabRepository(c,"",guardedClient(c))
            server.enqueue(MockResponse().setBody("{\"Results\":[],\"jacred\":true}"));assertTrue(repo.check().contains("JacRed JSON"))
            server.enqueue(MockResponse().setBody(fixture("jacred.json")));assertEquals(1,repo.search("Sintel тест").releases.size)
            repeat(2) {index-> val request=server.takeRequest();val url=request.requestUrl!!
                assertEquals("/prefix/api/v2.0/indexers/all/results",url.encodedPath)
                assertEquals("MJWatch/0.2.1 (Android)",request.getHeader("User-Agent"))
                assertNull(url.queryParameter("apikey"));assertNull(url.queryParameter("t"))
                assertEquals(if(index==0) "" else "Sintel тест",url.queryParameter("Query"))
            }
            server.enqueue(MockResponse().setResponseCode(403))
            val failure=runCatching {repo.check()}.exceptionOrNull()!!
            assertTrue(safeError(failure).contains("HTTP 403"));assertEquals(3,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun localHttpPolicyReasonSurvivesAsyncFailureAndHtmlIsRejected()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val c=dev.mjwatch.core.Config(torrServer=server.url("/").toString())
            val client=guardedClient(c)
            val failure=runCatching {client.bytes(Request.Builder().url(server.url("/echo")).build())}.exceptionOrNull()!!
            assertTrue(failure is NetworkPolicyFailure);assertTrue(safeError(failure).contains("Разрешить HTTP"));assertEquals(0,server.requestCount)
            assertTrue(runCatching {TorznabXml.caps("<html><body>Login</body></html>")}.isFailure)
            assertTrue(runCatching {TorznabXml.releases("<html/>","all")}.isFailure)
            val cfg=c.copy(jackett=server.url("/").toString(),localHttp=true)
            server.enqueue(MockResponse().setBody("<caps/>"))
            TorznabRepository(cfg,"",guardedClient(cfg)).check()
            assertEquals("/api/v2.0/indexers/all/results/torznab/api",server.takeRequest().requestUrl!!.encodedPath)
        } finally {server.shutdown()}
    }
    @Test fun xmlUnicodeOptionalAndDedupe() {
        val r=TorznabXml.releases(fixture("torznab.xml"),"fixture")
        assertEquals(2,r.size); assertEquals("Тест & Film 1080p",r[0].title); assertEquals(0,r[0].seeds); assertNull(r[1].seeds)
        assertNull(r[0].size); assertTrue(r[0].download!!.contains("&b=2")); assertEquals("abc",r[0].hash)
    }
    @Test fun xmlErrorDtdMalformedAndLimits() {
        for(xml in listOf("<error code='100' description='Invalid API Key'/>","<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>","<rss><item><title>broken</rss>")) {
            assertTrue(runCatching {TorznabXml.releases(xml,"fixture")}.isFailure)
        }
        assertTrue(runCatching {TorznabXml.releases("<rss>"+(1..501).joinToString(""){ "<item><title>$it</title></item>" }+"</rss>","fixture")}.isFailure)
        assertFalse(TorznabXml.caps("<caps><limits max='200'/><categories><category name='Other'/></categories></caps>").offset)
    }
    @Test fun serverFileIdsAndUrlEncoding() {
        val c=dev.mjwatch.core.Config(torrServer="https://host.invalid/proxy/ts")
        val client=guardedClient(c); val g=TorrServerGateway(c,"",client,TorznabRepository(c,"",client))
        val h=TorrServerGateway.parse(fixture("torrserver-files.json"))
        val file=h.files.sortedBy { it.path }.first {it.serverFileId==17}
        val u=g.stream(h,file).uri.toHttpUrl()
        assertEquals("17",u.queryParameter("index")); assertEquals("02 #100% ?.mp4",u.pathSegments.last())
        assertTrue(u.encodedPath.startsWith("/proxy/ts/stream/")); assertNull(u.queryParameter("save"))
        assertEquals(h,TorrServerGateway.parse("[${fixture("torrserver-files.json")}]"))
        assertTrue(h.files.first {it.serverFileId==9}.sample)
    }
    @Test fun recorded145Upload() {
        val h=TorrServerGateway.parse(fixture("torrserver-145-upload.json"))
        assertEquals(6,h.files.single {it.video}.serverFileId)
        assertEquals("08ada5a7a6183aae1e09d831df6748d566095a10",h.hash)
    }
    @Test fun baseUrlValidation() {
        listOf("file:///tmp/a","http://user:password@host","https://host/?apikey=secret","https://host/#fragment","javascript:alert(1)").forEach { assertTrue(runCatching {endpoint(it)}.isFailure) }
        assertEquals("/proxy/torrents",endpoint("https://host/proxy").child("torrents").encodedPath)
    }
    @Test fun openingDoesNotOverwriteBookmarkOrProgress()=runBlocking {
        val db=androidx.room.Room.inMemoryDatabaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(),WatchDatabase::class.java).build()
        try {
            val dao=db.dao();val e=LibraryEntry("test","Тест","fixture",openedAt=1)
            dao.opened(e);dao.toggleBookmark(e)
            dao.put(Progress("key","test",17,"file.mp4",12345,90000,5,false))
            dao.opened(e.copy(openedAt=100))
            assertTrue(dao.entry("test")!!.bookmarked);assertEquals(100L,dao.entry("test")!!.openedAt)
            assertEquals(12345L,dao.progress().first().single().positionMs)
        } finally {db.close()}
    }
    @Test fun historyRemovalDeletesAllPositionsAndCanBeUndone()=runBlocking {
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        val config=dev.mjwatch.core.Config(connectionMode="managed",managedUrl="https://server.example",
            serverId="server",userId="viewer")
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,WatchDatabase::class.java).build()
        try {
            val dao=db.syncDao()
            val scope=config.scope
            val engine=SyncEngine(ManagedApi(config,SecretStore(context)),dao)
            val opening=LibraryEntry("release","Film","fixture",openedAt=1).syncJson().toString()
            val progress=Progress("episode-1","release",1,"episode-1.mkv",12345,90000,5,false)
            val positions=listOf(progress,progress.copy(key="episode-2",fileId=2),progress.copy(key="other",releaseId="other"))
            dao.put(SyncCursor(scope,"epoch",1))
            dao.put(SyncEntity(scope,"opening","release",opening,2))
            dao.put(SyncEntity(scope,"favorite","release",opening,3))
            positions.forEach {dao.put(SyncEntity(scope,"progress",it.key,it.syncJson().toString(),4))}

            val removed=engine.removeHistory(setOf("release"))
            assertEquals(setOf("release","episode-1","episode-2"),removed.map {it.entityKey}.toSet())
            assertNull(dao.resumeProgress(scope,"release","episode-1"))
            assertNull(dao.resumeProgress(scope,"release","episode-2"))
            assertEquals(12345L,dao.resumeProgress(scope,"other","other")!!.positionMs)
            assertFalse(dao.entity(scope,"favorite","release")!!.deleted)
            assertTrue(dao.pending(scope).all {it.operation=="delete"&&it.json=="{}"})

            engine.restoreHistory(removed)
            assertEquals(12345L,dao.resumeProgress(scope,"release","episode-1")!!.positionMs)
            assertTrue(dao.pending(scope).takeLast(3).all {it.operation=="restore"})

            // An older client deleted only the opening. Reopening must not bring back its positions.
            dao.put(SyncEntity(scope,"opening","release","{}",3,deleted=true))
            assertNull(dao.resumeProgress(scope,"release","episode-1"))
            engine.enqueue("opening","release",org.json.JSONObject(opening))
            assertFalse(dao.entity(scope,"opening","release")!!.deleted)
            assertNull(dao.resumeProgress(scope,"release","episode-1"))
        } finally {db.close()}
    }
    @Test fun handoffConfigSchemaAndPaths() {
        val c=decodeConfig(fixture("config.example.json"))
        assertEquals("https://jackett.example.invalid",c.jackett)
        assertEquals("/api/v2.0/indexers/example-indexer/results/torznab/api",c.torznabPaths["example-indexer"])
        val exported=org.json.JSONObject(encodeConfig(c));assertEquals(c.jackett,exported.getJSONObject("jackett").getString("baseUrl"))
        assertEquals("none",exported.getJSONObject("metadata").getString("provider"))
    }
    @Test fun metadataParserAndOfflineCache()=runBlocking {
        val items=TmdbAdapter.parse("""{"results":[{"id":999999999,"media_type":"movie","title":"Fixture","release_date":"2026-01-01","genre_ids":[16],"genres":[{"name":"Фантастика"}],"runtime":108,"original_language":"ja","overview":"Fixture description","backdrop_path":"/fixture.jpg"},{"id":7,"media_type":"person","name":"Ignored"}]}""")
        assertEquals(1,items.size);assertEquals(2026,items.single().year);assertTrue(items.single().animation);assertNull(items.single().poster)
        assertEquals("Фантастика", items.single().genre)
        assertEquals(108, items.single().runtimeMinutes)
        assertEquals(items.single(),metadataFromJson(items.single().json()))
        val legacy = metadataFromJson("""{"id":"old","title":"Old"}""")
        assertNull(legacy.genre)
        assertNull(legacy.runtimeMinutes)
        val db=androidx.room.Room.inMemoryDatabaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(),WatchDatabase::class.java).build()
        try {
            var calls=0;var offline=false
            val adapter=object:MetadataAdapter {override suspend fun search(query:String):List<Metadata> {calls++;if(offline) throw java.io.IOException("offline");return items}}
            val repo=MetadataRepository(adapter,db.dao());assertFalse(repo.search("fixture").cached)
            assertTrue(repo.search("fixture").cached);assertEquals(1,calls)
            val row=db.dao().metadata("query:search:fixture")!!;db.dao().put(row.copy(updatedAt=0));offline=true
            val cached=repo.search("fixture");assertTrue(cached.cached);assertEquals(items,cached.items);assertEquals(2,calls)
        } finally {db.close()}
    }
    @Test fun schemaMigrationPreservesHistoryAndProgress()=runBlocking {
        val context=org.robolectric.RuntimeEnvironment.getApplication();val name="migration-contract.db";context.deleteDatabase(name)
        val old=context.openOrCreateDatabase(name,0,null)
        old.execSQL("CREATE TABLE library (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, source TEXT NOT NULL, openedAt INTEGER NOT NULL, bookmarked INTEGER NOT NULL, releaseJson TEXT NOT NULL)")
        old.execSQL("CREATE TABLE progress (`key` TEXT NOT NULL PRIMARY KEY, releaseId TEXT NOT NULL, fileId INTEGER NOT NULL, path TEXT NOT NULL, positionMs INTEGER NOT NULL, durationMs INTEGER, updatedAt INTEGER NOT NULL, completed INTEGER NOT NULL)")
        old.execSQL("INSERT INTO library VALUES ('old','Old title','fixture',123,1,'')")
        old.execSQL("INSERT INTO progress VALUES ('old|file','old',17,'file',12345,90000,456,0)")
        old.version=1;old.close()
        val db=androidx.room.Room.databaseBuilder(context,WatchDatabase::class.java,name).addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {assertTrue(db.dao().entry("old")!!.bookmarked);assertEquals(12345L,db.dao().progress().first().single().positionMs);assertNull(db.dao().entry("old")!!.metadataId)
            db.dao().put(CachedMetadata("fixture","{}",1));assertNotNull(db.dao().metadata("fixture"))
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun configExportHasNoSecrets() {
        val raw=encodeConfig(dev.mjwatch.core.Config(jackett="https://host",torrServer="https://server"))
        assertFalse(raw.contains("apikey",true)); assertFalse(raw.contains("Authorization",true));
        assertTrue(runCatching {encodeConfig(dev.mjwatch.core.Config(jackett="https://host/?apikey=secret"))}.isFailure); assertEquals(1,decodeConfig(raw).schemaVersion)
        assertTrue(raw.contains("baseUrl"));assertTrue(runCatching {validateTorznabPath("//other-host/api")}.isFailure)
        assertTrue(runCatching {validateTorznabPath("/api?apikey=secret")}.isFailure)
        assertTrue(runCatching {decodeConfig("{\"schemaVersion\":3}")}.isFailure)
    }
    @Test fun magnetParameterOrder() {
        validateMagnet("magnet:?dn=Test&xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10")
        assertTrue(runCatching {validateMagnet("https://host/?xt=urn:btih:invalid")}.isFailure)
    }
    @Test fun bencodeValidation() {
        validateTorrent("d4:infod4:name4:teste e".replace(" ","").toByteArray())
        listOf("de","d4:infoi1ee","<html>error</html>","d5:test", "d4:test99999999999999:xe").forEach {assertTrue(runCatching {validateTorrent(it.toByteArray())}.isFailure)}
    }
    @Test fun redirectIsRejectedAndCredentialsNotForwarded()= runBlocking {
        val server=MockWebServer(); val other=MockWebServer(); server.start(); other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/stolen")))
            val c=dev.mjwatch.core.Config(torrServer=server.url("/").toString(),localHttp=true)
            assertTrue(runCatching {guardedClient(c).bytes(Request.Builder().url(server.url("/")).header("Authorization","Basic sentinel").build())}.isFailure)
            assertEquals(0,other.requestCount)
        } finally {server.shutdown();other.shutdown()}
    }
    @Test fun jackettMagnetDownloadRedirectIsResolvedWithoutFollowing()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val magnet="magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel"
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",magnet))
            val c=dev.mjwatch.core.Config(jackett=server.url("/").toString(),localHttp=true)
            val input=TorznabRepository(c,"sentinel",guardedClient(c)).resolve(Release("fixture","Sintel","fixture",download=server.url("/dl/fixture?jackett_apikey=sentinel").toString()))
            assertEquals(TorrentInput.Magnet(magnet),input);assertEquals(1,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun definitiveAuthFailureDoesNotRepeatAddOrProbe()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val c=dev.mjwatch.core.Config(torrServer=server.url("/").toString(),localHttp=true)
            val client=guardedClient(c);val gateway=TorrServerGateway(c,"",client,TorznabRepository(c,"",client))
            server.enqueue(MockResponse().setResponseCode(404))
            val missing=runCatching {gateway.status("08ada5a7a6183aae1e09d831df6748d566095a10")}.exceptionOrNull()
            assertEquals(404,(missing as HttpFailure).status)
            server.enqueue(MockResponse().setResponseCode(401));server.enqueue(MockResponse().setResponseCode(500))
            val denied=runCatching {gateway.add(Release("id","title","fixture",hash="08ada5a7a6183aae1e09d831df6748d566095a10",magnet="magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"))}.exceptionOrNull()
            assertEquals(401,(denied as HttpFailure).status);assertEquals(2,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun advertisedPaginationAndCapabilityCache()=runBlocking {
        val server=MockWebServer();server.start();var capRequests=0
        try {
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path!!.contains("t=caps")) {capRequests++;return MockResponse().setBody("<caps><limits max='2'/><searching><search available='yes' supportedParams='q,offset,limit'/></searching></caps>")}
                val ids=if(request.requestUrl!!.queryParameter("offset")=="2") listOf("b","c") else listOf("a","b")
                return MockResponse().setBody("<rss><channel>"+ids.joinToString(""){"<item><title>$it</title><guid>$it</guid></item>"}+"</channel></rss>")
            }}
            val c=dev.mjwatch.core.Config(jackett=server.url("/").toString(),indexers="fixture",localHttp=true)
            val repo=TorznabRepository(c,"",guardedClient(c));val first=repo.searchPage("test");val second=repo.searchPage("test",1)
            assertTrue(first.more);assertEquals(1,capRequests);assertEquals(3,(first.releases+second.releases).distinctBy {it.id}.size)
        } finally {server.shutdown()}
    }
    @Test fun partialSourcesAndCancellation()=runBlocking {
        val server=MockWebServer(); server.start()
        try {
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
                val path=request.path.orEmpty()
                return when { path.contains("t=caps") -> MockResponse().setBody("<caps><limits max='50'/></caps>")
                    path.contains("/bad/") -> MockResponse().setResponseCode(401)
                    else -> MockResponse().setBody(fixture("torznab.xml")) }
            } }
            val c=dev.mjwatch.core.Config(jackett=server.url("/").toString().trimEnd('/'),indexers="good,bad",localHttp=true)
            val r=TorznabRepository(c,"sentinel",guardedClient(c)).search("тест")
            assertEquals(2,r.releases.size);assertEquals(1,r.failures.size)
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() { override fun dispatch(request:RecordedRequest)=MockResponse().setBody("<caps/>").setBodyDelay(5,java.util.concurrent.TimeUnit.SECONDS) }
            val job=launch {TorznabRepository(c,"",guardedClient(c)).check()};delay(100);job.cancelAndJoin();assertTrue(job.isCancelled)
        } finally {server.shutdown()}
    }
}
