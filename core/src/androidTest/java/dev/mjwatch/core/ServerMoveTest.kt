package dev.mjwatch.core

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

@RunWith(AndroidJUnit4::class)
class ServerMoveTest {
    @Test fun expiredSessionMovesOnlyAfterDestinationProvesIdentity()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        val settings=Settings(app)
        val saved=settings.config.first()
        val password="test-only".toCharArray()
        val keys=KeyStore.getInstance("PKCS12").apply {
            instrumentation.context.assets.open("offline-loopback-test.p12").use {load(it,password)}
        }
        val managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(keys,password)}
        val tls=SSLContext.getInstance("TLS").apply {init(managers.keyManagers,null,null)}
        val certificate=Base64.encodeToString(keys.getCertificate("local-test-only").encoded,Base64.NO_WRAP)
        val ca="-----BEGIN CERTIFICATE-----\n$certificate\n-----END CERTIFICATE-----"
        MockWebServer().use {source->
            MockWebServer().use {target->
                for(server in listOf(source,target)) {
                    server.useHttps(tls.socketFactory,false)
                    server.start()
                }
                val from=source.url("/")
                val to=target.url("/")
                val config=Config(connectionMode="managed",managedUrl=from.toString(),serverId=UUID.randomUUID().toString(),userId="viewer")
                val secrets=SecretStore(app)
                secrets.put("ca:${from.origin()}",ca)
                val api=ManagedApi(config,secrets)
                api.saveTokens(JSONObject().put("accessToken","expired").put("refreshToken","valid-refresh").put("expiresIn",0))
                val identity=JSONObject().put("serverId",config.serverId).put("serverEpoch","epoch").put("migrationId","cutover")
                val notice=JSONObject(identity.toString()).put("code","server_moved").put("baseUrl",to.toString()).put("ca",ca)
                try {
                    settings.save(config)
                    source.enqueue(MockResponse().setResponseCode(409).setBody(notice.toString()))
                    val move=runCatching {api.authorized("me")}.exceptionOrNull() as ServerMove
                    val sourceRequest=source.takeRequest()
                    assertEquals("/api/v1/auth/refresh",sourceRequest.path)
                    assertEquals("valid-refresh",JSONObject(sourceRequest.body.readUtf8()).getString("refreshToken"))

                    target.enqueue(MockResponse().setBody(JSONObject(identity.toString()).put("migrationId","wrong").toString()))
                    assertTrue(runCatching {api.followMove(move,settings)}.isFailure)
                    assertEquals(config,settings.config.first())
                    val rejected=target.takeRequest()
                    assertEquals("/api/v1/server/identity",rejected.path)
                    assertNull(rejected.getHeader("Authorization"))
                    assertEquals("",secrets.get("refresh:${to.origin()}:${config.scope}"))

                    target.enqueue(MockResponse().setBody(identity.toString()))
                    assertTrue(api.followMove(move,settings))
                    val moved=settings.config.first()
                    assertEquals(config.copy(managedUrl=to.toString()),moved)
                    assertEquals(from.toString(),secrets.get(downloadSourceKey(to,moved.scope)))
                    assertNull(target.takeRequest().getHeader("Authorization"))

                    target.enqueue(MockResponse().setBody("""{"accessToken":"fresh","refreshToken":"rotated","expiresIn":900}"""))
                    target.enqueue(MockResponse().setBody(identity.put("userId",moved.userId).toString()))
                    ManagedApi(moved,secrets).authorized("me")
                    val refresh=target.takeRequest()
                    assertEquals("/api/v1/auth/refresh",refresh.path)
                    assertEquals("valid-refresh",JSONObject(refresh.body.readUtf8()).getString("refreshToken"))
                    assertEquals("Bearer fresh",target.takeRequest().getHeader("Authorization"))

                    settings.save(config.copy(theme="dark"),preserveManagedAddress=true)
                    assertEquals(to.toString(),settings.config.first().managedUrl)
                    assertEquals("dark",settings.config.first().theme)
                } finally {
                    settings.save(saved)
                }
            }
        }
    }
}
