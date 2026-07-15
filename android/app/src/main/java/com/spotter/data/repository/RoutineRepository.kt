package com.spotter.data.repository

import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.WorkoutRoutineDao
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.data.model.RoutineCreate
import com.spotter.data.model.RoutineExerciseIn
import com.spotter.data.model.RoutineExerciseOut
import com.spotter.data.model.RoutineExercisesUpdate
import com.spotter.data.model.RoutineOut
import com.spotter.data.model.RoutineUpdate
import com.spotter.data.remote.ApiService
import com.spotter.util.TokenStore
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Workout routines — **offline-editable**, write-through with a sync queue (the 1.0 offline-writes
 * design). Every edit lands in Room immediately and never throws; the change is pushed best-effort
 * and drains on the next [sync] / reconnect. Room is the source of truth for reads.
 *
 * Ids: the Room PK is a **stable local id** (a UUID for a routine created offline, or the server id
 * for one pulled from the server) so sessions/programs that reference a routine stay valid even
 * before it syncs. The server id is tracked separately in [WorkoutRoutineEntity.serverId] and used
 * only when talking to the API. A pull reconciles by `serverId` so it updates rows in place instead
 * of duplicating locally-created ones.
 */
class RoutineRepository @Inject constructor(
    private val api: ApiService,
    private val dao: WorkoutRoutineDao,
    private val exerciseDao: RoutineExerciseDao,
    private val tokenStore: TokenStore,
) {
    val routines: Flow<List<WorkoutRoutineEntity>> = dao.observeAll()

    suspend fun sync() {
        drainPending()
        pullFromServer()
    }

    suspend fun getRoutine(id: String): RoutineOut {
        // Prefer the server copy when we have a server id and connectivity; fall back to local.
        val local = dao.getById(id)
        val serverId = local?.serverId
        if (serverId != null) {
            runCatching { api.getRoutine(serverId) }.getOrNull()?.let { return it }
        }
        if (local != null) return local.toOut(exerciseDao.getByRoutineId(id))
        return api.getRoutine(id)
    }

    suspend fun createRoutine(req: RoutineCreate): RoutineOut {
        val localId = UUID.randomUUID().toString()
        val entity = WorkoutRoutineEntity(
            id = localId,
            userId = tokenStore.getUserId() ?: "unknown",
            name = req.name,
            source = req.source,
            createdAt = Instant.now().toString(),
            serverId = null,
            syncPending = true,
        )
        dao.upsert(entity)
        val exEntities = req.exercises.map { it.toEntity(localId) }
        exerciseDao.upsertAll(exEntities)

        runCatching { api.createRoutine(req) }.getOrNull()?.let { saved ->
            dao.upsert(entity.copy(serverId = saved.id, syncPending = false))
            val savedEx = saved.exercises.map { it.toEntity(localId) }
            exerciseDao.deleteByRoutineId(localId)
            exerciseDao.upsertAll(savedEx)
            return entity.copy(serverId = saved.id).toOut(savedEx)
        }
        return entity.toOut(exEntities)
    }

    suspend fun renameRoutine(id: String, req: RoutineUpdate): RoutineOut {
        val entity = dao.getById(id) ?: return api.renameRoutine(id, req)
        val updated = entity.copy(name = req.name, syncPending = true)
        dao.upsert(updated)
        val serverId = updated.serverId
        if (serverId != null) {
            runCatching { api.renameRoutine(serverId, req) }
                .onSuccess { dao.upsert(updated.copy(syncPending = false)) }
        }
        // serverId == null → the pending create push carries the new name.
        return updated.toOut(exerciseDao.getByRoutineId(id))
    }

    suspend fun deleteRoutine(id: String) {
        val entity = dao.getById(id) ?: return
        if (entity.serverId == null) {
            // Never reached the server — just drop it locally.
            dao.deleteById(id)
            exerciseDao.deleteByRoutineId(id)
            return
        }
        dao.upsert(entity.copy(pendingDelete = true)) // hide immediately
        runCatching { api.deleteRoutine(entity.serverId) }.onSuccess {
            dao.deleteById(id)
            exerciseDao.deleteByRoutineId(id)
        }
    }

    suspend fun updateExercises(routineId: String, exercises: List<RoutineExerciseIn>): RoutineOut {
        exerciseDao.deleteByRoutineId(routineId)
        val exEntities = exercises.map { it.toEntity(routineId) }
        exerciseDao.upsertAll(exEntities)
        val entity = dao.getById(routineId)?.copy(syncPending = true)
        if (entity != null) {
            dao.upsert(entity)
            val serverId = entity.serverId
            if (serverId != null) {
                runCatching { api.updateRoutineExercises(serverId, RoutineExercisesUpdate(exercises)) }
                    .onSuccess { dao.upsert(entity.copy(syncPending = false)) }
            }
        }
        return entity?.toOut(exEntities)
            ?: api.updateRoutineExercises(routineId, RoutineExercisesUpdate(exercises))
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    /** Push every queued routine edit: deletes first, then creates/renames/exercise updates. */
    private suspend fun drainPending() {
        for (r in dao.getPendingDeletes()) {
            val sid = r.serverId
            if (sid != null && !runCatching { api.deleteRoutine(sid) }.isSuccess) continue
            dao.deleteById(r.id)
            exerciseDao.deleteByRoutineId(r.id)
        }
        for (r in dao.getUnsynced()) {
            val exercises = exerciseDao.getByRoutineId(r.id).sortedBy { it.order }.map { it.toIn() }
            if (r.serverId == null) {
                val saved = runCatching {
                    api.createRoutine(RoutineCreate(name = r.name, source = r.source, exercises = exercises))
                }.getOrNull() ?: continue
                dao.upsert(r.copy(serverId = saved.id, syncPending = false))
                exerciseDao.deleteByRoutineId(r.id)
                exerciseDao.upsertAll(saved.exercises.map { it.toEntity(r.id) })
            } else {
                val ok = runCatching {
                    api.renameRoutine(r.serverId, RoutineUpdate(r.name))
                    api.updateRoutineExercises(r.serverId, RoutineExercisesUpdate(exercises))
                }.isSuccess
                if (ok) dao.upsert(r.copy(syncPending = false))
            }
        }
    }

    /** Pull the server's routines, reconciling by serverId so locally-created rows aren't duplicated. */
    private suspend fun pullFromServer() {
        val remote = api.getRoutines()
        for (routine in remote) {
            val existing = dao.getByServerId(routine.id)
            if (existing != null) {
                // Leave rows with unpushed local edits / a pending delete alone until they drain.
                if (!existing.syncPending && !existing.pendingDelete) {
                    dao.upsert(
                        existing.copy(name = routine.name, source = routine.source, createdAt = routine.createdAt),
                    )
                    exerciseDao.deleteByRoutineId(existing.id)
                    exerciseDao.upsertAll(routine.exercises.map { it.toEntity(existing.id) })
                }
            } else {
                dao.upsert(routine.toEntity()) // keyed by the server id
                exerciseDao.deleteByRoutineId(routine.id)
                exerciseDao.upsertAll(routine.exercises.map { it.toEntity(routine.id) })
            }
        }
        // Prune synced routines deleted on another device (present locally, gone from the server).
        val serverIds = remote.map { it.id }.toHashSet()
        for (sid in dao.syncedServerIds()) {
            if (sid !in serverIds) {
                dao.getByServerId(sid)?.let {
                    dao.deleteById(it.id)
                    exerciseDao.deleteByRoutineId(it.id)
                }
            }
        }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────────

    private fun RoutineOut.toEntity() = WorkoutRoutineEntity(
        id = id, userId = userId, name = name, source = source, createdAt = createdAt,
        serverId = id, syncPending = false, pendingDelete = false,
    )

    private fun WorkoutRoutineEntity.toOut(exercises: List<RoutineExerciseEntity>) = RoutineOut(
        id = id, userId = userId, name = name, source = source, createdAt = createdAt,
        exercises = exercises.sortedBy { it.order }.map { it.toOut() },
    )

    private fun RoutineExerciseOut.toEntity(routineId: String) = RoutineExerciseEntity(
        routineId = routineId, exerciseId = exerciseId, exerciseName = exerciseName,
        targetSets = targetSets, targetReps = targetReps, targetWeight = targetWeight,
        isBodyweight = isBodyweight, order = order, supersetGroup = supersetGroup,
    )

    private fun RoutineExerciseIn.toEntity(routineId: String) = RoutineExerciseEntity(
        routineId = routineId, exerciseId = exerciseId, exerciseName = null,
        targetSets = targetSets, targetReps = targetReps, targetWeight = targetWeight,
        isBodyweight = isBodyweight, order = order, supersetGroup = supersetGroup,
    )

    private fun RoutineExerciseEntity.toIn() = RoutineExerciseIn(
        exerciseId = exerciseId, targetSets = targetSets, targetReps = targetReps,
        targetWeight = targetWeight, isBodyweight = isBodyweight, order = order,
        supersetGroup = supersetGroup,
    )

    private fun RoutineExerciseEntity.toOut() = RoutineExerciseOut(
        id = "$routineId:$exerciseId", exerciseId = exerciseId, targetSets = targetSets,
        targetReps = targetReps, targetWeight = targetWeight, isBodyweight = isBodyweight,
        order = order, exerciseName = exerciseName, supersetGroup = supersetGroup,
    )
}
