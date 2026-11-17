package dev.mjwatch.core

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val QR_LIMIT=65536
private val qrRandom=SecureRandom()
private fun randomQrBytes(n:Int)=ByteArray(n).also(qrRandom::nextBytes)
private fun qrBase64(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
private fun qrAddress(raw:String):InetAddress {
    require(raw.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")))
    val parts=raw.split('.').map {part->part.toInt().also {require(it in 0..255&&it.toString()==part)}}
    return InetAddress.getByAddress(parts.map {it.toByte()}.toByteArray()).also {require(it.isSiteLocalAddress)}
}
fun tvQrLocalAddress():String=NetworkInterface.getNetworkInterfaces().toList()
    .filter {it.isUp&&!it.isLoopback}.sortedBy {if(it.name.startsWith("wlan"))0 else 1}.flatMap {it.inetAddresses.toList()}
    .filterIsInstance<Inet4Address>().firstOrNull {it.isSiteLocalAddress}?.hostAddress
    ?: throw IOException("Подключите устройство к домашней сети Wi-Fi или Ethernet и обновите QR.")

/** The QR is an out-of-band, one-session authentication key. Never persist or log it. */
class TvQrLink(val ip:String,val port:Int,private val key:ByteArray) {
    init {qrAddress(ip);require(port in 1024..65535&&key.size==32)}
    override fun toString()="TvQrLink(redacted)"
    fun encode()="mjwatch://tv-connect?v=1&ip=$ip&port=$port&key=${qrBase64(key)}"
    internal fun seal(path:String,response:Boolean,json:JSONObject):ByteArray {
        val iv=randomQrBytes(12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv))
        cipher.updateAAD("mjwatch-tv-v1:$path:${if(response)"response" else "request"}".toByteArray(Charsets.UTF_8))
        return iv+cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
    }
    internal fun open(path:String,response:Boolean,bytes:ByteArray):JSONObject {
        require(bytes.size in 29..QR_LIMIT)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        cipher.updateAAD("mjwatch-tv-v1:$path:${if(response)"response" else "request"}".toByteArray(Charsets.UTF_8))
        // Protocol v1 contains only flat string fields. Reject nesting before parsing it.
        return JsonReader(StringReader(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))).use {reader->
            reader.beginObject();val result=JSONObject()
            while(reader.hasNext()) {
                val name=reader.nextName();require(!result.has(name)&&reader.peek()==JsonToken.STRING)
                result.put(name,reader.nextString())
            }
            reader.endObject();require(reader.peek()==JsonToken.END_DOCUMENT);result
        }
    }
    companion object {
        fun decode(raw:String):TvQrLink {
            require(raw.length in 1..512)
            val uri=URI(raw)
            require(uri.scheme=="mjwatch"&&uri.rawAuthority=="tv-connect"&&uri.rawPath.isNullOrEmpty()&&uri.rawFragment==null)
            val fields=uri.rawQuery.orEmpty().split('&').map {it.split('=',limit=2).also {v->require(v.size==2)}}
            require(fields.size==4)
            val values=fields.associate {it[0] to it[1]}
            require(values.keys==setOf("v","ip","port","key")&&values["v"]=="1")
            val key=Base64.getUrlDecoder().decode(values.getValue("key"))
            require(qrBase64(key)==values.getValue("key"))
            return TvQrLink(values.getValue("ip"),values.getValue("port").toInt(),key)
        }
    }
}
class TvQrProposal(val setup:ManagedTvSetup,val account:String,val serverId:String,val userId:String,val nonce:String) {
    override fun toString()="TvQrProposal(redacted)"
}

/** A bounded, temporary LAN inbox. All payloads are AES-GCM authenticated with the QR key.
 * This is separate from ManagedApi: it never receives account passwords or session tokens. */
class TvQrServer(
    ip:String,
    private val onSetup:(TvQrProposal)->Unit,
    private val status:()->JSONObject,
    private val expired:()->Unit={},
    private val cancelled:()->Unit={},
    lifetimeMs:Long=300000
):Closeable {
    private val socket=ServerSocket(0,4,qrAddress(ip)).apply {soTimeout=1000}
    val link=TvQrLink(ip,socket.localPort,randomQrBytes(32))
    private val deadline=android.os.SystemClock.elapsedRealtimeNanos()+TimeUnit.MILLISECONDS.toNanos(lifetimeMs)
    @Volatile private var closed=false
    val active get()=!closed&&android.os.SystemClock.elapsedRealtimeNanos()<deadline
    private var nonce=""
    private var proposal=""
    private val worker=Thread({
        try {
            while(!closed&&android.os.SystemClock.elapsedRealtimeNanos()<deadline) {
                try {socket.accept().use {client->serve(client)}}catch(e:SocketTimeoutException){/* Check expiry. */}
                catch(e:IOException){if(closed)break}
            }
            if(!closed)expired()
        }finally{close()}
    },"mjwatch-tv-qr").apply {isDaemon=true;start()}
    private fun serve(client:Socket) {
        client.soTimeout=3000
        val input=client.getInputStream()
        fun line():String {
            val out=java.io.ByteArrayOutputStream()
            while(true){val c=input.read();if(c<0)throw IOException();if(c==10)break;require(out.size()<1024);out.write(c)}
            return out.toString("US-ASCII").removeSuffix("\r")
        }
        fun respond(code:Int,body:ByteArray=ByteArray(0)) {
            val header="HTTP/1.1 $code Result\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            client.getOutputStream().apply {write(header.toByteArray(Charsets.US_ASCII));write(body);flush()}
        }
        try {
            val request=line().split(' ')
            require(request.size==3&&request[0]=="POST"&&request[1] in setOf("/setup","/status","/cancel")&&request[2]=="HTTP/1.1")
            val path=request[1]
            val headers=mutableMapOf<String,String>();var total=0
            while(true){val row=line();if(row.isEmpty())break;total+=row.length;require(total<=4096&&':' in row);val name=row.substringBefore(':').lowercase();require(name !in headers);headers[name]=row.substringAfter(':').trim()}
            require("transfer-encoding" !in headers)
            val length=headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 29..QR_LIMIT)
            val bytes=ByteArray(length);var offset=0
            while(offset<length){val n=input.read(bytes,offset,length-offset);if(n<0)throw IOException();offset+=n}
            if(closed||android.os.SystemClock.elapsedRealtimeNanos()>=deadline){respond(410);return}
            val body=try{link.open(path,false,bytes)}catch(e:Exception){respond(403);return}
            val incoming=body.getString("nonce");require(incoming.matches(Regex("[A-Za-z0-9_-]{22}")))
            if(path=="/cancel") {
                if(nonce.isNotEmpty()&&nonce!=incoming){respond(404);return}
                respond(200,link.seal(path,true,JSONObject().put("stage","cancelled").put("nonce",incoming)))
                cancelled();return
            }
            if(path=="/setup") {
                if(nonce.isNotEmpty()&&(nonce!=incoming||proposal!=body.toString())){respond(409);return}
                if(nonce.isEmpty()) {
                    val setup=decodeManagedTvSetup(body.getString("setup"))
                    val account=body.getString("account");require(account.isNotBlank()&&account.length<=256)
                    val serverId=body.getString("serverId");val userId=body.getString("userId")
                    require(serverId.matches(Regex("[A-Za-z0-9_-]{1,128}"))&&userId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                    nonce=incoming;proposal=body.toString();onSetup(TvQrProposal(setup,account,serverId,userId,nonce))
                }
            }
            if(nonce!=incoming){respond(404);return}
            respond(200,link.seal(path,true,status().put("nonce",nonce)))
        }catch(e:Exception){runCatching {respond(400)}}
    }
    override fun close(){closed=true;runCatching {socket.close()}}
}

class TvQrPhone(val link:TvQrLink) {
    private val nonce=qrBase64(randomQrBytes(16))
    private val client=OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
        .callTimeout(8,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    override fun toString()="TvQrPhone(redacted)"
    private suspend fun exchange(path:String,body:JSONObject):JSONObject=withContext(Dispatchers.IO) {
        val request=Request.Builder().url("http://${link.ip}:${link.port}$path")
            .post(link.seal(path,false,body.put("nonce",nonce)).toRequestBody("application/octet-stream".toMediaType())).build()
        val result=link.open(path,true,client.bytes(request,QR_LIMIT.toLong()))
        require(result.getString("nonce")==nonce)
        result
    }
    suspend fun send(setup:String,account:String,serverId:String,userId:String)=exchange("/setup",JSONObject().put("setup",setup).put("account",account).put("serverId",serverId).put("userId",userId))
    suspend fun status()=exchange("/status",JSONObject())
    suspend fun cancel()=exchange("/cancel",JSONObject())
}
