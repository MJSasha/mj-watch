package dev.mjwatch.mobile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallServerTest {
 private lateinit var fixture:JSONObject
 @get:Rule(order=0)val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
  Assume.assumeTrue("Explicit isolated fixture required",InstrumentationRegistry.getArguments().getString("isolatedManaged")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  Settings(context).save(Config(theme="dark"));Settings(context).destination("settings")
  fixture=JSONObject(java.net.URL("http://127.0.0.1:18444/ssh-target").readText())
 }}
 @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
 @Test fun nativeOneButtonInstallsBothServicesAndConnects(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val ssh=OwnerSsh(context,SecretStore(context));val target=SshTarget(fixture.getString("host"),fixture.getInt("port"),password=fixture.getString("password"))
  runBlocking(Dispatchers.IO){try{ssh.status(target)}catch(e:HostKeyRequired){val keys=fixture.getJSONArray("fingerprints");Assert.assertTrue("Host key must match independently obtained VM fingerprints",(0 until keys.length()).any {keys.getString(it)==e.fingerprint});ssh.trust(target,e.fingerprint)}}
  lateinit var model:WatchModel;compose.runOnIdle{model=ViewModelProvider(compose.activity)[WatchModel::class.java];model.page("server-install")}
  compose.onNodeWithContentDescription("IP или домен сервера").performTextInput(target.host)
  compose.onNodeWithContentDescription("Пароль SSH").performTextInput(target.password)
  compose.onNodeWithText("Дополнительно · SSH-порт 22").performScrollTo().performClick()
  compose.onNodeWithContentDescription("SSH-порт").performScrollTo().performTextReplacement(target.port.toString())
  compose.onNodeWithText("Установить автоматически").performScrollTo().performClick()
  compose.waitUntil(240000){model.state.value.config.historyEnabled&&model.managed.state.value.me?.optString("role")=="admin"}
  Assert.assertEquals("No manual URL or credential entry after SSH","https://127.0.0.1:8443",model.state.value.config.managedUrl)
  runBlocking(Dispatchers.IO){val data=ManagedApi(model.state.value.config,model.secrets).authorized("admin/status");Assert.assertTrue(data.getBoolean("databaseHealthy"));Assert.assertTrue(data.getBoolean("torrserverHealthy"))}
  compose.onNodeWithText("Сервер готов. Приложение уже подключено.").assertExists()
 }
}
