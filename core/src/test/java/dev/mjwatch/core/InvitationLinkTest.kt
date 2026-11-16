package dev.mjwatch.core

import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[35],manifest=org.robolectric.annotation.Config.NONE)
class InvitationLinkTest {
    private val ca get()=javaClass.getResource("/invitation-ca.pem")!!.readText()
    private val token="fixture-invitation-token"
    @Test fun shortLinkPreservesOriginAndTokenWithoutEmbeddingCertificate() {
        val link=invitationLink("https://fixture.invalid:8443",token,ca)
        assertTrue(link.length<260);assertFalse(link.contains("CERTIFICATE"));assertFalse(link.contains("{"))
        val invitation=decodeInvitationLink(link)
        assertEquals("https://fixture.invalid:8443/",invitation.url);assertEquals(token,invitation.token)
        assertEquals(managedCaFingerprint(ca).replace(":","").lowercase(),invitation.pin)
        assertFalse(invitation.toString().contains(token))
        val fragment=android.net.Uri.parse(link).encodedFragment!!
        val custom="mjwatch://join/"+Base64.encodeToString(fragment.toByteArray(),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        assertEquals(invitation.pin,decodeInvitationLink(custom).pin)
        assertEquals(token,decodeInvitationLink(custom).token)
    }
    @Test fun malformedAndForeignLinksCannotImportTrustOrOverrideParameters() {
        val valid=invitationLink("https://fixture.invalid:8443",token,ca)
        for(link in listOf(valid.replace(INVITATION_HOST,"evil.invalid"),valid.replace("https://mjsasha","http://mjsasha"),valid.replace("/join/","/join/extra/"),valid+"&s=https%3A%2F%2Fevil.invalid",valid+"&unknown=1",valid.replace("v=1","v=2"),valid.replace("p=","p=not-a-pin"),"mjwatch://join/???","x".repeat(8193)))assertTrue(link.take(80),runCatching{decodeInvitationLink(link)}.isFailure)
        for(address in listOf("http://fixture.invalid","https://user:pass@fixture.invalid","https://fixture.invalid/?token=secret"))assertTrue(runCatching{invitationLink(address,token,ca)}.isFailure)
    }
    @Test fun onlyThePinnedPublicCaCanAuthenticateBootstrap() {
        val cert=CertificateFactory.getInstance("X.509").generateCertificate(ca.byteInputStream()) as X509Certificate
        val pin=managedCaFingerprint(ca).replace(":","").lowercase()
        val trust=InvitationTrust(pin)
        trust.checkServerTrusted(arrayOf(cert),"RSA")
        assertEquals(cert,trust.verifiedCa)
        assertTrue(runCatching{InvitationTrust("0".repeat(64)).checkServerTrusted(arrayOf(cert),"RSA")}.isFailure)
        assertTrue(runCatching{InvitationTrust(pin).checkServerTrusted(emptyArray(),"RSA")}.isFailure)
        assertTrue(runCatching{InvitationTrust(pin).checkClientTrusted(arrayOf(cert),"RSA")}.isFailure)
    }
}
