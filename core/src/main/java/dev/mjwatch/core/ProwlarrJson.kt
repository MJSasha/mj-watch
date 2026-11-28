package dev.mjwatch.core

import org.json.JSONObject

internal fun prowlarrReleases(data: JSONObject): List<Release> {
    val rows = data.getJSONArray("Results")
    require(rows.length() <= 500) { "Ответ Prowlarr превышает лимит 500 раздач" }
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.getJSONObject(index)
        val magnet = row.optString("magnet").takeIf { runCatching { validateMagnet(it) }.isSuccess }
        val download = row.optString("download").takeIf { it.isNotBlank() }?.let { "prowlarr:$it" }
        val token = magnet ?: download ?: return@mapNotNull null
        val hash = row.optString("hash").takeIf { it.matches(Regex("(?i)[0-9a-f]{40}")) }?.lowercase()
        val id = java.security.MessageDigest.getInstance("SHA-256").digest((hash ?: row.optString("id").ifBlank { token }).toByteArray())
            .joinToString("") { "%02x".format(it) }
        Release("prowlarr:$id", row.getString("title"), row.optString("tracker").ifBlank { "Prowlarr" },
            hash, magnet, download, row.optLong("size", -1).takeIf { it >= 0 },
            row.optInt("seeds", -1).takeIf { it >= 0 }, releaseCategories(row.opt("categories")))
    }.distinctBy { it.hash ?: it.id }
}

internal fun releaseCategories(value: Any?): Set<Int> = when (value) {
    is org.json.JSONArray -> (0 until value.length()).mapNotNullTo(mutableSetOf()) { index ->
        when (val entry = value.opt(index)) {
            is JSONObject -> entry.optString("id").toIntOrNull()
            else -> entry?.toString()?.toIntOrNull()
        }
    }
    else -> setOfNotNull(value?.toString()?.toIntOrNull())
}
