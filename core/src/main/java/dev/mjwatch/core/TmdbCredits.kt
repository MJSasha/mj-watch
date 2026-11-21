package dev.mjwatch.core

import org.json.JSONArray
import org.json.JSONObject

private val titleIdPattern = Regex("tmdb:(movie|tv):([0-9]+)")
internal val tmdbProfilePath = Regex("/[A-Za-z0-9_-]{1,128}\\.(jpg|png|webp)")

internal fun titleCreditsPath(id: String): String {
    val match = requireNotNull(titleIdPattern.matchEntire(id))
    val (kind, number) = match.destructured
    require((number.toLongOrNull() ?: 0) > 0)
    return "$kind/$number/credits"
}

internal fun personCreditsPath(personId: Long): String {
    require(personId > 0)
    return "person/$personId/combined_credits"
}

internal fun parseCast(response: JSONObject, imageUrl: (String) -> String?): List<CastMember> {
    val cast = response.getJSONArray("cast")
    return (0 until cast.length()).mapNotNull { index ->
        val person = cast.optJSONObject(index) ?: return@mapNotNull null
        val id = person.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
        val name = person.creditText("name").ifEmpty { person.creditText("original_name") }
        if (name.isEmpty()) return@mapNotNull null
        CastMember(id, name, person.creditText("character"), imageUrl(person.creditText("profile_path")))
    }.distinctBy(CastMember::id)
}

private fun JSONObject.creditText(key: String): String =
    if (isNull(key)) "" else optString(key).trim()

internal fun filmographyResults(response: JSONObject): JSONArray {
    val cast = response.getJSONArray("cast")
    val titles = JSONArray()
    val seen = mutableSetOf<Pair<String, Int>>()
    for (index in 0 until cast.length()) {
        val item = cast.optJSONObject(index) ?: continue
        val kind = item.optString("media_type")
        val id = item.optInt("id")
        if ((kind == "movie" || kind == "tv") && id > 0 && !item.optBoolean("adult") && seen.add(kind to id)) {
            titles.put(item)
        }
    }
    return titles
}
