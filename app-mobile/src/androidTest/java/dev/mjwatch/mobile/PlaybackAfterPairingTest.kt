package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Real HTTPS fixture and encrypted TV inbox; no production account or server. */
@RunWith(AndroidJUnit4::class)
class PlaybackAfterPairingTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("playbackPairing")=="true")
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            val fixture=JSONObject(java.net.URL("http://127.0.0.1:18644/bootstrap").readText())
            val config=Config(connectionMode="managed",managedUrl=fixture.getString("url"),serverId=fixture.getString("serverId"),userId=fixture.getString("userId"),jackett=fixture.getString("url")+"/fixture-source",releaseApi="jacred",theme="dark")
            c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            c.getSharedPreferences("playback-preferences",0).edit().clear().commit()
            val secrets=SecretStore(c)
            secrets.put("ca:${endpoint(config.managedUrl).origin()}",fixture.getString("ca"))
            ManagedApi(config,secrets).saveTokens(fixture)
            Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("settings")
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @Test fun approvingTelevisionKeepsPhonePlaybackAvailable() {
        lateinit var model:WatchModel
        ui.runOnIdle {model=ViewModelProvider(ui.activity)[WatchModel::class.java]}
        ui.waitUntil(15000) {model.state.value.config.historyEnabled}
        val config=model.state.value.config
        val api=ManagedApi(config,model.secrets)
        val original=runBlocking(Dispatchers.IO) {api.authorized("me")}
        val pairing=runBlocking(Dispatchers.IO) {api.request("pairing","POST",JSONObject().put("device","Playback test TV"))}
        val inbox=TvQrServer(tvQrLocalAddress(),{},{JSONObject().put("stage","phone").put("code",pairing.getString("code"))})
        try {
            ui.runOnIdle {model.page("managed-pairing");model.managed.qr.scan(inbox.link.encode())}
            ui.waitUntil(15000) {model.managed.qr.phone.value.stage=="preview"}
            ui.onNode(hasText("Подключить устройство") and hasClickAction()).performScrollTo().performClick()
            ui.waitUntil(15000) {model.managed.qr.phone.value.stage=="approved"}
            Assert.assertEquals(config,model.state.value.config)
            runBlocking(Dispatchers.IO) {
                Assert.assertEquals(original.getString("deviceId"),api.authorized("me").getString("deviceId"))
                val tv=api.request("pairing/${pairing.getString("id")}",headers=mapOf("X-Pairing-Secret" to pairing.getString("pollSecret")))
                Assert.assertNotEquals(original.getString("deviceId"),tv.getString("deviceId"))
            }
        } finally {inbox.close()}
        ui.runOnIdle {model.metadata(Metadata("tmdb:movie:777778","Managed fixture",null,null))}
        ui.onNodeWithTag("detail-sources").performClick()
        ui.waitUntil(15000) {model.state.value.results.isNotEmpty()&&!model.state.value.busy}
        ui.onNodeWithTag("release-list").performScrollToNode(hasText("Managed fixture 720p"))
        ui.onNodeWithText("Managed fixture 720p").performClick()
        ui.waitForIdle()
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(c.getExternalFilesDir(null),"playback-after-pairing").apply {mkdirs()}
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(dir,"selected-release.png"))
        ui.onNodeWithTag("source-continue").assertIsDisplayed().assertIsEnabled().performClick()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var player:PlaybackActivity?=null
        ui.waitUntil(30000) {
            instrumentation.runOnMainSync {player=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull {it.firstFrameSeen}}
            player!=null
        }
        UiDevice.getInstance(instrumentation).takeScreenshot(File(dir,"phone-first-frame.png"))
        instrumentation.runOnMainSync {Assert.assertTrue(player!!.intent.getBooleanExtra("mobile",false));player!!.finish()}
    }
}
