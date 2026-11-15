package dev.mjwatch.core

import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Decoding never changes trust or sends the single-use token over the network. */
class ManagedInvitation(val url:String,val invite:String,val ca:String) {
 override fun toString()="ManagedInvitation(redacted)"
}
fun normalizeManagedCa(raw:String)=raw.trim().replace("\\r\\n","\n").replace("\\n","\n").replace("\r\n","\n").trim()
fun managedCaFingerprint(raw:String):String {
 val pem=normalizeManagedCa(raw)
 require(pem.length in 100..32768&&pem.startsWith("-----BEGIN CERTIFICATE-----")&&pem.endsWith("-----END CERTIFICATE-----"))
 val cert=CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
 require(cert.basicConstraints>=0)
 return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":"){"%02X".format(it)}
}
fun decodeManagedInvitation(raw:String):ManagedInvitation {
 require(raw.length in 1..65536)
 val json=JSONObject(raw)
 val url=endpoint(json.getString("url"));require(url.scheme=="https")
 val token=json.getString("invite");require(token.matches(Regex("[A-Za-z0-9_-]{16,512}")))
 val ca=normalizeManagedCa(json.getString("ca"));managedCaFingerprint(ca)
 return ManagedInvitation(url.toString(),token,ca)
}
