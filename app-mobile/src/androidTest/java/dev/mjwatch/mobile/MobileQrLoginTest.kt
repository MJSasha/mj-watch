@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.mobile

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import dev.mjwatch.designsystem.WatchApp
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*

class MobileQrLoginTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            Settings(c).handleLegacyHistory();Settings(c).save(Config(theme="dark"));Settings(c).destination("home")
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun open():WatchModel {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {!model.state.value.initializing}
        compose.runOnIdle {model.page("managed-login")}
        return model
    }
    @Test fun qrIsOptionalRefreshesRejectsAndBackRestoresManualLogin() {
        val model=open()
        compose.onNodeWithContentDescription("HTTPS-адрес MJWatch").performTextReplacement("https://saved.example")
        compose.onNodeWithText("Войти по QR с другого устройства").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.waitUntil(5000) {compose.onNodeWithText("Войти по QR",substring=false).isDisplayed()}
        compose.onNodeWithText("Войти по QR",substring=false).assertIsDisplayed()
        compose.waitUntil(10000) {model.managed.qr.tv.value.let {it.link!=null||it.message.isNotBlank()}}
        Assert.assertNotNull(model.managed.qr.tv.value.message,model.managed.qr.tv.value.link)
        val image=compose.onNodeWithContentDescription("QR для входа на новом устройстве").assertIsDisplayed().captureToImage().asAndroidBitmap()
        val first=requireNotNull(model.managed.qr.tv.value.link)
        val dir=File(compose.activity.getExternalFilesDir(null),"qr-style").apply {mkdirs()}
        File(dir,"connection-qr.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        for(size in listOf(image.width,280,216,160)) {
            val scaled=Bitmap.createScaledBitmap(image,size,size,true)
            val pixels=IntArray(size*size);scaled.getPixels(pixels,0,size,0,0,size,size)
            val decoded=try {MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size,size,pixels)))).text} catch(e:ReaderException) {throw AssertionError("Styled QR did not decode at $size px",e)}
            Assert.assertEquals("Styled QR at $size px",first.encode(),decoded)
        }
        val peer=TvQrPhone(first)
        runBlocking(Dispatchers.IO) {peer.send(encodeManagedTvSetup("https://fixture.invalid",""),"Test account","server","user")}
        compose.waitUntil(5000) {model.managed.qr.tv.value.proposal!=null}
        compose.onNodeWithText("Подключиться к аккаунту Test account?").assertIsDisplayed()
        Assert.assertEquals("",model.secrets.get("ca:https://fixture.invalid:443"))
        compose.onNodeWithText("Отклонить").performScrollTo().performClick()
        compose.waitUntil(5000) {model.managed.qr.tv.value.link?.encode()?.let {it!=first.encode()}==true}
        Assert.assertTrue(runBlocking(Dispatchers.IO) {runCatching {peer.status()}.isFailure})
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
        compose.waitUntil(5000) {model.managed.qr.tv.value.link==null}
        compose.onNodeWithContentDescription("HTTPS-адрес MJWatch").assertTextContains("https://saved.example")
        Assert.assertFalse(model.state.value.config.historyEnabled)
    }
    @Test fun oneScannerIconHandlesEveryDevice() {
        val model=open()
        var scans=0
        compose.runOnUiThread {compose.activity.setContent {WatchApp(model,false,scanQr={scans++})}}
        compose.runOnIdle {model.page("managed-account")}
        compose.onAllNodesWithContentDescription("Сканировать QR").assertCountEquals(1)
        compose.onNodeWithText("Добавить телевизор").assertDoesNotExist()
        compose.onNodeWithText("Добавить телефон или планшет").assertDoesNotExist()
        compose.onNodeWithContentDescription("Сканировать QR").performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.runOnIdle {Assert.assertEquals(1,scans);Assert.assertEquals("managed-pairing",model.state.value.page)}
        compose.onAllNodesWithContentDescription("Сканировать QR").assertCountEquals(1)
        compose.onNodeWithContentDescription("Сканировать QR").performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.runOnIdle {Assert.assertEquals(2,scans)}
    }
    @Test fun approvedQrCreatesSeparateMobileSession() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("mobileQrFixture")=="true")
        val model=open()
        val fixture=runBlocking(Dispatchers.IO) {JSONObject(java.net.URL("http://127.0.0.1:18644/bootstrap").readText())}
        val actor=Config(connectionMode="managed",managedUrl=fixture.getString("url"),serverId=fixture.getString("serverId"),userId=fixture.getString("userId"))
        val api=ManagedApi(actor,model.secrets,fixture.getString("ca"))
        api.saveTokens(fixture)
        compose.onNodeWithText("Войти по QR с другого устройства").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.waitUntil(5000) {compose.onNodeWithText("Войти по QR",substring=false).isDisplayed()}
        compose.onNodeWithText("Войти по QR",substring=false).assertIsDisplayed()
        compose.waitUntil(10000) {model.managed.qr.tv.value.let {it.link!=null||it.message.isNotBlank()}}
        Assert.assertNotNull(model.managed.qr.tv.value.message,model.managed.qr.tv.value.link)
        val peer=TvQrPhone(requireNotNull(model.managed.qr.tv.value.link))
        runBlocking(Dispatchers.IO) {peer.send(encodeManagedTvSetup(actor.managedUrl,fixture.getString("ca")),"owner",actor.serverId,actor.userId)}
        compose.waitUntil(5000) {model.managed.qr.tv.value.proposal!=null}
        compose.onNodeWithText("Подключить",substring=false).performScrollTo().performClick()
        compose.waitUntil(15000) {model.managed.state.value.pairing!=null}
        val code=model.managed.state.value.pairing!!.getString("code")
        runBlocking(Dispatchers.IO) {
            Assert.assertEquals(android.os.Build.MODEL,api.authorized("pairing/lookup","POST",JSONObject().put("code",code)).getJSONObject("pairing").getString("device"))
            api.authorized("pairing/$code/approve","POST",JSONObject().put("code",code))
        }
        compose.waitUntil(15000) {model.state.value.page=="home"&&model.state.value.config.historyEnabled}
        val me=runBlocking(Dispatchers.IO) {ManagedApi(model.state.value.config,model.secrets).authorized("me")}
        Assert.assertEquals(actor.userId,me.getString("userId"))
        Assert.assertNotEquals(fixture.getString("deviceId"),me.getString("deviceId"))
        compose.waitUntil(5000) {model.managed.qr.tv.value.link==null}
        compose.runOnIdle {model.page("managed-account")}
        compose.onAllNodesWithContentDescription("Сканировать QR").assertCountEquals(1)
        compose.runOnIdle {model.page("managed-pairing")}
        compose.onNodeWithContentDescription("Сканировать QR").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {model.managed.qr.scan("invalid")}
        compose.onNodeWithContentDescription("Сканировать QR").assertIsEnabled()
    }
}
