package com.spotter.data.repository

import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.entity.ProgramDayEntity
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.model.ProgramCreate
import com.spotter.data.model.ProgramDayOut
import com.spotter.data.model.ProgramDaysUpdate
import com.spotter.data.model.ProgramOut
import com.spotter.data.model.ProgramUpdate
import com.spotter.data.remote.ApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgramRepository @Inject constructor(
    private val api: ApiService,
    private val programDao: WorkoutProgramDao,
    private val dayDao: ProgramDayDao,
) {
    val programs: Flow<List<WorkoutProgramEntity>> = programDao.getAll()

    suspend fun sync() {
        val remote = api.listPrograms()
        programDao.deleteAll()
        dayDao.deleteAll()
        programDao.upsertAll(remote.map { it.toEntity() })
        remote.forEach { p ->
            dayDao.upsertAll(p.days.map { it.toEntity(p.id) })
        }
    }

    suspend fun createProgram(req: ProgramCreate): ProgramOut {
        val result = api.createProgram(req)
        programDao.upsertAll(listOf(result.toEntity()))
        dayDao.upsertAll(result.days.map { it.toEntity(result.id) })
        return result
    }

    suspend fun updateProgram(id: String, req: ProgramUpdate): ProgramOut {
        val result = api.updateProgram(id, req)
        if (req.isActive == true) {
            // Deactivate all programs locally before marking the new one active so
            // getActive() (LIMIT 1) can't transiently return the wrong program.
            val all = programDao.getAll().first()
            programDao.upsertAll(all.map { it.copy(isActive = false) })
        }
        programDao.upsertAll(listOf(result.toEntity()))
        return result
    }

    suspend fun deleteProgram(id: String) {
        api.deleteProgram(id)
        dayDao.deleteByProgram(id)
        // Note: programDao doesn't have deleteById, so we sync after delete
        sync()
    }

    suspend fun replaceDays(id: String, req: ProgramDaysUpdate): ProgramOut {
        val result = api.replaceProgramDays(id, req)
        dayDao.deleteByProgram(id)
        dayDao.upsertAll(result.days.map { it.toEntity(id) })
        return result
    }

    suspend fun getNextProgramDay(): ProgramDayOut? =
        try { api.getNextProgramDay() } catch (_: Exception) { null }

    suspend fun daysFor(programId: String): List<ProgramDayEntity> =
        dayDao.getByProgram(programId)

    suspend fun programName(programId: String): String? =
        programDao.getById(programId)?.name
}

private fun ProgramOut.toEntity() = WorkoutProgramEntity(
    id = id, name = name, isActive = isActive,
)

private fun ProgramDayOut.toEntity(programId: String) = ProgramDayEntity(
    id = id, programId = programId, planId = planId,
    label = label, order = order, planName = planName,
)
