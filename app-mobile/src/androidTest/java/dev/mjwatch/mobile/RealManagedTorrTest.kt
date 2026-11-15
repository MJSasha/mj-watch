package dev.mjwatch.mobile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RealManagedTorrTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun installedTorrServerSshSettingsAndActualManagedVideo(){
  Assume.assumeTrue("Explicit isolated fixture required",InstrumentationRegistry.getArguments().getString("isolatedManaged")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val fixture=JSONObject(java.net.URL("http://127.0.0.1:18444/ssh-target").readText())
  runBlocking {val config=Settings(context).config.first();Settings(context).save(config.copy(jackett="http://127.0.0.1:18444",localHttp=true))}
  val target=SshTarget(fixture.getString("host"),fixture.getInt("port"),password=fixture.getString("password"));val ssh=OwnerSsh(context,SecretStore(context))
  runBlocking(Dispatchers.IO){
   val read=ssh.read(target);Assert.assertEquals("MatriX.145.2",read.getString("version"))
   val changes=JSONObject().put("PreloadCache",25).put("DisableDHT",true).put("DisablePEX",true)
   val result=ssh.apply(target,JSONObject().put("operationId",UUID.randomUUID().toString()).put("revision",read.getString("revision")).put("changes",changes).put("confirmed",true))
   Assert.assertEquals(25,result.getJSONObject("settings").getInt("PreloadCache"));ssh.restart(target)
   val after=withTimeout(30000){while(true){val read=runCatching{ssh.read(target)}.getOrNull();if(read!=null)return@withTimeout read;delay(500)};error("unreachable")};Assert.assertEquals("Persisted after service restart",25,after.getJSONObject("settings").getInt("PreloadCache"))
  }
  lateinit var model:WatchModel;compose.runOnIdle{model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
  compose.waitUntil(15000){model.state.value.config.historyEnabled};compose.runOnIdle{Assert.assertEquals("http://127.0.0.1:18444",model.state.value.config.jackett)}
  compose.runOnIdle{model.open(Release("fixture:real-torr-upload","Actual local torrent","isolated VM",download="http://127.0.0.1:18444/torrent"));model.prepare()}
  compose.waitUntil(70000){model.state.value.page=="files"||(!model.state.value.busy&&model.state.value.message.isNotBlank())};compose.waitForIdle()
  Assert.assertEquals("Actual TorrServer metadata: "+model.state.value.message,"files",model.state.value.page)
  val instrumentation=InstrumentationRegistry.getInstrumentation();var active:PlaybackActivity?=null;val deadline=System.currentTimeMillis()+45000
  while(System.currentTimeMillis()<deadline){instrumentation.runOnMainSync{active=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull{it.firstFrameSeen}};if(active!=null)break;Thread.sleep(200)}
  Assert.assertNotNull("Media3 first frame from installed TorrServer through Go HTTPS gateway",active)
  instrumentation.runOnMainSync{val field=PlaybackActivity::class.java.getDeclaredField("player").apply{isAccessible=true};val player=field.get(active) as androidx.media3.common.Player;Assert.assertTrue(player.duration>0);player.seekTo(2000);player.pause()};Thread.sleep(1200)
  instrumentation.runOnMainSync{active!!.finish()};Thread.sleep(1500)
  runBlocking(Dispatchers.IO){val config=Settings(context).config.first();val snapshot=ManagedApi(config,SecretStore(context)).authorized("snapshot");val rows=snapshot.getJSONArray("entities");Assert.assertTrue("Real managed progress on installed SQLite",(0 until rows.length()).any {val e=rows.getJSONObject(it);e.getString("kind")=="progress"&&e.getJSONObject("value").optString("releaseId")=="fixture:real-torr-upload"})}
 }
}
