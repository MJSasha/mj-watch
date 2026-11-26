package dev.mjwatch.core

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import okio.buffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class UpdatesTest {
    private fun manifest(bytes:ByteArray="apk".toByteArray()):String {
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        return """{"schemaVersion":1,"channel":"testing","versionCode":1042,"versionName":"0.3.0-test.42","releaseUrl":"https://github.com/MJSasha/mj-watch/releases/tag/test-42","packages":{"mobile":{"packageName":"dev.mjwatch.mobile","url":"https://github.com/MJSasha/mj-watch/releases/download/test-42/MJWatch-mobile.apk","size":${bytes.size},"sha256":"$hash"},"tv":{"packageName":"dev.mjwatch.tv","url":"https://github.com/MJSasha/mj-watch/releases/download/test-42/MJWatch-tv.apk","size":${bytes.size},"sha256":"$hash"}}}"""
    }
    @Test fun descriptorBindsChannelPlatformVersionAndRepository() {
        assertEquals("dev.mjwatch.tv",parseUpdate(manifest(),"dev.mjwatch.tv","test-42").packageName)
        val update=parseUpdate(manifest(),"dev.mjwatch.mobile","test-42")
        assertEquals(1042,update.versionCode);assertTrue(update.apkUrl.endsWith("MJWatch-mobile.apk"))
        for(raw in listOf(manifest().replace("\"testing\"","\"production\""),manifest().replace("github.com/MJSasha/mj-watch/releases/download","evil.invalid/releases/download"),manifest().replace("\"size\":3","\"size\":999999999"),manifest().replace("\"versionCode\":1042","\"versionCode\":0"))) {
            assertTrue(runCatching {parseUpdate(raw,"dev.mjwatch.mobile","test-42")}.isFailure)
        }
        assertTrue(runCatching {parseUpdate(manifest(),"dev.mjwatch.mobile","test-43")}.isFailure)
    }
    @Test fun downloadBytesRejectTamperingAndTruncation() {
        val file=File.createTempFile("mj-update",".apk")
        val update=parseUpdate(manifest(),"dev.mjwatch.mobile","test-42")
        try {
            file.writeText("apk");verifyUpdateBytes(file,update)
            file.writeText("bad");assertTrue(runCatching {verifyUpdateBytes(file,update)}.isFailure)
            file.writeText("a");assertTrue(runCatching {verifyUpdateBytes(file,update)}.isFailure)
        } finally {file.delete()}
    }
    @Test fun cachedApkMatchesOnlyItsReleaseAndSize() {
        val context = RuntimeEnvironment.getApplication()
        val update = parseUpdate(manifest(), "dev.mjwatch.mobile", "test-42")
        val file = updateFile(context, update)
        file.parentFile!!.mkdirs()
        try {
            file.writeText("a")
            assertNull(cachedUpdateFile(context, update))
            file.writeText("apk")
            assertEquals(file, cachedUpdateFile(context, update))
            assertNull(cachedUpdateFile(context, update.copy(versionCode = update.versionCode + 1)))
            assertNull(cachedUpdateFile(context, update.copy(sha256 = "0".repeat(64))))
        } finally {
            file.delete()
        }
    }
    private fun client(handler:(Request)->Response):OkHttpClient=OkHttpClient.Builder().addInterceptor {handler(it.request())}.build()
    private fun response(request:Request,body:String,code:Int=200,location:String?=null)=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).apply {location?.let {header("Location",it)}}.build()
    @Test fun latestReleaseAssetRedirectsWithoutApiOrCredentialsForBothPlatforms()=runBlocking {
        val latest="https://github.com/MJSasha/mj-watch/releases/latest/download/update.json"
        val immutable="https://github.com/MJSasha/mj-watch/releases/download/test-42/update.json"
        val cdn="https://release-assets.githubusercontent.com/fixture?signature=public-fixture"
        for(platform in listOf("mobile","tv")) {
            val requests=mutableListOf<String>()
            val source=GitHubUpdates(client {request->
                requests+=request.url.toString()
                assertNotEquals("api.github.com",request.url.host)
                assertNull(request.header("Authorization"));assertNull(request.header("Cookie"));assertEquals("MJWatch-Updater/1",request.header("User-Agent"))
                when(request.url.toString()) {
                    latest -> response(request,"",302,immutable)
                    immutable -> response(request,"",302,cdn)
                    cdn -> response(request,manifest())
                    else -> error("Unexpected updater request: ${request.url}")
                }
            })
            val update=source.latest("dev.mjwatch.$platform")!!
            assertEquals(1042L,update.versionCode)
            assertEquals("0.3.0-test.42",update.versionName)
            assertEquals(3L,update.size)
            assertEquals(JSONObject(manifest()).getJSONObject("packages").getJSONObject(platform).getString("sha256"),update.sha256)
            assertEquals("dev.mjwatch.$platform",update.packageName)
            assertEquals("https://github.com/MJSasha/mj-watch/releases/download/test-42/MJWatch-$platform.apk",update.apkUrl)
            assertEquals("https://github.com/MJSasha/mj-watch/releases/tag/test-42",update.releaseUrl)
            assertEquals(listOf(latest,immutable,cdn),requests)
        }
    }
    @Test fun latestDescriptorStillBindsRepositoryTagAndPlatform()=runBlocking {
        val malicious=listOf(
            JSONObject(manifest()).put("releaseUrl","https://github.com/attacker/mj-watch/releases/tag/test-42"),
            JSONObject(manifest()).put("releaseUrl","https://github.com/MJSasha/mj-watch/releases/tag/other-42"),
            JSONObject(manifest()).apply {getJSONObject("packages").getJSONObject("mobile").put("url","https://github.com/MJSasha/mj-watch/releases/download/test-43/MJWatch-mobile.apk")},
            JSONObject(manifest()).apply {getJSONObject("packages").getJSONObject("mobile").put("packageName","dev.mjwatch.tv")},
            JSONObject(manifest()).apply {getJSONObject("packages").getJSONObject("mobile").put("sha256","not-a-sha256")}
        )
        for(descriptor in malicious) {
            var requests=0
            val source=GitHubUpdates(client {request->
                requests++
                assertEquals("https://github.com/MJSasha/mj-watch/releases/latest/download/update.json",request.url.toString())
                response(request,descriptor.toString())
            })
            assertTrue(runCatching {source.latest("dev.mjwatch.mobile")}.isFailure)
            assertEquals(1,requests)
        }
    }
    @Test fun redirectDowngradeAndOtherHostsAreRejectedBeforeRequest()=runBlocking {
        for(location in listOf("http://github.com/unsafe","https://evil.invalid/unsafe","https://user:secret@github.com/unsafe","https://api.github.com/repos/MJSasha/mj-watch/releases")) {
            var requests=0
            val source=GitHubUpdates(client {request->requests++;response(request,"",302,location)})
            assertTrue(runCatching {source.latest("dev.mjwatch.mobile")}.isFailure);assertEquals(1,requests)
        }
    }
    @Test fun streamedDownloadChecksHashAndSize()=runBlocking {
        val source=GitHubUpdates(client {request->response(request,"apk")})
        val file=File.createTempFile("mj-download",".apk")
        try {
            var progress=0L
            val update=parseUpdate(manifest(),"dev.mjwatch.mobile","test-42")
            source.download(update,file) {progress=it};assertEquals(3L,progress)
            assertTrue(runCatching {source.download(update.copy(sha256="0".repeat(64)),file) {}}.isFailure)
            assertTrue(runCatching {source.download(update.copy(size=2),file) {}}.isFailure)
        } finally {file.delete()}
    }
    @Test fun cancelledStreamClosesAndStopsWriting()=runBlocking {
        val closed=java.util.concurrent.atomic.AtomicBoolean(false)
        val body=object:ResponseBody() {
            private val stream=object:okio.Source {
                override fun read(sink:okio.Buffer,byteCount:Long):Long {Thread.sleep(10);val n=minOf(byteCount,32768).toInt();sink.write(ByteArray(n));return n.toLong()}
                override fun timeout()=okio.Timeout.NONE
                override fun close() {closed.set(true)}
            }.buffer()
            override fun contentType()="application/octet-stream".toMediaType()
            override fun contentLength()=-1L
            override fun source()=stream
        }
        val source=GitHubUpdates(client {request->Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()})
        val file=File.createTempFile("mj-cancel",".apk")
        try {
            val update=parseUpdate(manifest(),"dev.mjwatch.mobile","test-42").copy(size=5*1024*1024)
            val job=launch {source.download(update,file) {}}
            delay(40);job.cancelAndJoin()
            withTimeout(2000) {while(!closed.get()) delay(10)}
            val length=file.length();delay(40);assertEquals(length,file.length());assertTrue(length<update.size)
        } finally {file.delete()}
    }
}
