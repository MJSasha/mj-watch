package dev.mjwatch.tv

import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*

/** Seeds only the disposable cross-emulator fixture. The runner then launches the real TV app. */
class RemoteReceiverSetupTest {
    @Test fun seedIsolatedReceiver()=runBlocking<Unit> {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedRemote")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val result=JSONObject(java.net.URL("http://127.0.0.1:18444/bootstrap").readText())
        val config=Config(connectionMode="managed",managedUrl=result.getString("url"),serverId=result.getString("serverId"),userId=result.getString("userId"),metadataProvider="none",theme="dark")
        val secrets=SecretStore(context);secrets.put("ca:${endpoint(config.managedUrl).origin()}",result.getString("ca"))
        val api=ManagedApi(config,secrets);api.saveTokens(result)
        api.authorized("devices/${result.getString("deviceId")}","PATCH",JSONObject().put("name",InstrumentationRegistry.getArguments().getString("receiverName") ?: "Тестовый ТВ"))
        api.authorized("devices/self/profile","PUT",JSONObject().put("type","tv"))
        Settings(context).save(config);Settings(context).destination("home");Settings(context).handleLegacyHistory()
        context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
    }
}
