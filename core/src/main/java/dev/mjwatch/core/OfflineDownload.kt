package dev.mjwatch.core

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class OfflineState { QUEUED, DOWNLOADING, READY, FAILED, CANCELLED }

data class OfflineDownload(
    val id: String,
    val releaseId: String,
    val title: String,
    val metadataId: String?,
    val fileId: Int,
    val path: String,
    val contentKey: String,
    val season: Int?,
    val episode: Int?,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val state: OfflineState,
    val error: String?
)

// Keep the source coordinates, never a stream URL, release magnet or authorization header.
@Entity(tableName = "downloads", primaryKeys = ["id"])
internal data class OfflineRecord(
    @Embedded val item: OfflineDownload,
    val profile: String,
    val source: String,
    val handle: String,
    val createdAt: Long,
    val posterPath: String? = null,
    val segments: String? = null
)

@Dao
internal interface OfflineDao {
    @Query("SELECT * FROM downloads WHERE profile = :profile ORDER BY createdAt DESC, id")
    fun observe(profile: String): Flow<List<OfflineRecord>>

    @Query("SELECT * FROM downloads WHERE profile = :profile AND id = :id")
    suspend fun get(profile: String, id: String): OfflineRecord?

    @Query("SELECT * FROM downloads WHERE profile = :profile AND state IN ('QUEUED', 'DOWNLOADING') ORDER BY createdAt, season, episode, path, id LIMIT 1")
    suspend fun next(profile: String): OfflineRecord?

    @Upsert suspend fun put(record: OfflineRecord)

    @Upsert suspend fun put(records: List<OfflineRecord>)

    @Query("UPDATE downloads SET state = 'READY', bytesDownloaded = totalBytes, error = NULL, segments = :segments WHERE id = :id")
    suspend fun ready(id: String, segments: String?)

    @Query("UPDATE downloads SET state = :state, bytesDownloaded = :bytes, error = :error WHERE id = :id")
    suspend fun update(id: String, state: OfflineState, bytes: Long, error: String? = null)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [OfflineRecord::class], version = 2, exportSchema = false)
internal abstract class OfflineDatabase : RoomDatabase() {
    abstract fun downloads(): OfflineDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN posterPath TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN segments TEXT")
            }
        }
    }
}
