package dev.mjwatch.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

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
    private fun client(handler:(Request)->Response):OkHttpClient=OkHttpClient.Builder().addInterceptor {handler(it.request())}.build()
    private fun response(request:Request,body:String,code:Int=200,location:String?=null)=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).apply {location?.let {header("Location",it)}}.build()
    @Test fun githubReleaseSelectionRedirectsAndCredentialIsolation()=runBlocking {
        val paths=mutableListOf<String>()
        val source=GitHubUpdates(client {request->
            paths+=request.url.host
            assertNull(request.header("Authorization"));assertNull(request.header("Cookie"));assertEquals("MJWatch-Updater/1",request.header("User-Agent"))
            when(request.url.host) {
                "api.github.com" -> response(request,"""[{"tag_name":"unrelated","draft":false},{"tag_name":"test-41","draft":false},{"tag_name":"test-42","draft":false}]""")
                "github.com" -> response(request,"",302,"https://release-assets.githubusercontent.com/fixture?signature=public-fixture")
                else -> response(request,manifest())
            }
        })
        assertEquals(1042,source.latest("dev.mjwatch.mobile")!!.versionCode)
        assertEquals(listOf("api.github.com","github.com","release-assets.githubusercontent.com"),paths)
    }
    @Test fun redirectDowngradeAndOtherHostsAreRejectedBeforeRequest()=runBlocking {
        for(location in listOf("http://github.com/unsafe","https://evil.invalid/unsafe","https://user:secret@github.com/unsafe")) {
            var requests=0
            val source=GitHubUpdates(client {request->requests++;if(request.url.host=="api.github.com") response(request,"""[{"tag_name":"test-42"}]""") else response(request,"",302,location)})
            assertTrue(runCatching {source.latest("dev.mjwatch.mobile")}.isFailure);assertEquals(2,requests)
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
}
