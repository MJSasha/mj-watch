package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class QrPhoneUiTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("qrSecurity")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=JSONObject(java.net.URL("http://127.0.0.1:18644/bootstrap").readText())
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        val config=Config(connectionMode="managed",managedUrl=fixture.getString("url"),serverId=fixture.getString("serverId"),userId=fixture.getString("userId"),theme="dark")
        val secrets=SecretStore(c);secrets.put("ca:${endpoint(config.managedUrl).origin()}",fixture.getString("ca"));ManagedApi(config,secrets).saveTokens(fixture)
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("settings")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun malformedOfflineCancelRetryAndAccountChangeKeepScannerUsable() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(15000){model.state.value.config.historyEnabled}
        compose.runOnIdle {model.page("managed-tv");model.managed.qr.scan("https://invalid.example/")}
        compose.onNodeWithText("Сканировать QR телевизора").assertIsEnabled()
        Assert.assertEquals("error",model.managed.qr.phone.value.stage)
        val cancelled=AtomicInteger();val received=AtomicInteger()
        lateinit var server:TvQrServer
        fun fresh()=TvQrServer(tvQrLocalAddress(),{received.incrementAndGet()},{JSONObject().put("stage","confirm")},cancelled={cancelled.incrementAndGet();server.close()})
        server=fresh();val offline=server.link;server.close()
        try {
            compose.runOnIdle {model.managed.qr.scan(offline.encode())}
            compose.waitUntil(15000){model.managed.qr.phone.value.stage=="error"}
            compose.onNodeWithText("Сканировать QR телевизора").assertIsEnabled()
            server=fresh()
            compose.runOnIdle {model.managed.qr.scan(server.link.encode())}
            compose.waitUntil(15000){model.managed.qr.phone.value.stage=="waiting"}
            compose.onNodeWithText("Отменить подключение TV").performScrollTo().performClick()
            compose.waitUntil(5000){cancelled.get()==1&&model.managed.qr.phone.value.stage.isBlank()}
            compose.onNodeWithText("Сканировать QR телевизора").assertIsEnabled()
            server=fresh()
            compose.runOnIdle {model.managed.qr.scan(server.link.encode())}
            compose.waitUntil(15000){model.managed.qr.phone.value.stage=="waiting"}
            runBlocking {Settings(InstrumentationRegistry.getInstrumentation().targetContext).save(Config(theme="dark"))}
            compose.waitUntil(10000){cancelled.get()==2&&model.managed.qr.phone.value.stage.isBlank()}
            Assert.assertEquals(2,received.get())
        }finally{server.close()}
    }
    @Test fun stalePreviewCannotApproveAnotherTelevision() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(15000){model.state.value.config.historyEnabled}
        compose.runOnIdle {model.page("managed-tv")}
        val api=ManagedApi(model.state.value.config,model.secrets)
        val codes=runBlocking(Dispatchers.IO){listOf("old TV","new TV").map {api.request("pairing","POST",JSONObject().put("device",it)).getString("code")}}
        fun inbox(code:String)=TvQrServer(tvQrLocalAddress(),{},{JSONObject().put("stage","phone").put("code",code)})
        val first=inbox(codes[0]);val second=inbox(codes[1])
        try {
            compose.runOnIdle {model.managed.qr.scan(first.link.encode())}
            compose.waitUntil(15000){model.managed.qr.phone.value.stage=="preview"}
            val stale=model.managed.qr.phone.value
            compose.runOnIdle {model.managed.qr.cancelPhone()};first.close()
            compose.runOnIdle {model.managed.qr.scan(second.link.encode())}
            compose.waitUntil(15000){model.managed.qr.phone.value.stage=="preview"}
            compose.runOnIdle {model.managed.qr.approvePhone(stale)}
            Assert.assertEquals("preview",model.managed.qr.phone.value.stage)
            Assert.assertEquals(codes[1],model.managed.qr.phone.value.code)
            runBlocking(Dispatchers.IO){Assert.assertEquals("new TV",api.authorized("pairing/lookup","POST",JSONObject().put("code",codes[1])).getJSONObject("pairing").getString("device"))}
        }finally{first.close();second.close()}
    }

}
