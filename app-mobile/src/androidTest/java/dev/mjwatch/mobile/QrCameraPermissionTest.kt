package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrCameraPermissionTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("qrCameraDeny")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=JSONObject(java.net.URL("http://127.0.0.1:18644/bootstrap").readText())
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        val config=Config(connectionMode="managed",managedUrl=fixture.getString("url"),serverId=fixture.getString("serverId"),userId=fixture.getString("userId"),theme="dark")
        val secrets=SecretStore(c);secrets.put("ca:${endpoint(config.managedUrl).origin()}",fixture.getString("ca"));ManagedApi(config,secrets).saveTokens(fixture)
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("settings")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun denyingCameraReturnsToUsableScannerWithRussianExplanation() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(15000){model.state.value.config.historyEnabled}
        compose.runOnIdle {model.page("managed-pairing")}
        compose.onNodeWithContentDescription("Сканировать QR").performScrollTo().performClick()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val deny=device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button")),6000)
        Assert.assertNotNull("Run with CAMERA revoked on this isolated device",deny)
        deny!!.click()
        compose.waitUntil(10000){model.managed.qr.phone.value.stage=="error"}
        compose.onNodeWithContentDescription("Сканировать QR").assertIsEnabled()
        Assert.assertTrue(model.managed.qr.phone.value.message.contains("камере"))
    }
}
