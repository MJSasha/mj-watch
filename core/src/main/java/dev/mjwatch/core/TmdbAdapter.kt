package dev.mjwatch.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
/** TMDB API v3, optional, receives only the user's title query. Never receives release links. */
class TmdbAdapter(private val token:String,private val client:OkHttpClient): MetadataAdapter {
    override suspend fun search(query:String):List<Metadata> {
        val url=endpoint("https://api.themoviedb.org").child("3/search/multi").newBuilder()
            .addQueryParameter("query",query).addQueryParameter("language","ru-RU").addQueryParameter("include_adult","false").build()
        val j=JSONObject(String(client.bytes(Request.Builder().url(url).header("Authorization","Bearer $token").build())))
        val results=j.getJSONArray("results")
        return (0 until results.length()).mapNotNull { i -> val item=results.getJSONObject(i); val type=item.optString("media_type")
            if(type !in setOf("movie","tv")) null else Metadata("tmdb:$type:${item.getInt("id")}",
                item.optString(if(type=="movie") "title" else "name").ifBlank {item.optString(if(type=="movie") "original_title" else "original_name")},
                item.optString("poster_path").takeIf {it.startsWith('/') }?.let {"https://image.tmdb.org/t/p/w500$it"},item.optString("overview"))
        }
    }
}
