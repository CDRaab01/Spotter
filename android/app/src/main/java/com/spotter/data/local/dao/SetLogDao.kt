package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.SetLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SetLogDao {
    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId ORDER BY setNumber ASC")
    fun observeBySession(sessionId: String): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs WHERE sessionId = :sessionId ORDER BY setNumber ASC")
    suspend fun getBySession(sessionId: String): List<SetLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: SetLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<SetLogEntity>)

    @Query("DELETE FROM set_logs WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT * FROM set_logs WHERE id = :id")
    suspend fun getById(id: String): SetLogEntity?

    @Query("SELECT * FROM set_logs WHERE syncPending = 1 AND serverId IS NOT NULL")
    suspend fun getSyncPendingLogs(): List<SetLogEntity>

    /** Sets created offline that have never been POSTed (no serverId yet). */
    @Query("SELECT * FROM set_logs WHERE syncPending = 1 AND serverId IS NULL")
    suspend fun getUnsyncedNewLogs(): List<SetLogEntity>
}
