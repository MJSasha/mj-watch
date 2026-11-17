package dev.mjwatch.mobile

import android.media.session.MediaController
import android.media.session.MediaSessionManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.playback.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*

/** A different Android process/device joins the already playing receiver. */
class SecondControllerTest {
    @Test fun joinWithoutRestartAndControlThroughAndroid()=runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("isolatedRemote")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val response=withContext(Dispatchers.IO){JSONObject(java.net.URL("http://127.0.0.1:18444/bootstrap").readText())}
        val config=Config(connectionMode="managed",managedUrl=response.getString("url"),serverId=response.getString("serverId"),userId=response.getString("userId"),metadataProvider="none")
        val secrets=SecretStore(context);secrets.put("ca:${endpoint(config.managedUrl).origin()}",response.getString("ca"))
        ManagedApi(config,secrets).saveTokens(response);Settings(context).save(config);Settings(context).destination("home");Settings(context).handleLegacyHistory()
        context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        val receiver=args.getString("receiver")!!;val expected=args.getString("session")!!
        suspend fun await(message:String,test:()->Boolean){withTimeout(20000){while(!test()){delay(100)}}}
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {RemotePlaybackService.connect(it,receiver)}
            await("Receiver joined"){RemotePlaybackService.state.value.canLaunch&&RemotePlaybackService.state.value.target?.video?.active==true}
            Assert.assertEquals(expected,RemotePlaybackService.state.value.target!!.video.session)
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
            val sessions=context.getSystemService(MediaSessionManager::class.java)
            await("System media session"){sessions.getActiveSessions(null).any {it.packageName==context.packageName}}
            val controller=sessions.getActiveSessions(null).single {it.packageName==context.packageName}
            Assert.assertEquals(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE,controller.playbackInfo.playbackType)
            controller.transportControls.pause()
            await("Second phone paused receiver"){RemotePlaybackService.state.value.target?.video?.status=="paused"}
            controller.transportControls.play()
            await("Second phone resumed receiver"){RemotePlaybackService.state.value.target?.video?.status=="playing"}
            Assert.assertEquals(expected,RemotePlaybackService.state.value.target!!.video.session)
            scenario.onActivity {RemotePlaybackService.disconnect(it)}
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
