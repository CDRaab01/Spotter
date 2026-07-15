package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.WorkoutProgramEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutProgramDao {
    @Query("SELECT * FROM workout_programs WHERE pendingDelete = 0 ORDER BY name")
    fun getAll(): Flow<List<WorkoutProgramEntity>>

    @Query("SELECT * FROM workout_programs WHERE isActive = 1 AND pendingDelete = 0 LIMIT 1")
    suspend fun getActive(): WorkoutProgramEntity?

    @Query("SELECT * FROM workout_programs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorkoutProgramEntity?

    @Query("SELECT * FROM workout_programs WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: String): WorkoutProgramEntity?

    @Query("SELECT * FROM workout_programs WHERE pendingDelete = 0")
    suspend fun getAllOnce(): List<WorkoutProgramEntity>

    @Query("SELECT * FROM workout_programs WHERE syncPending = 1 AND pendingDelete = 0")
    suspend fun getUnsynced(): List<WorkoutProgramEntity>

    @Query("SELECT * FROM workout_programs WHERE pendingDelete = 1")
    suspend fun getPendingDeletes(): List<WorkoutProgramEntity>

    @Query("SELECT serverId FROM workout_programs WHERE serverId IS NOT NULL AND syncPending = 0 AND pendingDelete = 0")
    suspend fun syncedServerIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(program: WorkoutProgramEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(programs: List<WorkoutProgramEntity>)

    @Query("DELETE FROM workout_programs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM workout_programs")
    suspend fun deleteAll()
}
