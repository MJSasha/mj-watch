package dev.mjwatch.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "library")
data class LibraryEntry(@PrimaryKey val id: String, val title: String, val source: String,
    val openedAt: Long = 0, val bookmarked: Boolean = false, val releaseJson: String = "", val metadataId:String?=null)
@Entity(tableName = "progress")
data class Progress(@PrimaryKey val key: String, val releaseId: String, val fileId: Int, val path: String,
    val positionMs: Long, val durationMs: Long?, val updatedAt: Long, val completed: Boolean)
@Entity(tableName="metadataCache")
data class CachedMetadata(@PrimaryKey val id:String,val json:String,val updatedAt:Long)
@Dao interface WatchDao {
    @Query("SELECT * FROM metadataCache WHERE id=:id") suspend fun metadata(id:String):CachedMetadata?
    @Upsert suspend fun put(entry:CachedMetadata)
    @Transaction suspend fun cacheMetadata(query:String,items:List<Metadata>,now:Long) {
        items.forEach {put(CachedMetadata(it.id,it.json(),now))}
        put(CachedMetadata("query:$query",org.json.JSONArray(items.map {it.id}).toString(),now))
    }
    @Transaction suspend fun cachedMetadata(query:String):List<Metadata> {
        val row=metadata("query:$query") ?: return emptyList()
        val ids=org.json.JSONArray(row.json)
        return (0 until ids.length()).mapNotNull {metadata(ids.getString(it))?.let {metadataFromJson(it.json)}}
    }
    @Query("SELECT * FROM library ORDER BY openedAt DESC") fun library(): Flow<List<LibraryEntry>>
    @Query("SELECT * FROM progress ORDER BY updatedAt DESC") fun progress(): Flow<List<Progress>>
    @Query("SELECT * FROM library WHERE id=:id") suspend fun entry(id: String): LibraryEntry?
    @Transaction suspend fun opened(value:LibraryEntry) {val old=entry(value.id);put(value.copy(bookmarked=old?.bookmarked ?: false,metadataId=value.metadataId ?: old?.metadataId))}
    @Transaction suspend fun toggleBookmark(value:LibraryEntry) {val old=entry(value.id) ?: value;put(old.copy(bookmarked=!old.bookmarked))}
    @Upsert suspend fun put(entry: LibraryEntry)
    @Upsert suspend fun put(progress: Progress)
    @Query("DELETE FROM library WHERE id=:id") suspend fun delete(id: String)
}
@Database(entities = [LibraryEntry::class, Progress::class,CachedMetadata::class], version = 2, exportSchema = false)
abstract class WatchDatabase : RoomDatabase() {
    abstract fun dao(): WatchDao
    companion object {
        val MIGRATION_1_2=object:androidx.room.migration.Migration(1,2) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE library ADD COLUMN metadataId TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS metadataCache (id TEXT NOT NULL, json TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
    }
}
private val Context.configStore by preferencesDataStore("config", corruptionHandler=androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {emptyPreferences()})
class Settings(private val context: Context) {
    val destination = context.configStore.data.map {it[stringPreferencesKey("destination")] ?: "home"}.distinctUntilChanged()
    suspend fun destination(value:String) {require(value in setOf("home","search","library","settings"));context.configStore.edit {it[stringPreferencesKey("destination")]=value}}
    val config = context.configStore.data.map { decodeConfig(it[stringPreferencesKey("json")] ?: "{}") }.distinctUntilChanged()
    suspend fun save(config: Config) { context.configStore.edit { it[stringPreferencesKey("json")] = encodeConfig(config) } }
}
fun encodeConfig(c: Config): String {
    listOf(c.jackett,c.torrServer).filter {it.isNotBlank()}.forEach {endpoint(it)}
    val indexers=org.json.JSONArray()
    c.indexers.split(',').map {it.trim()}.filter {it.isNotBlank()}.forEach {id ->
        val path=c.torznabPaths[id] ?: "/api/v2.0/indexers/$id/results/torznab/api"
        validateTorznabPath(path)
        indexers.put(JSONObject().put("id",id).put("torznabPath",path).put("enabled",true))
    }
    return JSONObject().put("schemaVersion",1).put("demo",c.demo)
        .put("jackett",JSONObject().put("baseUrl",c.jackett).put("credentialRef","jackett-api-key").put("indexers",indexers))
        .put("torrServer",JSONObject().put("baseUrl",c.torrServer).put("credentialRef","torrserver-auth").put("saveToDatabase",false))
        .put("metadata",JSONObject().put("provider",c.metadataProvider).put("language","ru-RU").put("credentialRef","metadata-api-token"))
        .put("network",JSONObject().put("localHttpOptIn",c.localHttp).put("allowedLocalHttpOrigins",org.json.JSONArray(listOf(c.jackett,c.torrServer).filter {it.startsWith("http://")}.map {endpoint(it).origin()})))
        .put("appearance",JSONObject().put("theme",c.theme).put("motion","system")).toString(2)
}
fun decodeConfig(raw: String): Config {
    val j=JSONObject(raw);require(j.optInt("schemaVersion",1)==1) {"Неизвестная версия конфигурации"}
    val jackett=j.optJSONObject("jackett");val server=j.optJSONObject("torrServer")
    val sources=jackett?.optJSONArray("indexers") ?: org.json.JSONArray();val paths=mutableMapOf<String,String>()
    for(i in 0 until sources.length()) {val source=sources.getJSONObject(i);if(source.optBoolean("enabled",true)) {
        val id=source.getString("id");val path=source.optString("torznabPath","/api/v2.0/indexers/$id/results/torznab/api")
        require(Regex("[A-Za-z0-9_-]+").matches(id));validateTorznabPath(path);paths[id]=path
    }}
    // Read the early local flat schema too; all new exports follow the handoff's nested schema.
    return Config(schemaVersion=1,jackett=jackett?.optString("baseUrl") ?: j.optString("jackett"),
        indexers=if(jackett!=null) paths.keys.joinToString(",") else j.optString("indexers"),
        torrServer=server?.optString("baseUrl") ?: j.optString("torrServer"),
        localHttp=j.optJSONObject("network")?.optBoolean("localHttpOptIn") ?: j.optBoolean("localHttp"),
        demo=j.optBoolean("demo"),theme=j.optJSONObject("appearance")?.optString("theme","system") ?: j.optString("theme","system"),
        metadataProvider=j.optJSONObject("metadata")?.optString("provider","none") ?: j.optString("metadataProvider","none"),torznabPaths=paths)
}

fun Release.json(): String = JSONObject().put("id", id).put("title", title).put("source", source)
    .put("hash", hash).put("magnet", magnet).put("download", download).put("size", size).put("seeds", seeds).toString()
fun releaseFromJson(raw: String): Release { val j=JSONObject(raw)
    fun optional(k: String) = if (j.isNull(k)) null else j.optString(k).takeIf { it.isNotBlank() }
    return Release(j.getString("id"), j.getString("title"), j.getString("source"), optional("hash"), optional("magnet"), optional("download"),
        if(j.isNull("size")) null else j.getLong("size"), if(j.isNull("seeds")) null else j.getInt("seeds"))
}
/** All sensitive URLs (including tracker passkeys) stay encrypted; backups are disabled in both APKs. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("mjwatch", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mjwatch", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun put(name: String, value: String) {
        val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        check(prefs.edit().putString(name, Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP)).commit()) {"Не удалось сохранить секрет"}
    }
    fun get(name: String): String = runCatching {prefs.getString(name, null)?.let {
        val bytes=Base64.decode(it, Base64.NO_WRAP); val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        String(c.doFinal(bytes.copyOfRange(12, bytes.size)))
    } ?: ""}.getOrElse {prefs.edit().remove(name).apply();""}
}
