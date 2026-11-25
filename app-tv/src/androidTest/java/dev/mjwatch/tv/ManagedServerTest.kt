@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import androidx.room.Room
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ManagedServerTest {
 @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
  Assume.assumeTrue("Explicit isolated fixture required",InstrumentationRegistry.getArguments().getString("isolatedManaged")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  val response=JSONObject(java.net.URL("http://127.0.0.1:18444/bootstrap").readText())
  val config=Config(connectionMode="managed",managedUrl=response.getString("url"),serverId=response.getString("serverId"),userId=response.getString("userId"),jackett=response.getString("url")+"/fixture-source",releaseApi="jacred",externalReleaseSearch=true,theme="dark")
  val secrets=SecretStore(context);secrets.put("ca:${endpoint(config.managedUrl).origin()}",response.getString("ca"));val api=ManagedApi(config,secrets);api.saveTokens(response)
  val db=Room.databaseBuilder(context,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
  try {api.authorized("history","DELETE");db.syncDao().clearOutbox(config.scope);db.syncDao().clearEntities(config.scope);db.syncDao().clearCursor(config.scope);SyncEngine(api,db.syncDao()).sync()}finally{db.close()}
  Settings(context).save(config);Settings(context).destination("settings")
 }}
 @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
 private fun screenshot(name:String){compose.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"managed-proof").apply{mkdirs()};Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))}
 @Test fun managedFirstFrameRangeProgressAndExistingSettings(){
  lateinit var model:WatchModel;compose.runOnIdle{model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
  compose.waitUntil(15000){model.managed.state.value.me?.optString("role")=="admin"}
  screenshot("settings-admin")
  for(page in listOf("server-overview","server-users","server-tmdb","managed-account")) {
   compose.runOnIdle{model.page(page)};compose.waitUntil(15000){!model.managed.state.value.busy};Thread.sleep(300);screenshot(page)
   UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
  }
  compose.runOnIdle{model.open(Release("fixture:managed:tv","Managed fixture","fixture",magnet="magnet:?xt=urn:btih:0123456789012345678901234567890123456789"));model.prepare()}
  compose.waitUntil(20000){model.state.value.page=="files"||(!model.state.value.busy&&model.state.value.message.isNotBlank())};compose.waitForIdle()
  Assert.assertEquals("Gateway prepared: "+model.state.value.message,"files",model.state.value.page)
  val instrumentation=InstrumentationRegistry.getInstrumentation();var player:PlaybackActivity?=null
  val deadline=System.currentTimeMillis()+45000
  while(System.currentTimeMillis()<deadline){instrumentation.runOnMainSync{player=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull {it.firstFrameSeen}};if(player!=null)break;Thread.sleep(200)}
  Assert.assertNotNull("Actual Media3 first frame over authenticated HTTPS gateway",player)
  instrumentation.runOnMainSync{val f=PlaybackActivity::class.java.getDeclaredField("player").apply{isAccessible=true};val video=f.get(player) as androidx.media3.common.Player;Assert.assertTrue(video.duration>0);video.seekTo(2000);video.pause()}
  Thread.sleep(1500);screenshot("managed-first-frame");instrumentation.runOnMainSync{player!!.finish()};Thread.sleep(1800)
  val context=instrumentation.targetContext
  runBlocking {val config=Settings(context).config.first();val db=Room.databaseBuilder(context,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build();try {val engine=SyncEngine(ManagedApi(config,SecretStore(context)),db.syncDao());withTimeout(25000){do{engine.sync();if(db.syncDao().pending(config.scope).any {it.conflict.isNotBlank()})error("Unexpected isolated-fixture conflict");if(db.syncDao().pending(config.scope).isEmpty())break;delay(500)}while(true)};val snapshot=ManagedApi(config,SecretStore(context)).authorized("snapshot?fresh=1");val entities=snapshot.getJSONArray("entities");Assert.assertTrue("Progress reached actual SQLite server",(0 until entities.length()).any {val e=entities.getJSONObject(it);e.getString("kind")=="progress"&&e.getJSONObject("value").optString("releaseId")=="fixture:managed:tv"&&e.getJSONObject("value").getLong("positionMs")>=1900})}finally{db.close()}}
  compose.runOnIdle{model.page("settings")};screenshot("settings-after-playback")
 }
}

@RunWith(AndroidJUnit4::class)
class DirectPrivacyTest {
 @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
  Settings(context).save(Config(demo=true,connectionMode="direct"));Settings(context).destination("home")
 }}
 @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
 @Test fun directOpeningAndPlayerNeverWriteHistory(){
  val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
  val db=Room.databaseBuilder(context,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
  val before=runBlocking{db.dao().library().first().map {it.id to it.openedAt} to db.dao().progress().first()}
  lateinit var model:WatchModel;compose.runOnIdle{model=ViewModelProvider(compose.activity)[WatchModel::class.java];model.metadata(Metadata("tmdb:movie:privacy","Privacy fixture",null,null));model.open(Release("fixture:privacy","Privacy fixture","fixture"))}
  instrumentation.runOnMainSync{context.startActivity(android.content.Intent(context,PlaybackActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("uri","asset:///demo.mp4").putExtra("releaseId","fixture:privacy").putExtra("path","privacy.mp4").putExtra("fileId",17))}
  var active:PlaybackActivity?=null;val deadline=System.currentTimeMillis()+20000
  while(System.currentTimeMillis()<deadline){instrumentation.runOnMainSync{active=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull{it.firstFrameSeen}};if(active!=null)break;Thread.sleep(200)}
  Assert.assertNotNull("Direct demo first frame",active);instrumentation.runOnMainSync{active!!.finish()};Thread.sleep(1200)
  val after=runBlocking{db.dao().library().first().map {it.id to it.openedAt} to db.dao().progress().first()};Assert.assertEquals("No opening or progress writes",before,after)
  runBlocking {Assert.assertNull("No hidden metadata opening timestamp",db.dao().metadata("direct:tmdb:movie:privacy"))}
  Assert.assertEquals("No release URL persisted for direct opening","",SecretStore(context).get("release:fixture:privacy"));db.close()
  compose.runOnIdle{Assert.assertTrue(model.progress.value.isEmpty());Assert.assertTrue(model.library.value.all {it.openedAt==0L})}
 }
}
