package dev.mjwatch.core

import org.json.JSONObject

/** JacRed Jackett-compatible JSON endpoint; does not require a Torznab caps request. */
object JacredJson {
    fun releases(raw:String,source:String):List<Release> {
        val root=try {JSONObject(raw)} catch(_:Exception) {error("Ожидался JacRed JSON, получен другой ответ сервера")}
        val items=root.optJSONArray("Results") ?: root.optJSONArray("results") ?: error("В ответе нет Results. Проверьте API JacRed")
        require(items.length()<=500) {"Ответ JacRed превышает лимит 500 раздач"}
        return (0 until items.length()).mapNotNull {index ->
            val item=items.getJSONObject(index)
            val fields=item.keys().asSequence().associateBy {it.lowercase()}
            fun string(name:String)=fields[name]?.let {item.optString(it).takeUnless {v->v.isBlank()||v=="null"}}
            fun number(name:String)=fields[name]?.takeUnless {item.isNull(it)}?.let {item.optDouble(it,Double.NaN).takeIf {n->n.isFinite()&&n>=0}}
            val title=string("title") ?: return@mapNotNull null
            val magnet=string("magneturi") ?: return@mapNotNull null
            if(runCatching {validateMagnet(magnet)}.isFailure) return@mapNotNull null
            val hash=magnet.substringAfter('?').split('&').map {java.net.URLDecoder.decode(it,"UTF-8")}
                .firstOrNull {it.startsWith("xt=urn:btih:")&&it.substringAfterLast(':').matches(Regex("(?i)[0-9a-f]{40}"))}?.substringAfterLast(':')?.lowercase()
            val tracker=string("tracker") ?: source
            val identity=hash ?: magnet
            val id=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") {"%02x".format(it)}
            Release("jacred:$source:$id",title,tracker,hash,magnet,size=number("size")?.toLong(),seeds=number("seeders")?.takeIf {it<=Int.MAX_VALUE}?.toInt(),
                categories=releaseCategories((fields["categories"] ?: fields["category"])?.let(item::opt)))
        }.distinctBy {it.hash ?: it.id}
    }
}
