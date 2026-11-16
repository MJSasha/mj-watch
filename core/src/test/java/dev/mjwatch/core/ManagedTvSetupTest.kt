package dev.mjwatch.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManagedTvSetupTest {
    private val ca get()=javaClass.getResource("/invitation-ca.pem")!!.readText()
    @Test fun publicPacketRoundTripsWithoutAccountCredentials() {
        val raw=encodeManagedTvSetup("https://fixture.invalid:8443",ca)
        val json=JSONObject(raw)
        assertEquals(setOf("type","schema","url","ca"),json.keys().asSequence().toSet())
        val setup=decodeManagedTvSetup(raw)
        assertEquals("https://fixture.invalid:8443/",setup.url)
        assertEquals(managedCaFingerprint(ca),managedCaFingerprint(setup.ca))
        assertEquals("ManagedTvSetup(redacted)",setup.toString())
        assertEquals("",decodeManagedTvSetup(encodeManagedTvSetup("https://public.invalid","")).ca)
    }
    @Test fun expiredPairingOffersNewCodeInsteadOfGeneric404() {
        assertTrue(safeError(HttpFailure(404,responseBody="""{"code":"pairing_expired"}""")).contains("Получите новый код"))
        assertTrue(safeError(HttpFailure(503,responseBody="""{"code":"pairing_busy"}""")).contains("Повторите"))
    }
    @Test fun wrongFormatUnsafeAddressAndInvalidCaAreRejected() {
        val valid=encodeManagedTvSetup("https://fixture.invalid",ca)
        for(url in listOf("http://fixture.invalid","https://user:password@fixture.invalid","https://fixture.invalid/?token=secret"))
            assertTrue(runCatching{decodeManagedTvSetup(JSONObject(valid).put("url",url).toString())}.isFailure)
        for(field in listOf("type","schema","url"))assertTrue(runCatching{decodeManagedTvSetup(JSONObject(valid).apply{remove(field)}.toString())}.isFailure)
        assertTrue(runCatching{decodeManagedTvSetup(JSONObject(valid).put("schema",2).toString())}.isFailure)
        assertTrue(runCatching{decodeManagedTvSetup(JSONObject(valid).put("ca","broken").toString())}.isFailure)
        assertTrue(runCatching{decodeManagedTvSetup(" ".repeat(65537))}.isFailure)
    }
}
