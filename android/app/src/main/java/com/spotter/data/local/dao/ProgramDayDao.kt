package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spotter.data.local.entity.ProgramDayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgramDayDao {
    @Query("SELECT * FROM program_days WHERE programId = :programId ORDER BY `order`")
    suspend fun getByProgram(programId: String): List<ProgramDayEntity>

    @Query("SELECT * FROM program_days")
    fun observeAll(): Flow<List<ProgramDayEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(days: List<ProgramDayEntity>)

    @Query("DELETE FROM program_days WHERE programId = :programId")
    suspend fun deleteByProgram(programId: String)

    @Query("DELETE FROM program_days")
    suspend fun deleteAll()
}
