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

    /** Weigh-ins logged offline that still need pushing — the drain queue. */
    @Query("SELECT * FROM body_metrics WHERE syncPending = 1 ORDER BY date ASC")
    suspend fun getUnsynced(): List<BodyMetricEntity>

    @Query("DELETE FROM body_metrics WHERE id = :id")
    suspend fun deleteById(id: String)
}
