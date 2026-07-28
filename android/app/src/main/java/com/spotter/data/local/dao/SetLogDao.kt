package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.SetLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SetLogDao {
    // Deletion tombstones (pendingDelete = 1) are excluded from every read path — a tombstone is
    // a queued server DELETE, not data. Only the sync drain sees them, via getPendingDeleteLogs.
    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId AND pendingDelete = 0 ORDER BY setNumber ASC")
    fun observeBySession(sessionId: String): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId AND pendingDelete = 0 ORDER BY setNumber ASC")
    suspend fun getBySession(sessionId: String): List<SetLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: SetLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<SetLogEntity>)

    @Query("DELETE FROM set_logs WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT * FROM set_logs WHERE id = :id")
    suspend fun getById(id: String): SetLogEntity?

    @Query("SELECT * FROM set_logs WHERE syncPending = 1 AND serverId IS NOT NULL AND pendingDelete = 0")
    suspend fun getSyncPendingLogs(): List<SetLogEntity>

    /** Sets created offline that have never been POSTed (no serverId yet). */
    @Query("SELECT * FROM set_logs WHERE syncPending = 1 AND serverId IS NULL AND pendingDelete = 0")
    suspend fun getUnsyncedNewLogs(): List<SetLogEntity>

    /** Deletion tombstones: server-synced sets deleted while the DELETE couldn't land. */
    @Query("SELECT * FROM set_logs WHERE pendingDelete = 1")
    suspend fun getPendingDeleteLogs(): List<SetLogEntity>

    @Query("DELETE FROM set_logs WHERE id = :id")
    suspend fun deleteById(id: String)
}
