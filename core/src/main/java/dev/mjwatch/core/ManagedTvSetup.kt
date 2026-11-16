package dev.mjwatch.core

import org.json.JSONObject
import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader

/** Public connection settings only. Import never grants trust or account access. */
class ManagedTvSetup(val url:String,val ca:String) {
    override fun toString()="ManagedTvSetup(redacted)"
}
fun decodeManagedTvSetup(raw:String):ManagedTvSetup {
    require(raw.length in 1..65536)
    var type="";var schema=0;var address="";var ca=""
    JsonReader(StringReader(raw)).use {reader->
        reader.beginObject();val seen=mutableSetOf<String>()
        while(reader.hasNext()) {
            val name=reader.nextName();require(seen.add(name))
            if(name=="schema"){require(reader.peek()==JsonToken.NUMBER);schema=reader.nextInt()}
            else {
                require(name in setOf("type","url","ca")&&reader.peek()==JsonToken.STRING)
                val value=reader.nextString()
                when(name){"type"->type=value;"url"->address=value;"ca"->ca=value}
            }
        }
        reader.endObject();require(reader.peek()==JsonToken.END_DOCUMENT)
    }
    require(type=="mjwatch-tv"&&schema==1)
    val url=endpoint(address);require(url.scheme=="https")
    ca=normalizeManagedCa(ca)
    if(ca.isNotBlank())managedCaFingerprint(ca)
    return ManagedTvSetup(url.toString(),ca)
}
fun encodeManagedTvSetup(url:String,ca:String):String {
    val raw=JSONObject().put("type","mjwatch-tv").put("schema",1).put("url",url).put("ca",ca).toString()
    val checked=decodeManagedTvSetup(raw)
    return JSONObject().put("type","mjwatch-tv").put("schema",1).put("url",checked.url).put("ca",checked.ca).toString()
}
