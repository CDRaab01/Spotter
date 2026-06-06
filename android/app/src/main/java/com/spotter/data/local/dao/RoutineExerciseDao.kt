package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.RoutineExerciseEntity

@Dao
interface RoutineExerciseDao {
    @Query("SELECT * FROM routine_exercises WHERE routineId = :routineId ORDER BY `order` ASC")
    suspend fun getByRoutineId(routineId: String): List<RoutineExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(exercises: List<RoutineExerciseEntity>)

    @Query("DELETE FROM routine_exercises WHERE routineId = :routineId")
    suspend fun deleteByRoutineId(routineId: String)
}
