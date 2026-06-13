package com.spotter.data.repository

import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.SetLogEntity
import com.spotter.data.local.entity.WorkoutSessionEntity
import com.spotter.data.model.ApplyAdjustmentRequest
import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.SuggestedAdjustmentAction
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SessionSummary
import com.spotter.data.model.SessionUpdate
import com.spotter.data.model.SetLogCreate
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.SetLogUpdate
import com.spotter.data.remote.ApiService
import com.spotter.util.TokenStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

class SessionRepository @Inject constructor(
    private val api: ApiService,
    private val sessionDao: WorkoutSessionDao,
    private val setLogDao: SetLogDao,
    private val routineExerciseDao: RoutineExerciseDao,
    private val tokenStore: TokenStore,
) {
    // ── Session creation ──────────────────────────────────────────────────────

    suspend fun createSession(req: SessionCreate): SessionOut {
        val userId = tokenStore.getUserId() ?: "unknown"
        val localId = UUID.randomUUID().toString()

        val sessionEntity = WorkoutSessionEntity(
            id = localId, userId = userId, routineId = req.routineId, date = req.date,
            status = "in_progress", durationSeconds = null, note = req.note,
            exerciseNotes = null, serverId = null, syncPending = true,
        )
        sessionDao.upsert(sessionEntity)

        // Build set log shells from cached plan exercises so the workout
        // screen is immediately populated even when offline.
        val planExercises = req.routineId?.let { routineExerciseDao.getByRoutineId(it) } ?: emptyList()
        val localSetLogs = planExercises.flatMap { ex ->
            (1..ex.targetSets).map { setNum ->
                SetLogEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = localId, exerciseId = ex.exerciseId,
                    setNumber = setNum, reps = ex.targetReps,
                    weight = ex.targetWeight, completed = false, completedAt = null,
                    exerciseName = ex.exerciseName, targetSets = ex.targetSets,
                    targetReps = ex.targetReps, targetWeight = ex.targetWeight,
                    supersetGroup = ex.supersetGroup,
                    serverId = null, syncPending = false,
                )
            }
        }
        setLogDao.upsertAll(localSetLogs)

        // Try the server immediately; if offline, sync will happen later.
        try {
            val serverSession = api.createSession(req)
            reconcileNewSession(localId, sessionEntity, localSetLogs, serverSession)
        } catch (_: Exception) {}

        return sessionEntity.toSessionOut(localSetLogs)
    }

    // After a successful POST /sessions, match server set log IDs to local
    // entities by (exerciseId, setNumber) and persist the server IDs.
    private suspend fun reconcileNewSession(
        localId: String,
        sessionEntity: WorkoutSessionEntity,
        localLogs: List<SetLogEntity>,
        serverSession: SessionOut,
    ) {
        sessionDao.upsert(sessionEntity.copy(serverId = serverSession.id, syncPending = false))
        for (sl in serverSession.setLogs) {
            val local = localLogs.find { it.exerciseId == sl.exerciseId && it.setNumber == sl.setNumber }
            if (local != null) setLogDao.upsert(local.copy(serverId = sl.id))
        }
    }

    // ── Session read ──────────────────────────────────────────────────────────

    suspend fun getSession(id: String): SessionOut {
        val cached = sessionDao.getById(id)
        val serverSessionId = cached?.serverId
        return if (serverSessionId != null) {
            try {
                val serverResult = api.getSession(serverSessionId)
                val localLogs = setLogDao.getBySession(id)
                // Refresh local set logs from server; preserve local IDs and
                // syncPending flags so any offline edits aren't clobbered.
                val refreshedLogs = serverResult.setLogs.map { sl ->
                    val local = localLogs.find { it.serverId == sl.id }
                        ?: localLogs.find { it.exerciseId == sl.exerciseId && it.setNumber == sl.setNumber }
                    if (local != null) {
                        val keep = if (local.syncPending) local
                                   else local.copy(
                                       reps = sl.reps, weight = sl.weight,
                                       completed = sl.completed, completedAt = sl.completedAt,
                                   )
                        setLogDao.upsert(keep)
                        keep.toSetLogOut()
                    } else {
                        // Server has a set we don't have locally (e.g. added on another device)
                        val newLocal = sl.toEntity(localSessionId = id)
                        setLogDao.upsert(newLocal)
                        newLocal.toSetLogOut()
                    }
                }
                // Append locally-added sets that haven't been synced yet so they
                // remain visible in the UI while offline (serverId is null).
                val unsyncedLocal = localLogs
                    .filter { it.serverId == null && it.syncPending }
                    .map { it.toSetLogOut() }
                val allLogs = refreshedLogs + unsyncedLocal
                val updatedSession = serverResult.copy(
                    id = id,
                    setLogs = allLogs,
                    exerciseNotes = serverResult.exerciseNotes,
                )
                sessionDao.upsert(
                    updatedSession.toEntity().copy(
                        serverId = serverSessionId,
                        syncPending = cached.syncPending,
                    )
                )
                updatedSession
            } catch (_: Exception) {
                fallbackToLocal(cached, id)
            }
        } else {
            // Session not yet synced to server — serve from Room.
            fallbackToLocal(cached ?: throw Exception("Session not found: $id"), id)
        }
    }

    private suspend fun fallbackToLocal(session: WorkoutSessionEntity, id: String): SessionOut {
        val sets = setLogDao.getBySession(id)
        return session.toSessionOut(sets)
    }

    /**
     * Apply a user-accepted AI workout adjustment. Online-required by design — the
     * suggestion could only exist if chat (and therefore the server) was reachable.
     *
     * The server may have DELETED sets (swap/remove), which the incremental
     * [getSession] merge never does, so the local cache is rebuilt wholesale from the
     * response: unsynced local-only rows are preserved, everything else is replaced.
     */
    suspend fun applyAdjustment(
        localSessionId: String,
        actions: List<SuggestedAdjustmentAction>,
        applyToRoutine: Boolean,
    ): SessionOut {
        val cached = sessionDao.getById(localSessionId)
            ?: throw Exception("Session not found: $localSessionId")
        val serverSessionId = cached.serverId
            ?: throw Exception("Workout hasn't synced yet — try again in a moment.")

        val result = api.applyAdjustment(
            serverSessionId,
            ApplyAdjustmentRequest(actions = actions, applyToRoutine = applyToRoutine),
        )

        val unsyncedLocal = setLogDao.getBySession(localSessionId)
            .filter { it.serverId == null && it.syncPending }
        setLogDao.deleteBySession(localSessionId)
        unsyncedLocal.forEach { setLogDao.upsert(it) }
        result.setLogs.forEach { sl -> setLogDao.upsert(sl.toEntity(localSessionId)) }
        sessionDao.upsert(
            result.copy(id = localSessionId).toEntity().copy(
                serverId = serverSessionId,
                syncPending = cached.syncPending,
            )
        )
        return result.copy(id = localSessionId)
    }

    // ── Session updates ───────────────────────────────────────────────────────

    suspend fun updateSession(sessionId: String, req: SessionUpdate): SessionOut {
        val session = sessionDao.getById(sessionId) ?: throw Exception("Session not found: $sessionId")

        val mergedNotes: Map<String, String>? = req.exerciseNotes
            ?: session.exerciseNotes?.let { decodeNotes(it) }

        val updated = session.copy(
            status = req.status ?: session.status,
            durationSeconds = req.durationSeconds ?: session.durationSeconds,
            note = req.note ?: session.note,
            exerciseNotes = mergedNotes?.let { encodeNotes(it) } ?: session.exerciseNotes,
            syncPending = true,
        )
        sessionDao.upsert(updated)

        val serverSessionId = session.serverId
        return if (serverSessionId != null) {
            try {
                val result = api.updateSession(serverSessionId, req)
                sessionDao.upsert(updated.copy(syncPending = false))
                val sets = setLogDao.getBySession(sessionId)
                result.copy(id = sessionId, setLogs = adaptSetLogs(result.setLogs, sets, sessionId))
            } catch (_: Exception) {
                fallbackToLocal(updated, sessionId)
            }
        } else {
            fallbackToLocal(updated, sessionId)
        }
    }

    // ── Set log operations ────────────────────────────────────────────────────

    suspend fun logSet(sessionId: String, req: SetLogCreate): SetLogOut {
        val localId = UUID.randomUUID().toString()
        // Copy display enrichment (name, targets, superset group) from a sibling set of
        // the same exercise so an offline-added set renders fully without a server round
        // trip. The server re-derives these on read once the set syncs.
        val sibling = setLogDao.getBySession(sessionId)
            .firstOrNull { it.exerciseId == req.exerciseId }
        val localLog = SetLogEntity(
            id = localId, sessionId = sessionId,
            exerciseId = req.exerciseId, setNumber = req.setNumber,
            reps = req.reps, weight = req.weight, completed = req.completed, completedAt = null,
            exerciseName = sibling?.exerciseName, targetSets = sibling?.targetSets,
            targetReps = sibling?.targetReps, targetWeight = sibling?.targetWeight,
            supersetGroup = sibling?.supersetGroup,
            serverId = null, syncPending = true,
        )
        setLogDao.upsert(localLog)

        val serverSessionId = sessionDao.getById(sessionId)?.serverId
        return if (serverSessionId != null) {
            try {
                val result = api.logSet(serverSessionId, req)
                setLogDao.upsert(localLog.copy(serverId = result.id, syncPending = false))
                result.copy(id = localId, sessionId = sessionId)
            } catch (_: Exception) {
                localLog.toSetLogOut()
            }
        } else {
            localLog.toSetLogOut()
        }
    }

    suspend fun updateSet(sessionId: String, setId: String, req: SetLogUpdate): SetLogOut {
        val localLog = setLogDao.getById(setId) ?: throw Exception("Set not found: $setId")

        val updated = localLog.copy(
            reps = req.reps ?: localLog.reps,
            weight = if (req.weight != null) req.weight else localLog.weight,
            completed = req.completed ?: localLog.completed,
            syncPending = true,
        )
        setLogDao.upsert(updated)

        val session = sessionDao.getById(sessionId)
        val serverSessionId = session?.serverId
        val serverSetId = localLog.serverId
        return if (serverSessionId != null && serverSetId != null) {
            try {
                val result = api.updateSet(serverSessionId, serverSetId, req)
                setLogDao.upsert(
                    updated.copy(syncPending = false, completedAt = result.completedAt)
                )
                result.copy(id = setId, sessionId = sessionId)
            } catch (_: Exception) {
                updated.toSetLogOut()
            }
        } else {
            updated.toSetLogOut()
        }
    }

    // ── Prior bests ───────────────────────────────────────────────────────────

    suspend fun getPriorBests(sessionId: String): List<ExercisePrior> {
        val serverSessionId = sessionDao.getById(sessionId)?.serverId ?: return emptyList()
        return api.getPriorBests(serverSessionId)
    }

    // ── Session listing ───────────────────────────────────────────────────────

    suspend fun listSessions(): List<SessionSummary> {
        return try {
            api.listSessions()
        } catch (_: Exception) {
            // Offline fallback: build summaries from Room
            sessionDao.getAll().map { s ->
                val sets = setLogDao.getBySession(s.id)
                SessionSummary(
                    id = s.id, date = s.date, routineName = null,
                    status = s.status, durationSeconds = s.durationSeconds,
                    totalSets = sets.size,
                    completedSets = sets.count { it.completed },
                )
            }
        }
    }

    suspend fun deleteSession(sessionId: String) {
        val session = sessionDao.getById(sessionId)
        val serverSessionId = session?.serverId
        if (serverSessionId != null) {
            api.deleteSession(serverSessionId)
        }
        setLogDao.deleteBySession(sessionId)
        sessionDao.deleteById(sessionId)
    }

    // ── Background sync ───────────────────────────────────────────────────────

    suspend fun syncPending() {
        // 1. Create sessions on server that were created offline
        for (session in sessionDao.getUnsynced()) {
            try {
                val serverSession = api.createSession(
                    SessionCreate(routineId = session.routineId, date = session.date, note = session.note)
                )
                sessionDao.upsert(session.copy(serverId = serverSession.id, syncPending = false))
                val localLogs = setLogDao.getBySession(session.id)
                // Match server set logs → local by (exerciseId, setNumber)
                for (sl in serverSession.setLogs) {
                    val local = localLogs.find {
                        it.exerciseId == sl.exerciseId && it.setNumber == sl.setNumber
                    }
                    if (local != null) setLogDao.upsert(local.copy(serverId = sl.id))
                }
                // POST any locally-added sets that the server didn't auto-create
                for (local in localLogs) {
                    if (serverSession.setLogs.none {
                            it.exerciseId == local.exerciseId && it.setNumber == local.setNumber
                        }) {
                        val result = api.logSet(
                            serverSession.id,
                            SetLogCreate(
                                exerciseId = local.exerciseId, setNumber = local.setNumber,
                                reps = local.reps, weight = local.weight, completed = local.completed,
                            )
                        )
                        setLogDao.upsert(local.copy(serverId = result.id, syncPending = false))
                    }
                }
            } catch (_: Exception) { continue }
        }

        // 2. Create sets that were added offline to an already-synced session
        //    (these have a null serverId, so step 3 would otherwise skip them forever).
        for (sl in setLogDao.getUnsyncedNewLogs()) {
            val serverSessionId = sessionDao.getById(sl.sessionId)?.serverId ?: continue
            try {
                val result = api.logSet(
                    serverSessionId,
                    SetLogCreate(
                        exerciseId = sl.exerciseId, setNumber = sl.setNumber,
                        reps = sl.reps, weight = sl.weight, completed = sl.completed,
                    )
                )
                setLogDao.upsert(sl.copy(serverId = result.id, syncPending = false))
            } catch (_: Exception) { continue }
        }

        // 3. Push pending set log updates (completed, reps, weight)
        for (sl in setLogDao.getSyncPendingLogs()) {
            val serverSessionId = sessionDao.getById(sl.sessionId)?.serverId ?: continue
            val serverSetId = sl.serverId ?: continue
            try {
                api.updateSet(
                    serverSessionId, serverSetId,
                    SetLogUpdate(reps = sl.reps, weight = sl.weight, completed = sl.completed)
                )
                setLogDao.upsert(sl.copy(syncPending = false))
            } catch (_: Exception) { continue }
        }

        // 4. Push pending session-level updates (status, duration, notes)
        for (session in sessionDao.getSyncPendingSessions()) {
            val serverSessionId = session.serverId ?: continue
            try {
                api.updateSession(
                    serverSessionId,
                    SessionUpdate(
                        status = session.status,
                        durationSeconds = session.durationSeconds,
                        note = session.note,
                        exerciseNotes = session.exerciseNotes?.let { decodeNotes(it) },
                    )
                )
                sessionDao.upsert(session.copy(syncPending = false))
            } catch (_: Exception) { continue }
        }
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun WorkoutSessionEntity.toSessionOut(sets: List<SetLogEntity>) = SessionOut(
        id = id, userId = userId, routineId = routineId, date = date,
        status = status, durationSeconds = durationSeconds, note = note,
        exerciseNotes = exerciseNotes?.let { decodeNotes(it) },
        setLogs = sets.map { it.toSetLogOut() },
    )

    private fun SessionOut.toEntity() = WorkoutSessionEntity(
        id = id, userId = userId, routineId = routineId, date = date,
        status = status, durationSeconds = durationSeconds, note = note,
        exerciseNotes = exerciseNotes?.let { encodeNotes(it) },
        serverId = null, syncPending = false,
    )

    private fun SetLogOut.toEntity(localSessionId: String) = SetLogEntity(
        id = UUID.randomUUID().toString(),
        sessionId = localSessionId, exerciseId = exerciseId,
        setNumber = setNumber, reps = reps, weight = weight,
        completed = completed, completedAt = completedAt,
        exerciseName = exerciseName, targetSets = targetSets,
        targetReps = targetReps, targetWeight = targetWeight,
        supersetGroup = supersetGroup,
        serverId = id, syncPending = false,
    )

    private fun SetLogEntity.toSetLogOut() = SetLogOut(
        id = id, sessionId = sessionId, exerciseId = exerciseId,
        setNumber = setNumber, reps = reps, weight = weight,
        completed = completed, completedAt = completedAt,
        exerciseName = exerciseName, targetSets = targetSets,
        targetReps = targetReps, targetWeight = targetWeight,
        supersetGroup = supersetGroup,
    )

    private fun adaptSetLogs(
        serverLogs: List<SetLogOut>,
        localLogs: List<SetLogEntity>,
        localSessionId: String,
    ): List<SetLogOut> = serverLogs.map { sl ->
        val local = localLogs.find { it.serverId == sl.id }
            ?: localLogs.find { it.exerciseId == sl.exerciseId && it.setNumber == sl.setNumber }
        sl.copy(id = local?.id ?: sl.id, sessionId = localSessionId)
    }

    private fun encodeNotes(map: Map<String, String>): String =
        Json.encodeToString(map)

    private fun decodeNotes(json: String): Map<String, String> =
        runCatching { Json.decodeFromString<Map<String, String>>(json) }.getOrDefault(emptyMap())
}
