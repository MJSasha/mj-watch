package dev.mjwatch.mobile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit isolated-VM integration test; never uses an address from user settings. */
@RunWith(AndroidJUnit4::class)
class ServerUpdateTest {
 @Test fun importedSignedVersionInstallsThroughAppSshWithoutApkUpdate()=runBlocking(Dispatchers.IO) {
  Assume.assumeTrue("Explicit isolated fixture required",InstrumentationRegistry.getArguments().getString("isolatedManaged")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val fixture=JSONObject(java.net.URL("http://127.0.0.1:18444/ssh-target").readText());val target=SshTarget(fixture.getString("host"),fixture.getInt("port"),password=fixture.getString("password"))
  val config=Settings(context).config.first();val secrets=SecretStore(context);val api=ManagedApi(config,secrets);val ssh=OwnerSsh(context,secrets)
  Assert.assertEquals(config.serverId,ssh.identity(target).getString("serverId"))
  val before=api.authorized("admin/status").getString("version");Assert.assertEquals("0.1.0",before)
  val bundles=ServerBundles(context);val archive=File(context.getExternalFilesDir(null),"candidate.zip")
  val manifest=archive.inputStream().use {bundles.import(it)};Assert.assertEquals("0.1.1",manifest.getString("version"))
  try {
   val result=ssh.update(target,"isolated-backup-"+UUID.randomUUID(),UUID.randomUUID().toString());Assert.assertTrue(result.getBoolean("verified"))
   Assert.assertEquals("0.1.1",api.authorized("admin/status").getString("version"));Assert.assertEquals(config.scope,Settings(context).config.first().scope)
  }finally{bundles.useBundled()}
 }
}
