package dev.mjwatch.mobile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransportSecurityTest {
 @Test fun privateCaHostnameAndChangedSshKeyAreEnforced()=runBlocking(Dispatchers.IO) {
  Assume.assumeTrue("Explicit isolated fixture required",InstrumentationRegistry.getArguments().getString("isolatedManaged")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext;val secrets=SecretStore(context)
  val fixture=JSONObject(java.net.URL("http://127.0.0.1:18444/bootstrap").readText())
  val config=Config(connectionMode="managed",managedUrl=fixture.getString("url"))
  val key="ca:${endpoint(config.managedUrl).origin()}";val previous=secrets.get(key)
  try {
   secrets.put(key,"");Assert.assertTrue("Unknown private CA rejected",runCatching{ManagedApi(config,secrets).request("me")}.exceptionOrNull()?.message?.contains("TLS")==true)
   secrets.put(key,fixture.getString("ca"));val api=ManagedApi(config,secrets);api.saveTokens(fixture);Assert.assertEquals("admin",api.authorized("me").getString("role"))
   val wrong=config.copy(managedUrl="https://wrong.invalid:18443");secrets.put("ca:${endpoint(wrong.managedUrl).origin()}",fixture.getString("ca"))
   val client=managedClient(wrong,secrets).newBuilder().dns(object:okhttp3.Dns {override fun lookup(hostname:String)=listOf(java.net.InetAddress.getByName("127.0.0.1"))}).build()
   val result=runCatching{client.newCall(okhttp3.Request.Builder().url(wrong.managedUrl+"/api/v1/me").build()).execute().use{it.code}}
   Assert.assertTrue("CA trust never disables hostname verification",result.exceptionOrNull() is javax.net.ssl.SSLException)
   val targetJson=JSONObject(java.net.URL("http://127.0.0.1:18444/ssh-target").readText());val target=SshTarget(targetJson.getString("host"),targetJson.getInt("port"),password=targetJson.getString("password"));val sshKey="sshHostKey:${target.host}:${target.port}";val pin=secrets.get(sshKey)
   try {secrets.put(sshKey,"SHA256:intentionally-wrong-fixture-pin");val error=runCatching{OwnerSsh(context,secrets).identity(target)}.exceptionOrNull();Assert.assertTrue("Changed SSH host key blocks command",error is HostKeyRequired&&error.changed)}finally{secrets.put(sshKey,pin)}
  } finally {secrets.put(key,previous)}
 }
}
