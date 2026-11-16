package dev.mjwatch.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TvQrConnectionTest {
    private fun link()=TvQrLink("192.168.1.20",23456,ByteArray(32){it.toByte()})
    @Test fun qrRoundTripsAndRejectsTargetsOutsidePrivateLan() {
        val raw=link().encode()
        assertEquals(raw,TvQrLink.decode(raw).encode())
        assertEquals("TvQrLink(redacted)",TvQrLink.decode(raw).toString())
        for(ip in listOf("127.0.0.1","8.8.8.8","169.254.169.254","evil.invalid","192.168.001.20","256.1.1.1","224.0.0.1"))
            assertTrue(ip,runCatching{TvQrLink.decode(raw.replace("192.168.1.20",ip))}.isFailure)
        for(value in listOf(raw+"&ip=192.168.1.21",raw+"#fragment",raw.replace("v=1","v=2"),raw.replace("mjwatch://","https://"),raw.replace("port=23456","port=80"),raw.replace("key=","key=bad"),"x".repeat(513)))
            assertTrue(value.take(30),runCatching{TvQrLink.decode(value)}.isFailure)
    }
    @Test fun messagesAreEncryptedAuthenticatedAndBoundToDirectionAndEndpoint() {
        val key=link();val plain=JSONObject().put("nonce","0123456789012345678901").put("setup","private server settings")
        val sealed=key.seal("/setup",false,plain)
        assertFalse(String(sealed).contains("private server settings"))
        assertEquals(plain.toString(),key.open("/setup",false,sealed).toString())
        assertFalse(sealed.contentEquals(key.seal("/setup",false,plain)))
        assertTrue(runCatching{key.open("/status",false,sealed)}.isFailure)
        assertTrue(runCatching{key.open("/setup",true,sealed)}.isFailure)
        val wrong=TvQrLink("192.168.1.20",23456,ByteArray(32){42})
        assertTrue(runCatching{wrong.open("/setup",false,sealed)}.isFailure)
        sealed[sealed.lastIndex]=(sealed.last().toInt() xor 1).toByte()
        assertTrue(runCatching{key.open("/setup",false,sealed)}.isFailure)
        assertTrue(runCatching{key.open("/setup",false,ByteArray(65537))}.isFailure)
    }
    @Test fun authenticatedNestedPayloadsAreRejectedWithoutRecursiveParsing() {
        val key=link()
        for(value in listOf(JSONObject().put("nonce",JSONObject().put("nested","x")),JSONObject().put("nonce",org.json.JSONArray().put("x")))) {
            assertTrue(runCatching{key.open("/setup",false,key.seal("/setup",false,value))}.isFailure)
        }
    }

}
