package dev.mjwatch.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.core.content.FileProvider
import dev.mjwatch.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateRuntimeTest {
 @Test fun verifyCandidateSignaturePackageAndProviderIsolation() {
    val i=InstrumentationRegistry.getInstrumentation();val path=InstrumentationRegistry.getArguments().getString("candidate")
    Assume.assumeTrue("Signed upgrade fixtures not supplied",path!=null)
    val c=i.targetContext;val file=File(path!!)
    val raw=File(file.parentFile,"update.json").readText()
    val tag=JSONObject(raw).getString("releaseUrl").substringAfterLast('/')
    val update=parseUpdate(raw,c.packageName,tag)
    verifyUpdateBytes(file,update);verifyUpdatePackage(c,file,update)
    val wrong=File(file.parentFile,"wrong-signature.apk")
    Assert.assertTrue(runCatching {verifyUpdatePackage(c,wrong,update)}.exceptionOrNull()?.message?.contains("Подпись")==true)
    Assert.assertTrue(runCatching {verifyUpdatePackage(c,file,update.copy(packageName="dev.mjwatch.tv"))}.isFailure)
    Assert.assertTrue(runCatching {verifyUpdateBytes(file,update.copy(sha256="0".repeat(64)))}.isFailure)
    val provider=c.packageManager.resolveContentProvider("${c.packageName}.updates",0)!!
    Assert.assertFalse(provider.exported);Assert.assertTrue(provider.grantUriPermissions)
    Assert.assertTrue(runCatching {FileProvider.getUriForFile(c,"${c.packageName}.updates",File(c.filesDir,"credentials.xml"))}.isFailure)
    println("MJWatch upgrade proof: candidate package/version/hash/certificate verified; wrong certificate rejected")
 }
 @Test fun encryptedSecretsHistoryAndProgressSurviveUpgrade()=runBlocking {
    Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("retained")=="true")
    val c=InstrumentationRegistry.getInstrumentation().targetContext
    Assert.assertEquals("sentinel-only-for-test",SecretStore(c).get("acceptance-sentinel"))
    val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2).build()
    try {
        Assert.assertNotNull(db.dao().entry("demo:orbit"))
        Assert.assertTrue(db.dao().progress().first().any {it.releaseId=="demo:orbit"&&it.positionMs>0})
        println("MJWatch upgrade proof: encrypted sentinel, history and progress retained")
    } finally {db.close()}
 }
}
