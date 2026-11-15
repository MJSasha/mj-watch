package dev.mjwatch.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManagedInvitationTest {
 private val ca get()=javaClass.getResource("/invitation-ca.pem")!!.readText()
 private fun packet()=JSONObject().put("url","https://fixture.invalid:8443").put("invite","fixture-invitation-token").put("ca",ca)
 @Test fun fullCopiedPacketPreservesAddressCodeAndCertificate(){
  val value=decodeManagedInvitation(packet().toString())
  assertEquals("https://fixture.invalid:8443/",value.url);assertEquals("fixture-invitation-token",value.invite)
  assertEquals(ca.trim(),value.ca);assertFalse(value.toString().contains(value.invite))
  assertEquals(managedCaFingerprint(ca),managedCaFingerprint(ca.replace("\n","\\n")))
 }
 @Test fun unsafeUrlsAndBrokenCaAreRejectedBeforeAnyRequest(){
  for(url in listOf("http://fixture.invalid","https://user:password@fixture.invalid","https://fixture.invalid/?key=secret"))assertTrue(runCatching{decodeManagedInvitation(packet().put("url",url).toString())}.isFailure)
  for(value in listOf("", "-----BEGIN CERTIFICATE-----\nbroken\n-----END CERTIFICATE-----"))assertTrue(runCatching{decodeManagedInvitation(packet().put("ca",value).toString())}.isFailure)
  assertTrue(runCatching{decodeManagedInvitation("x".repeat(65537))}.isFailure)
 }
}
