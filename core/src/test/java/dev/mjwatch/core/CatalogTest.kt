package dev.mjwatch.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[35],manifest=org.robolectric.annotation.Config.NONE)
class CatalogTest {
    @Test fun everyCategoryLoadsItsOwnSourceAndAssignsMediaType()=runBlocking {
        val requests=mutableListOf<Request>()
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request();requests+=request
            val tv=request.url.encodedPath.endsWith("/tv")
            val animation=request.url.queryParameter("with_genres")=="16"
            val raw="""{"results":[{"id":1,"title":"Film","name":"Series","genre_ids":[${if(animation)16 else 18}],"original_language":"${if(request.url.queryParameter("with_original_language")=="ja")"ja" else "en"}"${if(request.url.encodedPath.contains("trending"))",\"media_type\":\"movie\"" else ""}}]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(raw.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val adapter=TmdbAdapter("test-secret",client)
        for(section in CatalogSection.entries) {
            requests.clear()
            val items=adapter.browse(section)
            assertTrue(section.label,items.isNotEmpty())
            assertTrue(section.label,items.all(section::matches))
            assertEquals(if(section in listOf(CatalogSection.ANIME,CatalogSection.CARTOONS))2 else 1,requests.size)
            assertTrue(requests.all {it.header("Authorization")=="Bearer test-secret"&&!it.url.toString().contains("test-secret")})
            when(section) {
                CatalogSection.MOVIES->assertEquals("/3/discover/movie",requests.single().url.encodedPath)
                CatalogSection.SERIES->assertEquals("/3/discover/tv",requests.single().url.encodedPath)
                CatalogSection.ANIME->assertTrue(requests.all {it.url.queryParameter("with_original_language")=="ja"})
                else->Unit
            }
        }
    }
    @Test fun categoryCachesDoNotOverwriteEachOtherAndWorkOffline()=runBlocking {
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,WatchDatabase::class.java).allowMainThreadQueries().build()
        var offline=false;var calls=0
        val adapter=object:MetadataAdapter {
            override suspend fun search(query:String)=emptyList<Metadata>()
            override suspend fun browse(section:CatalogSection):List<Metadata> {calls++;check(!offline);return listOf(Metadata(section.key,section.label,null,null))}
        }
        try {
            val repo=MetadataRepository(adapter,db.dao(),"account")
            assertEquals("series",repo.home(section=CatalogSection.SERIES).items.single().id)
            assertEquals("anime",repo.home(section=CatalogSection.ANIME).items.single().id)
            offline=true
            val cached=repo.home(force=true,section=CatalogSection.SERIES)
            assertTrue(cached.cached);assertEquals("series",cached.items.single().id)
            assertEquals("anime",repo.home(section=CatalogSection.ANIME).items.single().id)
            assertEquals(3,calls)
        }finally {db.close()}
    }
}
