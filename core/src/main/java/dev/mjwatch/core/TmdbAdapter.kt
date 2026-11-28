package dev.mjwatch.core

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
/** TMDB API v3. Requests use catalog queries and TMDB title or person IDs. */
class TmdbAdapter(private val token:String,private val client:OkHttpClient):MetadataAdapter {
    private suspend fun request(path:String,query:String?=null,section:CatalogSection?=null,kind:String?=null):List<Metadata> {
        val url=endpoint("https://api.themoviedb.org").child("3/$path").newBuilder().addQueryParameter("language","ru-RU")
            .apply {if(query!=null) addQueryParameter("query",query).addQueryParameter("include_adult","false")}
            .apply {
                if(section!=null) {
                    addQueryParameter("include_adult","false");addQueryParameter("sort_by","popularity.desc")
                    if(section in setOf(CatalogSection.MOVIES,CatalogSection.SERIES))addQueryParameter("without_genres","16")
                    else addQueryParameter("with_genres","16")
                    if(section==CatalogSection.ANIME)addQueryParameter("with_original_language","ja")
                }
            }.build()
        return parse(fetch(url),kind)
    }
    private suspend fun fetch(url: HttpUrl): String =
        String(client.bytes(Request.Builder().url(url).header("Authorization", "Bearer $token").build()))

    private suspend fun credits(path: String): JSONObject {
        val url = endpoint("https://api.themoviedb.org").child("3/$path").newBuilder()
            .addQueryParameter("language", "ru-RU").build()
        return JSONObject(fetch(url))
    }

    override suspend fun cast(id: String): List<CastMember> =
        parseCast(credits(titleCreditsPath(id))) { path ->
            path.takeIf(tmdbProfilePath::matches)?.let { "https://image.tmdb.org/t/p/w500$it" }
        }

    override suspend fun actorFilmography(personId: Long): List<Metadata> =
        parseItems(filmographyResults(credits(personCreditsPath(personId))))

    override suspend fun search(query:String)=request("search/multi",query)
    override suspend fun home()=request("trending/all/week")
    override suspend fun browse(section:CatalogSection):List<Metadata> = when(section) {
        CatalogSection.FOR_YOU->home()
        CatalogSection.MOVIES->request("discover/movie",section=section,kind="movie")
        CatalogSection.SERIES->request("discover/tv",section=section,kind="tv")
        else->(request("discover/movie",section=section,kind="movie")+request("discover/tv",section=section,kind="tv")).distinctBy {it.id}
    }
    override suspend fun details(id:String)=details(id,false)
    override suspend fun artworkDetails(id:String)=details(id,true)
    private suspend fun details(id:String,artwork:Boolean):Metadata? {
        require(id.matches(Regex("tmdb:(movie|tv):[0-9]+")))
        val (_,kind,number)=id.split(':')
        val url=endpoint("https://api.themoviedb.org").child("3/$kind/$number").newBuilder().addQueryParameter("language","ru-RU")
            .apply {if(artwork) addQueryParameter("append_to_response","images").addQueryParameter("include_image_language","ru,en,null")}.build()
        val item=JSONObject(fetch(url)).put("media_type",kind)
        return parseItems(JSONArray().put(item)).singleOrNull()
    }
    companion object {
        fun parse(raw:String,defaultKind:String?=null):List<Metadata> {
            val results = JSONObject(raw).getJSONArray("results")
            require(results.length() <= 500)
            return parseItems(results, defaultKind)
        }

        private fun parseItems(results: JSONArray, defaultKind: String? = null): List<Metadata> =
            (0 until results.length()).mapNotNull {i -> val item=results.getJSONObject(i);val kind=item.optString("media_type").ifBlank {defaultKind.orEmpty()}
                if(kind !in setOf("movie","tv")) null else {
                    fun image(key:String)=item.optString(key).takeIf {it.startsWith('/')&&it.length<1024}?.let {"https://image.tmdb.org/t/p/${if(key=="backdrop_path") "w1280" else "w500"}$it"}
                    val genreIds=item.optJSONArray("genre_ids")
                    Metadata("tmdb:$kind:${item.getInt("id")}",item.optString(if(kind=="movie") "title" else "name").ifBlank {item.optString(if(kind=="movie") "original_title" else "original_name")},
                        image("poster_path"),item.optString("overview"),kind,item.optString(if(kind=="movie") "release_date" else "first_air_date").take(4).toIntOrNull(),
                        image("backdrop_path"),(0 until (genreIds?.length() ?: 0)).any {genreIds!!.optInt(it)==16},item.optString("original_language").takeIf {it.isNotBlank()}, item.primaryGenre(), item.runtimeMinutes(), tmdbLogo(item) { file ->
                            file.takeIf(tmdbLogoPath::matches)?.let {"https://image.tmdb.org/t/p/w500"+it.replace(".svg",".png")}
                        }, item.optString(if(kind=="movie") "original_title" else "original_name").takeIf {it.isNotBlank()})
                }
            }
    }
}
private val tmdbLogoPath=Regex("/[A-Za-z0-9_-]{1,128}\\.(png|webp|jpg|svg)")
internal fun tmdbLogo(item:JSONObject,resolve:(String)->String?):String? {
    val logos=item.optJSONObject("images")?.optJSONArray("logos") ?: return null
    val candidates=(0 until logos.length()).mapNotNull {logos.optJSONObject(it)}
    for(language in listOf("ru","en","")) {
        for(logo in candidates) {
            val imageLanguage=if(logo.isNull("iso_639_1")) "" else logo.optString("iso_639_1")
            if(imageLanguage==language) resolve(logo.optString("file_path"))?.let {return it}
        }
    }
    return null
}
internal fun JSONObject.primaryGenre(): String? = optJSONArray("genres")?.optJSONObject(0)
    ?.optString("name")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
internal fun JSONObject.runtimeMinutes(): Int? = optInt("runtime").takeIf { it > 0 }

fun Metadata.json():String=JSONObject().put("id",id).put("title",title).put("poster",poster).put("overview",overview)
    .put("kind",kind).put("year",year).put("backdrop",backdrop).put("animation",animation).put("originalLanguage",originalLanguage).put("genre",genre).put("runtimeMinutes",runtimeMinutes).put("logo",logo).put("originalTitle",originalTitle).toString()
fun metadataFromJson(raw:String):Metadata {val j=JSONObject(raw)
    fun optional(key:String)=if(j.isNull(key)) null else j.optString(key)
    return Metadata(j.getString("id"),j.getString("title"),optional("poster"),optional("overview"),j.optString("kind","movie"),if(j.isNull("year")) null else j.getInt("year"),optional("backdrop"),j.optBoolean("animation"),optional("originalLanguage"),optional("genre"),j.optInt("runtimeMinutes").takeIf { it > 0 },optional("logo"),optional("originalTitle"))
}
data class MetadataResult(val items:List<Metadata>,val cached:Boolean)
class MetadataRepository(private val adapter:MetadataAdapter,private val dao:WatchDao,private val namespace:String="") {
    suspend fun details(id:String,withArtwork:Boolean=false):Metadata? {
        if(withArtwork) return titleFacts(id,withArtwork=true)
        val key=if(namespace.isBlank())id else "$namespace:$id"
        dao.metadata(key)?.let {return metadataFromJson(it.json)}
        return adapter.details(id)?.also {dao.put(CachedMetadata(key,it.json(),System.currentTimeMillis()))}
    }
    suspend fun titleFacts(id: String, withArtwork:Boolean=false): Metadata? {
        // Catalogue entries and detail responses have different fields. Keep the richer
        // response when refreshing a shelf replaces a catalogue entry.
        val key = if(withArtwork) "$namespace:artwork:$id" else "$namespace:detail:$id"
        val cached = dao.metadata(key)
        if (cached != null && System.currentTimeMillis() - cached.updatedAt < 86_400_000L) return metadataFromJson(cached.json)
        return try {
            (if(withArtwork) adapter.artworkDetails(id) else adapter.details(id))?.also { dao.put(CachedMetadata(key, it.json(), System.currentTimeMillis())) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached?.let { metadataFromJson(it.json) }
        }
    }
    suspend fun cast(id: String) = adapter.cast(id)
    suspend fun actorFilmography(personId: Long) = adapter.actorFilmography(personId)
    suspend fun search(query:String)=load("${if(namespace.isBlank()) "" else "$namespace:"}search:$query") {adapter.search(query)}
    suspend fun home(force:Boolean=false,section:CatalogSection=CatalogSection.FOR_YOU)=load("${if(namespace.isBlank()) "" else "$namespace:"}home${if(section==CatalogSection.FOR_YOU)"" else ":${section.key}"}",force) {adapter.browse(section)}
    private suspend fun load(query:String,force:Boolean=false,request:suspend ()->List<Metadata>):MetadataResult {
        val row=dao.metadata("query:$query")
        if(!force&&row!=null&&System.currentTimeMillis()-row.updatedAt<86_400_000) return MetadataResult(dao.cachedMetadata(query),true)
        return try {val items=request();dao.cacheMetadata(query,items,System.currentTimeMillis(),namespace);MetadataResult(items,false)}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {val cached=dao.cachedMetadata(query);if(cached.isEmpty()) throw e else MetadataResult(cached,true)}
    }
}
