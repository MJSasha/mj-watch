package dev.mjwatch.core

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
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
    val createdAt: Long
)

@Dao
internal interface OfflineDao {
    @Query("SELECT * FROM downloads WHERE profile = :profile ORDER BY createdAt DESC, id")
    fun observe(profile: String): Flow<List<OfflineRecord>>

    @Query("SELECT * FROM downloads WHERE profile = :profile AND id = :id")
    suspend fun get(profile: String, id: String): OfflineRecord?

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED', 'DOWNLOADING') ORDER BY createdAt, id LIMIT 1")
    suspend fun next(): OfflineRecord?

    @Upsert suspend fun put(record: OfflineRecord)

    @Query("UPDATE downloads SET state = :state, bytesDownloaded = :bytes, error = :error WHERE id = :id")
    suspend fun update(id: String, state: OfflineState, bytes: Long, error: String? = null)

    @Query("UPDATE downloads SET state = 'FAILED', error = :message WHERE state IN ('QUEUED', 'DOWNLOADING')")
    suspend fun interrupt(message: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [OfflineRecord::class], version = 1, exportSchema = false)
internal abstract class OfflineDatabase : RoomDatabase() {
    abstract fun downloads(): OfflineDao
}
