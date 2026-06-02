package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.PlannedExerciseEntity

@Dao
interface PlannedExerciseDao {
    @Query("SELECT * FROM planned_exercises WHERE planId = :planId ORDER BY `order` ASC")
    suspend fun getByPlanId(planId: String): List<PlannedExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(exercises: List<PlannedExerciseEntity>)

    @Query("DELETE FROM planned_exercises WHERE planId = :planId")
    suspend fun deleteByPlanId(planId: String)
}
