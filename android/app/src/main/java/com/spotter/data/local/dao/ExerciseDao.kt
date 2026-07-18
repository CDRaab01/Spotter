package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.ExerciseEntity

@Dao
interface ExerciseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(exercises: List<ExerciseEntity>)

    /**
     * Name search for the offline Exercise Library path. [pattern] is a ready-made LIKE pattern
     * (e.g. `%bench%`) — SQLite LIKE is case-insensitive for ASCII, matching the server's
     * case-insensitive `?search=` behaviour closely enough for a fallback.
     */
    @Query("SELECT * FROM exercises WHERE name LIKE :pattern ORDER BY name ASC")
    suspend fun search(pattern: String): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ExerciseEntity>

    @Query("SELECT * FROM exercises ORDER BY name ASC")
    suspend fun getAll(): List<ExerciseEntity>
}
