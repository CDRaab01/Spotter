package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.WorkoutRoutineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutRoutineDao {
    // Soft-deleted routines are hidden from the UI until their delete drains.
    @Query("SELECT * FROM workout_routines WHERE pendingDelete = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WorkoutRoutineEntity>>

    @Query("SELECT * FROM workout_routines WHERE id = :id")
    suspend fun getById(id: String): WorkoutRoutineEntity?

    @Query("SELECT * FROM workout_routines WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: String): WorkoutRoutineEntity?

    /** Routines with unpushed local edits (create/rename/exercise change), not soft-deleted. */
    @Query("SELECT * FROM workout_routines WHERE syncPending = 1 AND pendingDelete = 0")
    suspend fun getUnsynced(): List<WorkoutRoutineEntity>

    /** Routines deleted offline that still need the delete pushed. */
    @Query("SELECT * FROM workout_routines WHERE pendingDelete = 1")
    suspend fun getPendingDeletes(): List<WorkoutRoutineEntity>

    /** Server ids currently held locally (synced rows), to prune ones deleted on another device. */
    @Query("SELECT serverId FROM workout_routines WHERE serverId IS NOT NULL AND syncPending = 0 AND pendingDelete = 0")
    suspend fun syncedServerIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: WorkoutRoutineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(routines: List<WorkoutRoutineEntity>)

    @Query("DELETE FROM workout_routines WHERE id = :id")
    suspend fun deleteById(id: String)
}
