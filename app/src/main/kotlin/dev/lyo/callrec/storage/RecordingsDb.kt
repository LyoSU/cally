// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lyo.callrec.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "calls")
data class CallRecord(
    @PrimaryKey val callId: String,
    @ColumnInfo("started_at") val startedAt: Long,
    @ColumnInfo("ended_at") val endedAt: Long?,
    @ColumnInfo("contact_number") val contactNumber: String?,
    @ColumnInfo("contact_name") val contactName: String?,
    /** "dual", "single_voicecall_mono", "single_voicecall_stereo", "single_mic" */
    @ColumnInfo("mode") val mode: String,
    /** Absolute path of the uplink half (or the single-stream file). */
    @ColumnInfo("uplink_path") val uplinkPath: String,
    /** Optional downlink path, only set for [mode] == "dual". */
    @ColumnInfo("downlink_path") val downlinkPath: String?,
    @ColumnInfo("notes") val notes: String? = null,
    @ColumnInfo("favorite") val favorite: Boolean = false,
    @ColumnInfo("transcript") val transcript: String? = null,
)

@Dao
interface CallDao {
    @Query("SELECT * FROM calls ORDER BY started_at DESC")
    fun observeAll(): Flow<List<CallRecord>>

    @Query("SELECT * FROM calls WHERE callId = :id LIMIT 1")
    suspend fun byId(id: String): CallRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rec: CallRecord)

    @Query("UPDATE calls SET ended_at = :endedAt WHERE callId = :id")
    suspend fun markEnded(id: String, endedAt: Long)

    @Query("UPDATE calls SET contact_number = :number, contact_name = :name WHERE callId = :id")
    suspend fun updateContact(id: String, number: String?, name: String?)

    /**
     * Update mode/file paths after the recorder finalises — used by
     * `CallMonitorService.persistOutcomeFinal` so a Dual→Single downgrade
     * (one side silent + file deleted) is reflected in DB.
     */
    @Query("UPDATE calls SET mode = :mode, uplink_path = :up, downlink_path = :dn WHERE callId = :id")
    suspend fun updateOutcome(id: String, mode: String, up: String, dn: String?)

    @Query("UPDATE calls SET notes = :notes WHERE callId = :id")
    suspend fun setNotes(id: String, notes: String?)

    @Query("UPDATE calls SET favorite = :fav WHERE callId = :id")
    suspend fun setFavorite(id: String, fav: Boolean)

    @Query("UPDATE calls SET transcript = :transcript WHERE callId = :id")
    suspend fun setTranscript(id: String, transcript: String?)

    @Query("DELETE FROM calls WHERE callId = :id")
    suspend fun delete(id: String)

    @Query("""
        SELECT * FROM calls
        WHERE LOWER(IFNULL(contact_name, '')) LIKE '%' || LOWER(:q) || '%'
           OR IFNULL(contact_number, '') LIKE '%' || :q || '%'
           OR callId LIKE '%' || :q || '%'
        ORDER BY started_at DESC
    """)
    fun observeSearch(q: String): Flow<List<CallRecord>>

    @Query("DELETE FROM calls WHERE callId IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    /**
     * Cleanup helper — return all non-favourite recordings sorted oldest-first.
     * The size-cap policy walks this list and stops once enough has been
     * pruned to fit under the cap.
     */
    @Query("SELECT * FROM calls WHERE favorite = 0 ORDER BY started_at ASC")
    suspend fun selectOldestNotFavorite(): List<CallRecord>

    /**
     * Cleanup helper — return non-favourite recordings older than the cutoff
     * (UTC ms). Used by the max-age policy. Favourites are never returned so
     * the caller never has to filter them again.
     */
    @Query("SELECT * FROM calls WHERE favorite = 0 AND started_at < :cutoffMs ORDER BY started_at ASC")
    suspend fun selectOlderThan(cutoffMs: Long): List<CallRecord>

    /**
     * Bulk-delete non-favourite rows older than [cutoffMs]. Returns the
     * number of rows actually removed (Room exposes the SQLite changes()
     * count from a `DELETE` query).
     *
     * NOTE: this method does NOT touch the on-disk audio files — the caller
     * must select the rows first (via [selectOlderThan]), delete the files
     * with `BulkOps.deleteFiles`, and only then issue the row delete.
     */
    @Query("DELETE FROM calls WHERE favorite = 0 AND started_at < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}

// v1→v2: introduces the favorite column. Default 0 keeps existing rows un-starred.
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE calls ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
    }
}

// v2→v3: adds nullable transcript column (lazily filled by Whisper on demand).
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE calls ADD COLUMN transcript TEXT")
    }
}

@Database(entities = [CallRecord::class], version = 3, exportSchema = true)
abstract class RecordingsDb : RoomDatabase() {
    abstract fun calls(): CallDao

    companion object {
        fun create(ctx: Context): RecordingsDb =
            Room.databaseBuilder(ctx, RecordingsDb::class.java, "callrec.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
