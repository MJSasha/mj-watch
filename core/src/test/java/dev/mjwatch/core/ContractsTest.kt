package dev.mjwatch.core

import kotlinx.coroutines.*
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
    @Test fun configExportHasNoSecrets() {
        val raw=encodeConfig(dev.mjwatch.core.Config(jackett="https://host",torrServer="https://server"))
        assertFalse(raw.contains("apikey",true)); assertFalse(raw.contains("Authorization",true));
        assertTrue(runCatching {encodeConfig(dev.mjwatch.core.Config(jackett="https://host/?apikey=secret"))}.isFailure); assertEquals(1,decodeConfig(raw).schemaVersion)
        assertTrue(runCatching {decodeConfig("{\"schemaVersion\":3}")}.isFailure)
    }
    @Test fun bencodeValidation() {
        validateTorrent("d4:infod4:name4:teste e".replace(" ","").toByteArray())
        listOf("<html>error</html>","d5:test", "d4:test99999999999999:xe").forEach {assertTrue(runCatching {validateTorrent(it.toByteArray())}.isFailure)}
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
