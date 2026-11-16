@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
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
class PairingTvUiTest {
    private lateinit var fixture:JSONObject
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("pairingFixture")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val control=InstrumentationRegistry.getArguments().getString("pairingControlPort") ?: "18544"
        fixture=JSONObject(java.net.URL("http://127.0.0.1:$control/bootstrap").readText())
        context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        SecretStore(context).put("ca:${endpoint(fixture.getString("url")).origin()}","")
        Settings(context).handleLegacyHistory()
        Settings(context).save(Config(connectionMode="direct",managedUrl=fixture.getString("url"),theme="dark"))
        Settings(context).destination("settings")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun press(text:String) {
        compose.onNodeWithText(text).performScrollTo().assertIsEnabled().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText(text).performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitForIdle()
    }
    private fun confirm(text:String) {
        compose.onNodeWithText(text).assertIsEnabled().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText(text).performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitForIdle()
    }
    private fun screenshot(name:String) {
        compose.waitForIdle()
        val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"tv-pairing").apply{mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun privateCaFileImportRemoteRetryCancelAndApprovedSession() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.config.managedUrl.isNotBlank()}
        compose.runOnIdle {model.page("managed-login")}
        press("Другие способы входа")
        compose.onNodeWithText("Имя пользователя").assertDoesNotExist()
        compose.onNodeWithText("Пароль").assertDoesNotExist()
        press("Получить код")
        compose.waitUntil(15000){!model.managed.state.value.busy&&model.managed.state.value.message.isNotBlank()}
        Assert.assertNull(model.managed.state.value.pairing)
        Assert.assertTrue(model.managed.state.value.message.contains("сертификат"))
        compose.onNodeWithTag("operation-message").assertIsDisplayed()
        screenshot("private-ca-error")
        press("Повторить получение кода")
        compose.waitUntil(15000){!model.managed.state.value.busy&&model.managed.state.value.message.isNotBlank()}
        val values=ContentValues().apply {put(MediaStore.Downloads.DISPLAY_NAME,"mjwatch-tv-${System.nanoTime()}.json");put(MediaStore.Downloads.MIME_TYPE,"application/json");put(MediaStore.Downloads.RELATIVE_PATH,"Download")}
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        val packet=encodeManagedTvSetup(fixture.getString("url"),fixture.getString("ca"))
        context.contentResolver.openOutputStream(uri)!!.use {it.write("broken".toByteArray())}
        try {
            compose.runOnIdle {model.managed.importTvSetup(uri)}
            compose.waitUntil(10000){!model.managed.state.value.busy&&model.managed.state.value.message.contains("не распознан")}
            Assert.assertNull(model.managed.state.value.pairingSetup)
            Assert.assertNull(model.managed.state.value.pairing)
            context.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(packet.toByteArray())}
            // Exercise the actual system document picker, not just the parser/controller.
            press("Импортировать настройки")
            val device=UiDevice.getInstance(i)
            val file=device.wait(Until.findObject(By.text(values.getAsString(MediaStore.Downloads.DISPLAY_NAME))),5000)
            Assert.assertNotNull("Exported JSON available through DocumentsUI",file)
            file!!.click()
            compose.waitUntil(10000){model.managed.state.value.pairingSetup!=null}
            Assert.assertEquals("",model.secrets.get("ca:${endpoint(fixture.getString("url")).origin()}"))
            compose.onNodeWithText(managedCaFingerprint(fixture.getString("ca"))).assertIsDisplayed()
            confirm("Отмена")
            Assert.assertNull(model.managed.state.value.pairingSetup)
            Assert.assertEquals("",model.secrets.get("ca:${endpoint(fixture.getString("url")).origin()}"))
            // Same URI retained by this test; trust still requires the visible confirmation.
            compose.runOnIdle {model.managed.importTvSetup(uri)}
            compose.waitUntil(10000){model.managed.state.value.pairingSetup!=null}
            confirm("Отпечаток совпадает")
            compose.waitUntil(15000){model.managed.state.value.pairing!=null}
            val first=model.managed.state.value.pairing!!.getString("id")
            press("Отменить вход по коду")
            press("Получить код")
            compose.waitUntil(15000){model.managed.state.value.pairing!=null}
            Assert.assertNotEquals(first,model.managed.state.value.pairing!!.getString("id"))
            Thread.sleep(3300)
            val pair=model.managed.state.value.pairing!!
            compose.onNodeWithText(pair.getString("code")).assertIsDisplayed()
            screenshot("code-ready")
            runBlocking(Dispatchers.IO) {
                val config=Config(connectionMode="managed",managedUrl=fixture.getString("url"),serverId=fixture.getString("serverId"),userId=fixture.getString("userId"))
                val phone=ManagedApi(config,model.secrets);phone.saveTokens(fixture)
                val preview=phone.authorized("pairing/lookup","POST",JSONObject().put("code",pair.getString("code"))).getJSONObject("pairing")
                Assert.assertEquals(android.os.Build.MODEL,preview.getString("device"))
                val fresh=ManagedApi(config.copy(serverId="",userId=""),model.secrets)
                Assert.assertEquals(404,(runCatching {fresh.request("pairing/${pair.getString("id")}",headers=mapOf("X-Pairing-Secret" to "wrong"))}.exceptionOrNull() as HttpFailure).status)
                phone.authorized("pairing/${pair.getString("code")}/approve","POST",JSONObject().put("code",pair.getString("code")))
            }
            compose.waitUntil(20000){model.state.value.config.historyEnabled&&model.state.value.page=="home"}
            Assert.assertNull(model.managed.state.value.pairing)
            Assert.assertEquals(fixture.getString("userId"),model.state.value.config.userId)
            runBlocking(Dispatchers.IO) {
                val me=ManagedApi(model.state.value.config,model.secrets).authorized("me")
                Assert.assertNotEquals("Phone and TV have separate sessions",fixture.optString("deviceId"),me.getString("deviceId"))
                // The CA stays scoped to this origin, and never disables hostname verification.
                val wrong=model.state.value.config.copy(managedUrl="https://wrong.invalid:${endpoint(fixture.getString("url")).port}")
                model.secrets.put("ca:${endpoint(wrong.managedUrl).origin()}",fixture.getString("ca"))
                try {
                    val client=managedClient(wrong,model.secrets).newBuilder().dns(object:okhttp3.Dns{override fun lookup(hostname:String)=listOf(java.net.InetAddress.getByName("127.0.0.1"))}).build()
                    val failure=runCatching{client.newCall(okhttp3.Request.Builder().url(wrong.managedUrl+"/api/v1/me").build()).execute().use{it.code}}.exceptionOrNull()
                    Assert.assertTrue(failure is javax.net.ssl.SSLException)
                }finally{model.secrets.put("ca:${endpoint(wrong.managedUrl).origin()}","")}
            }
            screenshot("connected")
        }finally{context.contentResolver.delete(uri,null,null)}
    }
    @Test fun leavingPairingScreenStopsPollingAndCannotSignInLater() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.config.managedUrl.isNotBlank()}
        model.secrets.put("ca:${endpoint(fixture.getString("url")).origin()}",fixture.getString("ca"))
        compose.runOnIdle {model.page("managed-login")}
        press("Другие способы входа")
        press("Получить код")
        compose.waitUntil(15000){model.managed.state.value.pairing!=null}
        val pair=model.managed.state.value.pairing!!
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(5000){model.managed.state.value.pairing==null}
        runBlocking(Dispatchers.IO) {
            val config=Config(connectionMode="managed",managedUrl=fixture.getString("url"),serverId=fixture.getString("serverId"),userId=fixture.getString("userId"))
            val phone=ManagedApi(config,model.secrets);phone.saveTokens(fixture)
            phone.authorized("pairing/${pair.getString("code")}/approve","POST",JSONObject().put("code",pair.getString("code")))
        }
        Thread.sleep(3500)
        Assert.assertFalse("Cancelled pairing cannot switch the account",model.state.value.config.managed)
        Assert.assertNull(model.managed.state.value.pairing)
    }

}
