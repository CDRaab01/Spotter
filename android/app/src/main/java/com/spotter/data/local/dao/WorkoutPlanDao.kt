package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.WorkoutPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutPlanDao {
    @Query("SELECT * FROM workout_plans ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WorkoutPlanEntity>>

    @Query("SELECT * FROM workout_plans WHERE id = :id")
    suspend fun getById(id: String): WorkoutPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: WorkoutPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(plans: List<WorkoutPlanEntity>)

    @Query("DELETE FROM workout_plans WHERE id = :id")
    suspend fun deleteById(id: String)
}
