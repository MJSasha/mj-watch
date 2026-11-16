package dev.mjwatch.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class QrProtocolTest {
    @Before fun isolated(){Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("qrSecurity")=="true")}
    @Test fun tamperedKeySecondPhoneAndOldSessionCannotReplaceProposal()=runBlocking {
        val received=AtomicInteger();val cancelled=CountDownLatch(1)
        lateinit var server:TvQrServer
        server=TvQrServer(tvQrLocalAddress(),{received.incrementAndGet()},{JSONObject().put("stage","confirm")},cancelled={cancelled.countDown();server.close()})
        val setup=encodeManagedTvSetup("https://fixture.invalid","")
        try {
            val phone=TvQrPhone(server.link)
            Assert.assertEquals("confirm",phone.send(setup,"owner","test-server","test-user").getString("stage"))
            Assert.assertEquals("confirm",phone.send(setup,"owner","test-server","test-user").getString("stage"))
            Assert.assertEquals(1,received.get())
            val wrongKey=server.link.encode().substringBefore("&key=")+"&key="+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32){42})
            val rejected=runCatching{TvQrPhone(TvQrLink.decode(wrongKey)).send(setup,"attacker","test-server","test-user")}.exceptionOrNull()
            Assert.assertEquals(403,(rejected as HttpFailure).status)
            val other=runCatching{TvQrPhone(server.link).send(setup,"other account","test-server","test-user")}.exceptionOrNull()
            Assert.assertEquals(409,(other as HttpFailure).status)
            Assert.assertEquals(1,received.get())
            phone.cancel();Assert.assertTrue(cancelled.await(2,TimeUnit.SECONDS))
            Assert.assertTrue(runCatching{phone.status()}.isFailure)
            val next=TvQrServer(tvQrLocalAddress(),{received.incrementAndGet()},{JSONObject().put("stage","confirm")})
            try {
                val stale=next.link.encode().substringBefore("&key=")+"&key="+server.link.encode().substringAfter("&key=")
                val error=runCatching{TvQrPhone(TvQrLink.decode(stale)).send(setup,"old session","test-server","test-user")}.exceptionOrNull()
                Assert.assertEquals(403,(error as HttpFailure).status)
                Assert.assertEquals(1,received.get())
            }finally{next.close()}
        }finally{server.close()}
    }
    @Test fun expiryClosesInboxAndCannotReceiveSettings()=runBlocking {
        val expired=CountDownLatch(1);val received=AtomicInteger()
        val server=TvQrServer(tvQrLocalAddress(),{received.incrementAndGet()},{JSONObject().put("stage","confirm")},expired={expired.countDown()},lifetimeMs=100)
        try {
            Assert.assertTrue(expired.await(3,TimeUnit.SECONDS))
            Assert.assertFalse(server.active)
            Assert.assertTrue(runCatching{TvQrPhone(server.link).send(encodeManagedTvSetup("https://fixture.invalid",""),"owner","test-server","test-user")}.isFailure)
            Assert.assertEquals(0,received.get())
        }finally{server.close()}
    }
}
