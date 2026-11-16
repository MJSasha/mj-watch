package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QrCameraTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("qrCamera")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=JSONObject(java.net.URL("http://127.0.0.1:18644/bootstrap").readText())
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        val config=Config(connectionMode="managed",managedUrl=fixture.getString("url"),serverId=fixture.getString("serverId"),userId=fixture.getString("userId"),theme="dark")
        val secrets=SecretStore(c);secrets.put("ca:${endpoint(config.managedUrl).origin()}",fixture.getString("ca"));ManagedApi(config,secrets).saveTokens(fixture)
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("settings")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun cameraScansDisplayedTvQrAndExplicitApprovalCreatesSeparateDevice() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(15000){model.state.value.config.historyEnabled}
        val before=runBlocking(Dispatchers.IO) {managedDevices(ManagedApi(model.state.value.config,model.secrets).authorized("devices")).map {it.id}.toSet()}
        compose.runOnIdle {model.page("managed-tv")}
        compose.onNodeWithText("Сохранить настройки TV").assertDoesNotExist()
        compose.onNodeWithText("Код телевизора").assertDoesNotExist()
        compose.onNodeWithText("Сканировать QR телевизора").performScrollTo().performClick()
        val i=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(i)
        device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button")),6000)?.click()
        compose.waitUntil(150000){model.managed.qr.phone.value.stage in setOf("preview","error")}
        Assert.assertEquals(model.managed.qr.phone.value.message,"preview",model.managed.qr.phone.value.stage)
        compose.onNodeWithText("Аккаунт: owner").assertExists()
        val dir=File(i.targetContext.getExternalFilesDir(null),"qr-proof").apply {mkdirs()}
        Assert.assertTrue(device.takeScreenshot(File(dir,"phone-preview.png")))
        compose.onNodeWithText("Подключить телевизор").performScrollTo().performClick()
        compose.waitUntil(15000){model.managed.qr.phone.value.stage in setOf("approved","error")}
        Assert.assertEquals(model.managed.qr.phone.value.message,"approved",model.managed.qr.phone.value.stage)
        runBlocking(Dispatchers.IO) {
            val api=ManagedApi(model.state.value.config,model.secrets)
            withTimeout(25000) {
                while(true) {
                    val devices=managedDevices(api.authorized("devices"))
                    if(devices.any {it.id !in before&&it.name==android.os.Build.MODEL})break
                    delay(500)
                }
            }
        }
        Assert.assertTrue(device.takeScreenshot(File(dir,"phone-approved.png")))
    }
}
