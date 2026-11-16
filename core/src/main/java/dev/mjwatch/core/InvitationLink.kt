package dev.mjwatch.core

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

const val INVITATION_HOST="mjsasha.github.io"
private const val INVITATION_PATH="/mj-watch/join/"
class InvitationLink(val url:String,val token:String,val pin:String) {
    override fun toString()="InvitationLink(redacted)"
}
private fun certificatePin(cert:X509Certificate)=MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(""){"%02x".format(it)}
fun invitationLink(url:String,token:String,ca:String):String {
    val pin=if(ca.isBlank())"" else managedCaFingerprint(ca).replace(":","").lowercase()
    val fields=listOf("v" to "1","s" to url,"i" to token,"p" to pin)
    val fragment=fields.joinToString("&"){(key,value)->"$key=${URLEncoder.encode(value,"UTF-8")}"}
    val link="https://$INVITATION_HOST$INVITATION_PATH".toHttpUrl().newBuilder().encodedFragment(fragment).build().toString()
    decodeInvitationLink(link)
    return link
}
fun decodeInvitationLink(raw:String):InvitationLink {
    require(raw.length in 1..8192)
    val uri=android.net.Uri.parse(raw)
    val fragment=when(uri.scheme) {
        "https"->{require(uri.host==INVITATION_HOST&&uri.port==-1&&uri.encodedPath==INVITATION_PATH&&uri.encodedQuery==null&&uri.userInfo==null);uri.encodedFragment.orEmpty()}
        "mjwatch"->{require(uri.host=="join"&&uri.encodedQuery==null&&uri.encodedFragment==null&&uri.port==-1&&uri.userInfo==null&&uri.pathSegments.size==1)
            val payload=uri.pathSegments.single();require(payload.matches(Regex("[A-Za-z0-9_-]{1,6000}")))
            String(Base64.decode(payload,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),Charsets.UTF_8)}
        else->throw IllegalArgumentException("Invalid invitation link")
    }
    require(fragment.length in 1..4096)
    val fields=linkedMapOf<String,String>()
    fragment.split('&').forEach {part->val key=part.substringBefore('=');require(key in setOf("v","s","i","p")&&part.contains('=')&&!fields.containsKey(key));fields[key]=URLDecoder.decode(part.substringAfter('='),"UTF-8")}
    require(fields.keys==setOf("v","s","i","p")&&fields["v"]=="1")
    val url=endpoint(fields.getValue("s"));require(url.scheme=="https"&&url.toString().length<=2048)
    val token=fields.getValue("i");require(token.matches(Regex("[A-Za-z0-9_-]{16,512}")))
    val pin=fields.getValue("p");require(pin.isEmpty()||pin.matches(Regex("[a-f0-9]{64}")))
    return InvitationLink(url.toString(),token,pin)
}

/** The link authenticates one CA, never a trust-all TLS connection. Hostname checks stay enabled. */
internal class InvitationTrust(private val pin:String):X509TrustManager {
    @Volatile var verifiedCa:X509Certificate?=null
        private set
    override fun getAcceptedIssuers()=verifiedCa?.let {arrayOf(it)} ?: emptyArray()
    override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String)=throw CertificateException("Client certificates not supported")
    override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
        if(chain.size !in 1..8)throw CertificateException("Invalid certificate chain")
        val ca=chain.firstOrNull {it.basicConstraints>=0&&certificatePin(it)==pin} ?: throw CertificateException("Invitation CA mismatch; update MJWatch server")
        ca.checkValidity()
        val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply {load(null);setCertificateEntry("invited-server",ca)}
        val factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(store)}
        factory.trustManagers.filterIsInstance<X509TrustManager>().single().checkServerTrusted(chain,authType)
        verifiedCa=ca
    }
}
suspend fun invitationCa(link:InvitationLink,storedCa:String):String {
    if(link.pin.isEmpty()) {check(storedCa.isBlank()){ "Ссылка не совпадает с сохранённым сертификатом сервера." };return ""}
    if(storedCa.isNotBlank()) {
        check(managedCaFingerprint(storedCa).replace(":","").lowercase()==link.pin){"Ссылка не совпадает с сохранённым сертификатом сервера. Попросите владельца проверить приглашение."}
        return storedCa
    }
    val trust=InvitationTrust(link.pin)
    val ssl=SSLContext.getInstance("TLS").apply {init(null,arrayOf(trust),null)}
    val c=Config(connectionMode="managed",managedUrl=link.url)
    val client=guardedClient(c.copy(jackett="")).newBuilder().sslSocketFactory(ssl.socketFactory,trust).build()
    // No invitation, account token or password is sent by this bootstrap request.
    client.newCall(Request.Builder().url(endpoint(link.url).child("api/v1/capabilities")).get().build()).execute().use {response->
        check(!response.isRedirect){"Сервер перенаправляет запрос. Попросите владельца проверить адрес."}
    }
    val cert=trust.verifiedCa ?: error("Не удалось проверить сертификат приглашения")
    return "-----BEGIN CERTIFICATE-----\n"+Base64.encodeToString(cert.encoded,Base64.NO_WRAP).chunked(64).joinToString("\n")+"\n-----END CERTIFICATE-----"
}
