@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrLifecycleTest {
    private lateinit var fixture:JSONObject
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("qrSecurity")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        fixture=JSONObject(java.net.URL("http://127.0.0.1:18644/bootstrap").readText())
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        SecretStore(c).put("ca:${endpoint(fixture.getString("url")).origin()}","")
        Settings(c).handleLegacyHistory();Settings(c).save(Config(theme="dark"));Settings(c).destination("settings")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun press(text:String) {
        compose.onNodeWithText(text).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText(text).performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitForIdle()
    }
    @Test fun phoneCancelRejectionRefreshAndBackNeverGrantUnapprovedSession() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.page=="connection-choice"}
        compose.runOnIdle {model.page("managed-login")}
        compose.waitUntil(10000){model.managed.qr.tv.value.link!=null}
        val setup=encodeManagedTvSetup(fixture.getString("url"),fixture.getString("ca"))
        val key="ca:${endpoint(fixture.getString("url")).origin()}"
        val first=model.managed.qr.tv.value.link!!;val phone=TvQrPhone(first)
        runBlocking {phone.send(setup,"owner",fixture.getString("serverId"),fixture.getString("userId"))}
        compose.waitUntil(5000){model.managed.qr.tv.value.proposal!=null}
        Assert.assertEquals("",model.secrets.get(key))
        runBlocking {phone.cancel()}
        compose.waitUntil(5000){model.managed.qr.tv.value.link?.encode()?.let {it!=first.encode()}==true}
        Assert.assertNull(model.managed.qr.tv.value.proposal)
        Assert.assertEquals("",model.secrets.get(key))
        Assert.assertTrue(runBlocking{runCatching{phone.status()}.isFailure})
        val second=model.managed.qr.tv.value.link!!
        runBlocking {TvQrPhone(second).send(setup,"owner",fixture.getString("serverId"),fixture.getString("userId"))}
        compose.waitUntil(5000){model.managed.qr.tv.value.proposal!=null}
        press("Отклонить")
        compose.waitUntil(5000){model.managed.qr.tv.value.link?.encode()?.let {it!=second.encode()}==true}
        Assert.assertEquals("",model.secrets.get(key))
        runBlocking {TvQrPhone(model.managed.qr.tv.value.link!!).send(setup,"owner",fixture.getString("serverId"),fixture.getString("userId"))}
        compose.waitUntil(5000){model.managed.qr.tv.value.proposal!=null}
        press("Подключить")
        compose.waitUntil(15000){model.managed.state.value.pairing!=null}
        val code=model.managed.state.value.pairing!!.getString("code")
        Assert.assertEquals(managedCaFingerprint(fixture.getString("ca")),managedCaFingerprint(model.secrets.get(key)))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(5000){model.managed.qr.tv.value.link==null&&model.managed.state.value.pairing==null}
        runBlocking(Dispatchers.IO) {
            val c=Config(connectionMode="managed",managedUrl=fixture.getString("url"),serverId=fixture.getString("serverId"),userId=fixture.getString("userId"))
            val actor=ManagedApi(c,model.secrets);actor.saveTokens(fixture)
            actor.authorized("pairing/$code/approve","POST",JSONObject().put("code",code))
        }
        Thread.sleep(3500)
        Assert.assertFalse(model.state.value.config.managed)
        Assert.assertNull(model.managed.state.value.pairing)
    }
    @Test fun mismatchedAccountCannotOverwriteTokensOrConnectTv() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.page=="connection-choice"}
        val url=fixture.getString("url");val origin=endpoint(url).origin()
        model.secrets.put("ca:$origin",fixture.getString("ca"))
        val owner=Config(connectionMode="managed",managedUrl=url,serverId=fixture.getString("serverId"),userId=fixture.getString("userId"))
        val previous=model.secrets.get("access:$origin:${owner.scope}")
        val failure=runBlocking(Dispatchers.IO){runCatching{model.managed.connected(owner.copy(serverId="",userId=""),fixture,expectedServerId=fixture.getString("serverId"),expectedUserId="different-user")}.exceptionOrNull()}
        Assert.assertTrue(failure?.message?.contains("другой аккаунт")==true)
        val spoofedName=runBlocking(Dispatchers.IO){runCatching{model.managed.connected(owner.copy(serverId="",userId=""),fixture,expectedServerId=fixture.getString("serverId"),expectedUserId=fixture.getString("userId"),expectedName="spoofed owner")}.exceptionOrNull()}
        Assert.assertTrue(spoofedName?.message?.contains("другой аккаунт")==true)
        Assert.assertEquals(previous,model.secrets.get("access:$origin:${owner.scope}"))
        Assert.assertFalse(model.state.value.config.managed)
    }

    @Test fun cancellationDuringStartupClosesUnpublishedInboxes() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.page=="connection-choice"}
        fun inboxes()=Thread.getAllStackTraces().keys.count {it.isAlive&&it.name=="mjwatch-tv-qr"}
        val before=inboxes()
        repeat(3){compose.runOnIdle {model.managed.qr.startTv();Thread.sleep(50);model.managed.qr.stopTv()}}
        compose.waitUntil(5000){inboxes()<=before}
        Assert.assertNull(model.managed.qr.tv.value.link)
    }

    @Test fun newQrClearsPreviousCertificateFailure() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.page=="connection-choice"}
        compose.runOnIdle {model.managed.startPairing(fixture.getString("url"))}
        compose.waitUntil(15000){!model.managed.state.value.busy&&model.managed.state.value.message.contains("сертификат")}
        compose.runOnIdle {model.managed.qr.startTv()}
        compose.waitUntil(5000){model.managed.qr.tv.value.link!=null}
        Assert.assertEquals("",model.managed.state.value.message)
        compose.runOnIdle {model.managed.qr.stopTv()}
    }

}
