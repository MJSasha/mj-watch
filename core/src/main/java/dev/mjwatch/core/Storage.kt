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
    val openedAt: Long = 0, val bookmarked: Boolean = false, val releaseJson: String = "")
@Entity(tableName = "progress")
data class Progress(@PrimaryKey val key: String, val releaseId: String, val fileId: Int, val path: String,
    val positionMs: Long, val durationMs: Long?, val updatedAt: Long, val completed: Boolean)
@Dao interface WatchDao {
    @Query("SELECT * FROM library ORDER BY openedAt DESC") fun library(): Flow<List<LibraryEntry>>
    @Query("SELECT * FROM progress ORDER BY updatedAt DESC") fun progress(): Flow<List<Progress>>
    @Query("SELECT * FROM library WHERE id=:id") suspend fun entry(id: String): LibraryEntry?
    @Upsert suspend fun put(entry: LibraryEntry)
    @Upsert suspend fun put(progress: Progress)
    @Query("DELETE FROM library WHERE id=:id") suspend fun delete(id: String)
}
@Database(entities = [LibraryEntry::class, Progress::class], version = 1, exportSchema = false)
abstract class WatchDatabase : RoomDatabase() { abstract fun dao(): WatchDao }
private val Context.configStore by preferencesDataStore("config")
class Settings(private val context: Context) {
    val config = context.configStore.data.map { decodeConfig(it[stringPreferencesKey("json")] ?: "{}") }
    suspend fun save(config: Config) { context.configStore.edit { it[stringPreferencesKey("json")] = encodeConfig(config) } }
}
fun encodeConfig(c: Config): String { listOf(c.jackett,c.torrServer).filter {it.isNotBlank()}.forEach {endpoint(it)}; return JSONObject().put("schemaVersion", c.schemaVersion).put("jackett", c.jackett)
    .put("indexers", c.indexers).put("torrServer", c.torrServer).put("localHttp", c.localHttp)
    .put("demo", c.demo).put("theme", c.theme).put("metadataProvider", c.metadataProvider).toString(2)
}
fun decodeConfig(raw: String): Config {
    val j = JSONObject(raw); require(j.optInt("schemaVersion", 1) == 1) { "Неизвестная версия конфигурации" }
    return Config(j.optInt("schemaVersion", 1), j.optString("jackett"), j.optString("indexers"),
        j.optString("torrServer"), j.optBoolean("localHttp"), j.optBoolean("demo"), j.optString("theme", "system"), j.optString("metadataProvider", "none"))
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
        prefs.edit().putString(name, Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP)).commit()
    }
    fun get(name: String): String = prefs.getString(name, null)?.let {
        val bytes=Base64.decode(it, Base64.NO_WRAP); val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        String(c.doFinal(bytes.copyOfRange(12, bytes.size)))
    } ?: ""
}
