package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.BodyMetricEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BodyMetricDao {
    @Query("SELECT * FROM body_metrics ORDER BY date ASC")
    fun observeAll(): Flow<List<BodyMetricEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metric: BodyMetricEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(metrics: List<BodyMetricEntity>)
}
