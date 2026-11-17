package dev.mjwatch.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootstrapConnectionTest {
 @Test fun installationAuthenticatesBeforeScopeAndRetainsRefreshedCredentials()=runBlocking(Dispatchers.IO) {
  Assume.assumeTrue("Explicit isolated fixture required",InstrumentationRegistry.getArguments().getString("isolatedManaged")=="true")
  val i=InstrumentationRegistry.getInstrumentation()
  val app=i.targetContext.applicationContext as Application
  app.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  Settings(app).save(Config(demo=true))
  val store=ViewModelStore()
  lateinit var model:WatchModel
  i.runOnMainSync {model=WatchModel(app);store.put("bootstrap",model)}
  try {
   withTimeout(10000) {while(!model.state.value.config.demo)delay(20)}
   for(expired in listOf(false,true)) {
    val bootstrap=JSONObject(java.net.URL("http://127.0.0.1:18444/bootstrap").readText())
    val c=Config(connectionMode="managed",managedUrl=bootstrap.getString("url"))
    Assert.assertEquals("",c.scope)
    model.secrets.put("ca:${endpoint(c.managedUrl).origin()}",bootstrap.getString("ca"))
    if(expired)bootstrap.put("accessToken","expired-bootstrap-access")
    val api=ManagedApi(c,model.secrets);api.saveTokens(bootstrap)
    val me=api.authorized("me")
    Assert.assertEquals("admin",me.getString("role"))
    val status=api.authorized("admin/status")
    Assert.assertTrue(status.getBoolean("databaseHealthy"));Assert.assertTrue(status.getBoolean("torrserverHealthy"))
    api.authorized("admin/probe","POST")
    val verified=api.sessionTokens()
    if(expired) {
     Assert.assertNotEquals(bootstrap.getString("accessToken"),verified.getString("accessToken"))
     Assert.assertNotEquals(bootstrap.getString("refreshToken"),verified.getString("refreshToken"))
    }
    model.managed.connected(c,verified)
    val saved=Settings(app).config.first()
    Assert.assertTrue(saved.historyEnabled)
    val restored=ManagedApi(saved,SecretStore(app))
    Assert.assertEquals(me.getString("userId"),restored.authorized("me").getString("userId"))
    restored.refresh()
    Assert.assertEquals("admin",restored.authorized("me").getString("role"))
   }
  }finally {i.runOnMainSync {store.clear()}}
 }
}
