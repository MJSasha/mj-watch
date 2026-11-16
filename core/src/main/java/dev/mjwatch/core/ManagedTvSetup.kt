package dev.mjwatch.core

import org.json.JSONObject

/** Public connection settings only. Import never grants trust or account access. */
class ManagedTvSetup(val url:String,val ca:String) {
    override fun toString()="ManagedTvSetup(redacted)"
}
fun decodeManagedTvSetup(raw:String):ManagedTvSetup {
    require(raw.length in 1..65536)
    val json=JSONObject(raw)
    require(json.getString("type")=="mjwatch-tv"&&json.getInt("schema")==1)
    val url=endpoint(json.getString("url"));require(url.scheme=="https")
    val ca=normalizeManagedCa(json.optString("ca"))
    if(ca.isNotBlank())managedCaFingerprint(ca)
    return ManagedTvSetup(url.toString(),ca)
}
fun encodeManagedTvSetup(url:String,ca:String):String {
    val raw=JSONObject().put("type","mjwatch-tv").put("schema",1).put("url",url).put("ca",ca).toString()
    val checked=decodeManagedTvSetup(raw)
    return JSONObject().put("type","mjwatch-tv").put("schema",1).put("url",checked.url).put("ca",checked.ca).toString()
}
