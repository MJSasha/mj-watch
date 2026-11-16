@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QrHostTest {
    private lateinit var fixture:JSONObject
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("qrHost")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        fixture=JSONObject(java.net.URL("http://127.0.0.1:18644/bootstrap").readText())
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        SecretStore(c).put("ca:${endpoint(fixture.getString("url")).origin()}","")
        Settings(c).handleLegacyHistory();Settings(c).save(Config(theme="dark"));Settings(c).destination("settings")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun displayedQrToPhoneCameraPrivateCaAndActualTvSession() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.page=="connection-choice"}
        compose.runOnIdle {model.page("managed-login")}
        compose.waitUntil(15000){model.managed.qr.tv.value.link!=null}
        compose.onNodeWithText("HTTPS-адрес MJWatch").assertDoesNotExist()
        compose.onNodeWithText("Импортировать настройки").assertDoesNotExist()
        val bitmap=compose.onNodeWithTag("tv-connect-qr").captureToImage().asAndroidBitmap()
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val decoded=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels)))).text
        Assert.assertEquals(model.managed.qr.tv.value.link!!.encode(),decoded)
        val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"qr-proof").apply{mkdirs()}
        File(dir,"tv-qr.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        val camera=Bitmap.createBitmap(1920,1920,Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            // Place the captured TV QR inside the imagefile camera's portrait crop.
            android.graphics.Canvas(this).drawBitmap(bitmap,190f,620f,null)
        }
        File(dir,"camera-qr.png").outputStream().use {camera.compress(Bitmap.CompressFormat.PNG,100,it)}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"tv-qr-screen.png")))
        File(dir,"ready").writeText("QR ready")
        compose.waitUntil(200000){model.managed.qr.tv.value.proposal!=null}
        val proposal=model.managed.qr.tv.value.proposal!!
        Assert.assertEquals("owner",proposal.account)
        Assert.assertEquals(fixture.getString("serverId"),proposal.serverId)
        Assert.assertEquals(fixture.getString("userId"),proposal.userId)
        Assert.assertEquals("",model.secrets.get("ca:${endpoint(proposal.setup.url).origin()}"))
        compose.onNodeWithText("Подключить").assertIsFocused()
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"tv-confirm.png")))
        compose.onNodeWithText("Подключить").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(60000){model.state.value.page=="home"&&model.state.value.config.historyEnabled}
        Assert.assertEquals(fixture.getString("userId"),model.state.value.config.userId)
        compose.waitUntil(5000){model.managed.qr.tv.value.link==null}
        Assert.assertNull(model.managed.qr.tv.value.link)
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"tv-connected.png")))
        File(dir,"connected").writeText("Actual TV account session established")
    }
}
