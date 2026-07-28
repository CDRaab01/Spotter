package com.spotter.data.repository

import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutRoutineDao
import com.spotter.data.local.entity.ProgramDayEntity
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.model.ProgramCreate
import com.spotter.data.model.ProgramDayIn
import com.spotter.data.model.ProgramDayOut
import com.spotter.data.model.ProgramDaysUpdate
import com.spotter.data.model.ProgramOut
import com.spotter.data.model.ProgramUpdate
import com.spotter.data.remote.ApiService
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Workout programs — offline-editable, write-through with a sync queue (the 1.0 offline-writes
 * design). Create/rename/activate/delete/replaceDays land in Room immediately and never throw, push
 * best-effort, and drain on the next [sync]. Stable local PK + separate serverId; a pull reconciles
 * by serverId so locally-created programs are not duplicated.
 *
 * A program day references a routine; when pushing, the day's local routineId is translated to that
 * routine's serverId (a routine may have been created offline), mirroring the session->routine path.
 */
@Singleton
class ProgramRepository @Inject constructor(
    private val api: ApiService,
    private val programDao: WorkoutProgramDao,
    private val dayDao: ProgramDayDao,
    private val routineDao: WorkoutRoutineDao,
) {
    val programs: Flow<List<WorkoutProgramEntity>> = programDao.getAll()

    suspend fun sync() {
        drainPending()
        pullFromServer()
    }

    suspend fun createProgram(req: ProgramCreate): ProgramOut {
        val localId = UUID.randomUUID().toString()
        val entity = WorkoutProgramEntity(id = localId, name = req.name, isActive = false, syncPending = true)
        programDao.upsert(entity)
        val dayEntities = req.days.map { it.toEntity(localId) }
        dayDao.upsertAll(dayEntities)

        runCatching { api.createProgram(ProgramCreate(req.name, translate(req.days))) }.getOrNull()?.let { saved ->
            programDao.upsert(entity.copy(serverId = saved.id, syncPending = false))
            dayDao.deleteByProgram(localId)
            dayDao.upsertAll(saved.days.map { it.toEntity(localId) })
            return entity.copy(serverId = saved.id).toOut(saved.days.map { it.toEntity(localId) })
        }
        return entity.toOut(dayEntities)
    }

    suspend fun updateProgram(id: String, req: ProgramUpdate): ProgramOut {
        val entity = programDao.getById(id) ?: return api.updateProgram(id, req)
        var updated = entity.copy(syncPending = true)
        if (req.name != null) updated = updated.copy(name = req.name)
        if (req.isActive == true) {
            programDao.getAllOnce().forEach {
                if (it.id != id && it.isActive) programDao.upsert(it.copy(isActive = false))
            }
            updated = updated.copy(isActive = true)
        }
        programDao.upsert(updated)
        val serverId = updated.serverId
        if (serverId != null) {
            runCatching { api.updateProgram(serverId, req) }
                .onSuccess { programDao.upsert(updated.copy(syncPending = false)) }
        }
        return updated.toOut(dayDao.getByProgram(id))
    }

    suspend fun deleteProgram(id: String) {
        val entity = programDao.getById(id) ?: return
        if (entity.serverId == null) {
            programDao.deleteById(id)
            dayDao.deleteByProgram(id)
            return
        }
        programDao.upsert(entity.copy(pendingDelete = true))
        runCatching { api.deleteProgram(entity.serverId) }.onSuccess {
            programDao.deleteById(id)
            dayDao.deleteByProgram(id)
        }
    }

    suspend fun replaceDays(id: String, req: ProgramDaysUpdate): ProgramOut {
        dayDao.deleteByProgram(id)
        val dayEntities = req.days.map { it.toEntity(id) }
        dayDao.upsertAll(dayEntities)
        val entity = programDao.getById(id)?.copy(syncPending = true)
        if (entity != null) {
            programDao.upsert(entity)
            val serverId = entity.serverId
            if (serverId != null) {
                runCatching { api.replaceProgramDays(serverId, ProgramDaysUpdate(translate(req.days))) }
                    .onSuccess { programDao.upsert(entity.copy(syncPending = false)) }
            }
        }
        return entity?.toOut(dayEntities) ?: api.replaceProgramDays(id, req)
    }

    suspend fun getNextProgramDay(): ProgramDayOut? =
        try { api.getNextProgramDay() } catch (_: Exception) { null }

    suspend fun daysFor(programId: String): List<ProgramDayEntity> = dayDao.getByProgram(programId)

    suspend fun programName(programId: String): String? = programDao.getById(programId)?.name

    // ── Sync ──────────────────────────────────────────────────────────────────

    private suspend fun drainPending() {
        for (p in programDao.getPendingDeletes()) {
            val sid = p.serverId
            if (sid != null && !runCatching { api.deleteProgram(sid) }.isSuccess) continue
            programDao.deleteById(p.id)
            dayDao.deleteByProgram(p.id)
        }
        for (p in programDao.getUnsynced()) {
            val days = translate(dayDao.getByProgram(p.id).sortedBy { it.order }.map { it.toIn() })
            if (p.serverId == null) {
                val saved = runCatching { api.createProgram(ProgramCreate(p.name, days)) }.getOrNull() ?: continue
                programDao.upsert(p.copy(serverId = saved.id, syncPending = false))
                dayDao.deleteByProgram(p.id)
                dayDao.upsertAll(saved.days.map { it.toEntity(p.id) })
                if (p.isActive) runCatching { api.updateProgram(saved.id, ProgramUpdate(isActive = true)) }
            } else {
                val ok = runCatching {
                    api.updateProgram(p.serverId, ProgramUpdate(name = p.name, isActive = if (p.isActive) true else null))
                    api.replaceProgramDays(p.serverId, ProgramDaysUpdate(days))
                }.isSuccess
                if (ok) programDao.upsert(p.copy(syncPending = false))
            }
        }
    }

    private suspend fun pullFromServer() {
        val remote = api.listPrograms()
        for (p in remote) {
            val existing = programDao.getByServerId(p.id)
            if (existing != null) {
                if (!existing.syncPending && !existing.pendingDelete) {
                    programDao.upsert(
                        existing.copy(
                            name = p.name, isActive = p.isActive, source = p.source,
                            description = p.description, weeks = p.weeks,
                            deloadWeek = p.deloadWeek, startedOn = p.startedOn,
                        )
                    )
                    dayDao.deleteByProgram(existing.id)
                    dayDao.upsertAll(p.days.map { it.toEntity(existing.id) })
                }
            } else {
                programDao.upsert(p.toEntity())
                dayDao.deleteByProgram(p.id)
                dayDao.upsertAll(p.days.map { it.toEntity(p.id) })
            }
        }
        val serverIds = remote.map { it.id }.toHashSet()
        for (sid in programDao.syncedServerIds()) {
            if (sid !in serverIds) {
                programDao.getByServerId(sid)?.let {
                    programDao.deleteById(it.id)
                    dayDao.deleteByProgram(it.id)
                }
            }
        }
    }

    /** Replace each day's local routineId with the routine's serverId for an API payload. */
    private suspend fun translate(days: List<ProgramDayIn>): List<ProgramDayIn> =
        days.map { d -> d.copy(routineId = d.routineId?.let { routineDao.getById(it)?.serverId ?: it }) }

    // ── Mapping ─────────────────────────────────────────────────────────────────

    private fun ProgramOut.toEntity() = WorkoutProgramEntity(
        id = id, name = name, isActive = isActive,
        source = source, description = description, weeks = weeks,
        deloadWeek = deloadWeek, startedOn = startedOn,
        serverId = id, syncPending = false, pendingDelete = false,
    )

    // current_week/is_deload_week stay at their defaults here — they are server-computed and
    // deliberately not mirrored (see WorkoutProgramEntity).
    private fun WorkoutProgramEntity.toOut(days: List<ProgramDayEntity>) = ProgramOut(
        id = id, name = name, isActive = isActive,
        source = source, description = description, weeks = weeks,
        deloadWeek = deloadWeek, startedOn = startedOn,
        days = days.sortedBy { it.order }.map { it.toOut() },
    )

    private fun ProgramDayOut.toEntity(programId: String) = ProgramDayEntity(
        id = id, programId = programId, routineId = routineId, label = label, order = order, routineName = routineName,
    )

    private fun ProgramDayIn.toEntity(programId: String) = ProgramDayEntity(
        id = UUID.randomUUID().toString(), programId = programId, routineId = routineId,
        label = label, order = order, routineName = null,
    )

    private fun ProgramDayEntity.toIn() = ProgramDayIn(routineId = routineId, label = label, order = order)

    private fun ProgramDayEntity.toOut() = ProgramDayOut(
        id = id, routineId = routineId, label = label, order = order, routineName = routineName,
    )
}
