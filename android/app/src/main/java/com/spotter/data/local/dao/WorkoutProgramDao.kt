package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.WorkoutProgramEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutProgramDao {
    @Query("SELECT * FROM workout_programs ORDER BY name")
    fun getAll(): Flow<List<WorkoutProgramEntity>>

    @Query("SELECT * FROM workout_programs WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): WorkoutProgramEntity?

    @Query("SELECT * FROM workout_programs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorkoutProgramEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(programs: List<WorkoutProgramEntity>)

    @Query("DELETE FROM workout_programs")
    suspend fun deleteAll()
}
