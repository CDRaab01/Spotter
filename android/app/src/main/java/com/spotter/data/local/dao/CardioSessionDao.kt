package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.CardioSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CardioSessionDao {
    @Query("SELECT * FROM cardio_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CardioSessionEntity>>

    @Query("SELECT * FROM cardio_sessions WHERE programId = :programId ORDER BY startedAt DESC")
    fun observeByProgram(programId: String): Flow<List<CardioSessionEntity>>

    @Query("SELECT * FROM cardio_sessions WHERE id = :id")
    suspend fun getById(id: String): CardioSessionEntity?

    @Query("SELECT * FROM cardio_sessions WHERE programId = :programId AND status = 'in_progress'")
    suspend fun getInProgress(programId: String): List<CardioSessionEntity>

    /** All in-progress cardio sessions across programs, for the app-shell "resume" banner. */
    @Query("SELECT * FROM cardio_sessions WHERE status = 'in_progress' ORDER BY startedAt DESC")
    fun observeInProgress(): Flow<List<CardioSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: CardioSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<CardioSessionEntity>)

    @Query("DELETE FROM cardio_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM cardio_sessions WHERE syncPending = 1")
    suspend fun getSyncPending(): List<CardioSessionEntity>

    /** All sessions that came from the server (have a serverId), used to reconcile on sync. */
    @Query("SELECT * FROM cardio_sessions WHERE serverId IS NOT NULL")
    suspend fun getSynced(): List<CardioSessionEntity>
}
