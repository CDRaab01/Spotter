package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.WorkoutRoutineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutRoutineDao {
    @Query("SELECT * FROM workout_routines ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WorkoutRoutineEntity>>

    @Query("SELECT * FROM workout_routines WHERE id = :id")
    suspend fun getById(id: String): WorkoutRoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: WorkoutRoutineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(routines: List<WorkoutRoutineEntity>)

    @Query("DELETE FROM workout_routines WHERE id = :id")
    suspend fun deleteById(id: String)
}
