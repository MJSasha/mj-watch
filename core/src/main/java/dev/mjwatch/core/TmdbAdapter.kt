package dev.mjwatch.core

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
/** TMDB API v3. Receives only title queries; no release URLs or tracker credentials. */
class TmdbAdapter(private val token:String,private val client:OkHttpClient):MetadataAdapter {
    private suspend fun request(path:String,query:String?=null):List<Metadata> {
        val url=endpoint("https://api.themoviedb.org").child("3/$path").newBuilder().addQueryParameter("language","ru-RU")
            .apply {if(query!=null) addQueryParameter("query",query).addQueryParameter("include_adult","false")}.build()
        return parse(String(client.bytes(Request.Builder().url(url).header("Authorization","Bearer $token").build())))
    }
    override suspend fun search(query:String)=request("search/multi",query)
    override suspend fun home()=request("trending/all/week")
    override suspend fun details(id:String):Metadata? {
        require(id.matches(Regex("tmdb:(movie|tv):[0-9]+")))
        val (_,kind,number)=id.split(':')
        val url=endpoint("https://api.themoviedb.org").child("3/$kind/$number").newBuilder().addQueryParameter("language","ru-RU").build()
        val item=JSONObject(String(client.bytes(Request.Builder().url(url).header("Authorization","Bearer $token").build()))).put("media_type",kind)
        return parse(JSONObject().put("results",org.json.JSONArray().put(item)).toString()).singleOrNull()
    }
    companion object {
        fun parse(raw:String):List<Metadata> {
            val results=JSONObject(raw).getJSONArray("results");require(results.length()<=500)
            return (0 until results.length()).mapNotNull {i -> val item=results.getJSONObject(i);val kind=item.optString("media_type")
                if(kind !in setOf("movie","tv")) null else {
                    fun image(key:String)=item.optString(key).takeIf {it.startsWith('/')&&it.length<1024}?.let {"https://image.tmdb.org/t/p/${if(key=="backdrop_path") "w1280" else "w500"}$it"}
                    val genreIds=item.optJSONArray("genre_ids")
                    Metadata("tmdb:$kind:${item.getInt("id")}",item.optString(if(kind=="movie") "title" else "name").ifBlank {item.optString(if(kind=="movie") "original_title" else "original_name")},
                        image("poster_path"),item.optString("overview"),kind,item.optString(if(kind=="movie") "release_date" else "first_air_date").take(4).toIntOrNull(),
                        image("backdrop_path"),(0 until (genreIds?.length() ?: 0)).any {genreIds!!.optInt(it)==16},item.optString("original_language").takeIf {it.isNotBlank()})
                }
            }
        }
    }
}
fun Metadata.json():String=JSONObject().put("id",id).put("title",title).put("poster",poster).put("overview",overview)
    .put("kind",kind).put("year",year).put("backdrop",backdrop).put("animation",animation).put("originalLanguage",originalLanguage).toString()
fun metadataFromJson(raw:String):Metadata {val j=JSONObject(raw)
    fun optional(key:String)=if(j.isNull(key)) null else j.optString(key)
    return Metadata(j.getString("id"),j.getString("title"),optional("poster"),optional("overview"),j.optString("kind","movie"),if(j.isNull("year")) null else j.getInt("year"),optional("backdrop"),j.optBoolean("animation"),optional("originalLanguage"))
}
data class MetadataResult(val items:List<Metadata>,val cached:Boolean)
class MetadataRepository(private val adapter:MetadataAdapter,private val dao:WatchDao,private val namespace:String="") {
    suspend fun details(id:String):Metadata? {
        val key=if(namespace.isBlank())id else "$namespace:$id"
        dao.metadata(key)?.let {return metadataFromJson(it.json)}
        return adapter.details(id)?.also {dao.put(CachedMetadata(key,it.json(),System.currentTimeMillis()))}
    }
    suspend fun search(query:String)=load("${if(namespace.isBlank()) "" else "$namespace:"}search:$query") {adapter.search(query)}
    suspend fun home(force:Boolean=false)=load("${if(namespace.isBlank()) "" else "$namespace:"}home",force) {adapter.home()}
    private suspend fun load(query:String,force:Boolean=false,request:suspend ()->List<Metadata>):MetadataResult {
        val row=dao.metadata("query:$query")
        if(!force&&row!=null&&System.currentTimeMillis()-row.updatedAt<86_400_000) return MetadataResult(dao.cachedMetadata(query),true)
        return try {val items=request();dao.cacheMetadata(query,items,System.currentTimeMillis(),namespace);MetadataResult(items,false)}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {val cached=dao.cachedMetadata(query);if(cached.isEmpty()) throw e else MetadataResult(cached,true)}
    }
}
