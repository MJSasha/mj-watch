@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.mobile

import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import java.io.File

class PhoneTvTest {
    @get:Rule(order=0)val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking<Unit> {
        Assume.assumeTrue("Isolated three-emulator fixture only",InstrumentationRegistry.getArguments().getString("isolatedRemote")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val response=JSONObject(java.net.URL("http://127.0.0.1:18444/bootstrap").readText())
        val config=Config(connectionMode="managed",managedUrl=response.getString("url"),serverId=response.getString("serverId"),userId=response.getString("userId"),metadataProvider="none",theme="dark")
        val secrets=SecretStore(context);secrets.put("ca:${endpoint(config.managedUrl).origin()}",response.getString("ca"));val api=ManagedApi(config,secrets);api.saveTokens(response)
        api.authorized("devices/${response.getString("deviceId")}","PATCH",JSONObject().put("name","Телефон"))
        Settings(context).save(config);Settings(context).destination("home");Settings(context).handleLegacyHistory()
        context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
    }}
    @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private fun api()=runBlocking {ManagedApi(Settings(context).config.first(),SecretStore(context))}
    private fun snapshot(id:String,instance:String)=runBlocking(Dispatchers.IO){RemoteTarget.parse(api().authorized("remote/$id?instance=$instance"))}
    private fun waitFor(message:String,test:()->Boolean) {
        val until=System.currentTimeMillis()+25000
        while(System.currentTimeMillis()<until){if(test())return;Thread.sleep(200)}
        Assert.fail(message+"; remote="+RemotePlaybackService.state.value)
    }
    private fun proof(name:String) {
        compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(350)
        val dir=File(context.getExternalFilesDir(null),"phone-tv-proof").apply{mkdirs()}
        Assert.assertTrue(device.takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun realReceiverAndAndroidSystemSession() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(15000){!model.state.value.initializing&&model.managed.state.value.syncedAt>0}
        val tvs=runBlocking(Dispatchers.IO){withTimeout(25000){var rows:List<ManagedDevice>;do {rows=managedDevices(api().authorized("devices")).filter {it.type=="tv"&&it.available};if(rows.size<2)delay(500)}while(rows.size<2);rows}}
        val first=tvs.first {it.name=="Гостиная"};val second=tvs.first {it.name=="Спальня"}
        compose.runOnIdle {model.open(Release("demo:remote","Тихая орбита","fixture",magnet="magnet:?xt=urn:btih:0123456789012345678901234567890123456789"),metadata=Metadata("tmdb:movie:1","Тихая орбита",null,"История о встрече за пределами знакомого мира.",year=2026))}
        compose.onNodeWithTag("detail-cast").assertExists()
        compose.onNodeWithTag("watch-on-tv").assertDoesNotExist()
        compose.runOnIdle {model.prepare();Assert.assertEquals("detail",model.state.value.page);Assert.assertFalse(model.state.value.busy)}
        compose.onNodeWithContentDescription("В «Буду смотреть»").performClick()
        compose.waitUntil(10000){model.library.value.any {it.id=="tmdb:movie:1"&&it.bookmarked}}
        proof("01-no-connection")
        compose.onNodeWithTag("detail-cast").performClick()
        compose.onNodeWithText("Где смотреть?").assertExists()
        compose.waitUntil(10000){compose.onAllNodesWithTag("target-${first.id}").fetchSemanticsNodes().isNotEmpty()}
        device.pressBack()
        Assert.assertNull(RemotePlaybackService.state.value.target)
        compose.onNodeWithTag("detail-cast").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("target-${first.id}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("target-${first.id}").performClick()
        waitFor("Connection was acknowledged"){RemotePlaybackService.state.value.canLaunch}
        compose.onNodeWithTag("watch-on-tv").assertExists();proof("02-connected")
        compose.onNodeWithTag("watch-on-tv").performClick()
        compose.onNodeWithTag("source-continue").performClick()
        compose.waitUntil(25000){model.state.value.page=="files"||(!model.state.value.busy&&model.state.value.message.isNotBlank())}
        Assert.assertEquals("files",model.state.value.page)
        compose.onNodeWithTag("file-17").performClick()
        compose.onNodeWithTag("file-continue").performClick()
        waitFor("Receiver rendered the first frame"){RemotePlaybackService.state.value.target?.video?.status=="playing"}
        waitFor("Load completed"){!RemotePlaybackService.state.value.busy}
        Assert.assertFalse("Phone never opens its own video player",PlaybackActivity.hasActivePlayback())
        val target=RemotePlaybackService.state.value.target!!;val originalSession=target.video.session
        Assert.assertEquals(first.id,target.id);compose.onNodeWithTag("remote-status").assertExists();proof("03-playing-on-tv")
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        val sessions=context.getSystemService(MediaSessionManager::class.java)
        waitFor("Android registered its active media card"){sessions.getActiveSessions(null).any {it.packageName==context.packageName}}
        val controller=sessions.getActiveSessions(null).single {it.packageName==context.packageName}
        Assert.assertEquals(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE,controller.playbackInfo.playbackType)
        controller.transportControls.pause()
        waitFor("System pause reached the actual TV"){snapshot(first.id,target.instance).video.status=="paused"}
        controller.transportControls.seekTo(2000)
        waitFor("System seek reached the actual TV"){snapshot(first.id,target.instance).video.position in 1800..2500}
        val oldVolume=snapshot(first.id,target.instance).video.volume
        val newVolume=if(oldVolume>0)oldVolume-1 else 1
        controller.setVolumeTo(newVolume,0)
        waitFor("Volume changes on the TV"){snapshot(first.id,target.instance).video.volume==newVolume}
        controller.transportControls.play()
        waitFor("System play reached the actual TV"){snapshot(first.id,target.instance).video.status=="playing"}
        waitFor("Android card reflects playing state"){controller.playbackState?.state==PlaybackState.STATE_PLAYING}
        device.openNotification();Thread.sleep(500);proof("04-android-media-controls");device.pressBack()
        java.net.URL("http://127.0.0.1:18445/tv-pause").readText()
        waitFor("TV remote pause reflected on the phone"){RemotePlaybackService.state.value.target?.video?.status=="paused"}
        java.net.URL("http://127.0.0.1:18445/tv-play").readText()
        waitFor("TV remote play reflected on the phone"){RemotePlaybackService.state.value.target?.video?.status=="playing"}
        java.net.URL("http://127.0.0.1:18445/cut").readText()
        // Removing an adb reverse listener leaves existing keep-alive sockets alive.
        // Drop only this controller's sockets, as a real network loss would do.
        lateinit var transport:okhttp3.OkHttpClient
        compose.runOnIdle {
            val service=RemotePlaybackService::class.java.getDeclaredField("current").apply {isAccessible=true}.get(null)
            transport=(RemotePlaybackService::class.java.getDeclaredField("api").apply {isAccessible=true}.get(service) as ManagedApi).client
        }
        runBlocking(Dispatchers.IO){transport.connectionPool.evictAll();transport.dispatcher.cancelAll()}
        waitFor("Broken transport closes the launch gate"){!RemotePlaybackService.state.value.connected}
        compose.onNodeWithTag("watch-on-tv").assertDoesNotExist()
        compose.onNodeWithText("Повторить").assertExists();proof("08-connection-lost")
        val continuing=JSONObject(java.net.URL("http://127.0.0.1:18445/state?target=${first.id}&instance=${target.instance}").readText()).getJSONObject("state")
        Assert.assertEquals("TV keeps playing without the phone","playing",continuing.getString("status"))
        java.net.URL("http://127.0.0.1:18445/restore").readText()
        compose.onNodeWithText("Повторить").performClick()
        waitFor("Reconnect obtains receiver truth"){RemotePlaybackService.state.value.connected&&RemotePlaybackService.state.value.target?.video?.session==originalSession}

        device.pressHome();Thread.sleep(2200)
        Assert.assertEquals("Foreground service survives closed UI",PlaybackState.STATE_PLAYING,controller.playbackState!!.state)
        device.sleep();Thread.sleep(1500)
        Assert.assertEquals("Locked phone leaves TV playing","playing",snapshot(first.id,target.instance).video.status)
        device.wakeUp();device.pressMenu()
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val handle=model.state.value.handle!!
        val same=RemoteMedia(handle.hash,17,"Тихая орбита","demo:remote","${handle.contentHash}:17")
        compose.runOnIdle {RemotePlaybackService.load(same)}
        waitFor("Repeated file attached"){!RemotePlaybackService.state.value.busy}
        Assert.assertEquals("Same file cannot restart",originalSession,snapshot(first.id,target.instance).video.session)
        JSONObject(java.net.URL("http://127.0.0.1:18445/second-controller?target=${first.id}&session=$originalSession").readText()).also {Assert.assertTrue(it.getBoolean("ok"))}
        Assert.assertEquals("A real second Android sender did not restart the TV",originalSession,snapshot(first.id,target.instance).video.session)
        // Replacement is explicit, and cancelling it keeps the current video intact.
        compose.runOnIdle {RemotePlaybackService.load(same.copy(fileId=18,contentId="${handle.contentHash}:18",title="Другое видео"))}
        compose.onNodeWithText("Заменить видео?").assertExists();proof("05-replacement")
        compose.onNodeWithText("Отмена").performClick()
        Assert.assertEquals(originalSession,snapshot(first.id,target.instance).video.session)
        compose.runOnIdle {RemotePlaybackService.connect(context,second.id)}
        waitFor("Second TV selected"){RemotePlaybackService.state.value.connected&&RemotePlaybackService.state.value.target?.id==second.id}
        Assert.assertEquals("Old TV keeps its video",originalSession,snapshot(first.id,target.instance).video.session)
        Assert.assertEquals("Selecting a TV never loads video","idle",RemotePlaybackService.state.value.target!!.video.status)
        compose.runOnIdle {RemotePlaybackService.load(same.copy(fileId=18,contentId="${handle.contentHash}:18",position=12000))}
        waitFor("Independent second TV starts at supplied saved position"){RemotePlaybackService.state.value.target?.video?.let {it.status=="playing"&&it.position>=12000}==true}
        Assert.assertEquals("First TV remains untouched",originalSession,snapshot(first.id,target.instance).video.session)
        compose.runOnIdle {RemotePlaybackService.disconnect(context)}
        Assert.assertEquals("Disconnecting phone leaves TV playing","playing",snapshot(first.id,target.instance).video.status)
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }
    @Test fun renameInUnifiedDevicesDialogAndLargeFont() {
        lateinit var model:WatchModel;compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java];model.page("managed-devices")}
        compose.waitUntil(15000){model.managed.state.value.data?.has("devices")==true&&!model.managed.state.value.busy}
        val rows=managedDevices(model.managed.state.value.data!!)
        val own=rows.first {it.id==model.managed.state.value.me!!.getString("deviceId")}
        compose.onNodeWithTag("device-menu-${own.id}").performScrollTo().performClick()
        compose.onNodeWithText("Переименовать").performClick()
        compose.onNodeWithTag("device-name").performTextReplacement("")
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithTag("device-name").performTextReplacement("Планшет 😀")
        proof("06-rename-dialog")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(10000){model.managed.state.value.data?.let {data->data.has("devices")&&managedDevices(data).any {it.id==own.id&&it.name=="Планшет 😀"}}==true}
        Assert.assertEquals("managed-devices",model.state.value.page)
        proof("07-unified-devices")
    }
}
