package com.spotter.data.repository

import com.spotter.data.local.dao.ExerciseDao
import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.dao.WorkoutRoutineDao
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
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * A session listing plus provenance: [fromCache] is true when the server was unreachable and the
 * summaries were rebuilt from the Room mirror — the signal behind the History stale banner.
 */
data class SessionListing(
    val sessions: List<SessionSummary>,
    val fromCache: Boolean,
)

class SessionRepository @Inject constructor(
    private val api: ApiService,
    private val sessionDao: WorkoutSessionDao,
    private val setLogDao: SetLogDao,
    private val routineExerciseDao: RoutineExerciseDao,
    private val routineDao: WorkoutRoutineDao,
    private val exerciseDao: ExerciseDao,
    private val tokenStore: TokenStore,
) {
    /**
     * The server id to send for a session's [routineId]. A routine created offline has a local
     * UUID id whose server id lands only once it syncs; the server wouldn't recognise the local id,
     * so translate to [WorkoutRoutineEntity.serverId] when we have it. Null routineId stays null; an
     * id with no local row (server-native) passes through unchanged. When the routine isn't synced
     * yet (serverId still null), we send the local id — the create will fail and the session stays
     * queued, then succeeds on a later drain once the routine has its server id.
     */
    private suspend fun routineServerId(routineId: String?): String? {
        if (routineId == null) return null
        val local = routineDao.getById(routineId) ?: return routineId
        return local.serverId ?: routineId
    }

    // ── Session creation ──────────────────────────────────────────────────────

    suspend fun createSession(req: SessionCreate): SessionOut {
        val userId = tokenStore.getUserId() ?: "unknown"
        val localId = UUID.randomUUID().toString()

        val sessionEntity = WorkoutSessionEntity(
            id = localId, userId = userId, routineId = req.routineId, date = req.date,
            status = "in_progress", durationSeconds = null, note = req.note,
            exerciseNotes = null, serverId = null, syncPending = true,
            startedAtMs = System.currentTimeMillis(),
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
            val serverSession = api.createSession(req.copy(routineId = routineServerId(req.routineId)))
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
                                       rpe = sl.rpe, setType = sl.setType,
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
                        startedAtMs = cached.startedAtMs,
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
        // The server-computed muscle_groups are unavailable offline; recompute from the exercise
        // mirror so an offline-finished workout still gets its summary breakdown. Exercises the
        // mirror doesn't know simply drop out (degrades to the old empty state, never a crash).
        val muscleGroupById = exerciseDao.getByIds(sets.map { it.exerciseId }.distinct())
            .associate { it.id to it.muscleGroup }
        return session.toSessionOut(sets).copy(
            muscleGroups = OfflineMuscleGroups.summarize(sets, muscleGroupById),
        )
    }

    /**
     * The session's wall-clock start anchor (epoch millis), or null for sessions created before the
     * column existed. The elapsed workout timer is derived from this so it stays correct across
     * backgrounding and process death (it is not carried on [SessionOut]).
     */
    suspend fun getStartedAtMs(id: String): Long? = sessionDao.getById(id)?.startedAtMs

    /**
     * Per-exercise rest overrides for the session's routine: exerciseId → restSeconds, only for
     * exercises that carry one. Read from the routine_exercises Room mirror (kept fresh by the
     * routine sync), so it works offline; SetLogOut deliberately doesn't carry rest_seconds — it
     * is routine prescription, not per-set data. Ad-hoc sessions (no routine) get an empty map.
     */
    suspend fun getRestSeconds(id: String): Map<String, Int> {
        val routineId = sessionDao.getById(id)?.routineId ?: return emptyMap()
        return routineExerciseDao.getByRoutineId(routineId)
            .mapNotNull { ex -> ex.restSeconds?.let { ex.exerciseId to it } }
            .toMap()
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
                startedAtMs = cached.startedAtMs,
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

    /**
     * [displayName] is a fallback for the sibling display enrichment below — pass it when adding
     * the FIRST set of an exercise mid-workout (there is no sibling to copy the name from, and
     * the getSession merge preserves local rows, so a null name would stick until a reconcile).
     */
    suspend fun logSet(sessionId: String, req: SetLogCreate, displayName: String? = null): SetLogOut {
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
            exerciseName = sibling?.exerciseName ?: displayName, targetSets = sibling?.targetSets,
            targetReps = sibling?.targetReps, targetWeight = sibling?.targetWeight,
            supersetGroup = sibling?.supersetGroup,
            rpe = req.rpe, setType = req.setType,
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
            rpe = req.rpe ?: localLog.rpe,
            setType = req.setType ?: localLog.setType,
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

    /**
     * Delete one set. Local removal is immediate; the server DELETE is best-effort:
     *
     * - Never synced (`serverId == null`) → hard-delete locally, nothing to tell the server.
     * - Synced + server DELETE succeeds → hard-delete locally.
     * - Synced + connectivity failure ([IOException]) → the row becomes a **pendingDelete
     *   tombstone** (the routine/program precedent applied to sets): hidden from every read
     *   (the DAO queries filter it), so the getSession merge can't resurrect the server's copy,
     *   and drained by [syncPending] which retries the DELETE on reconnect.
     * - Synced + HTTP error → the server answered (e.g. 409 completed session): restore the row
     *   and propagate, per the suite-wide degrade rule.
     */
    suspend fun deleteSet(localSessionId: String, setId: String) {
        val local = setLogDao.getById(setId) ?: return
        val serverSetId = local.serverId
        val serverSessionId = sessionDao.getById(localSessionId)?.serverId
        if (serverSetId == null || serverSessionId == null) {
            // Never reached the server (or the whole session hasn't) — just drop it locally; the
            // session-create sync path only pushes rows that still exist.
            setLogDao.deleteById(setId)
            return
        }
        setLogDao.upsert(local.copy(pendingDelete = true)) // hide immediately
        try {
            api.deleteSet(serverSessionId, serverSetId)
            setLogDao.deleteById(setId)
        } catch (_: IOException) {
            // Tombstone stays; syncPending drains it on reconnect.
        } catch (e: Exception) {
            setLogDao.upsert(local.copy(pendingDelete = false))
            throw e
        }
    }

    // ── Prior bests ───────────────────────────────────────────────────────────

    suspend fun getPriorBests(sessionId: String): List<ExercisePrior> {
        val serverSessionId = sessionDao.getById(sessionId)?.serverId ?: return emptyList()
        return api.getPriorBests(serverSessionId)
    }

    // ── Session listing ───────────────────────────────────────────────────────

    /**
     * Sessions with provenance. Only a connectivity failure ([IOException]) degrades to the Room
     * mirror; an HTTP error (`retrofit2.HttpException`) deliberately propagates — the server
     * answered, so erroring is the honest state.
     */
    suspend fun listSessionsWithFreshness(): SessionListing {
        return try {
            SessionListing(api.listSessions(), fromCache = false)
        } catch (_: IOException) {
            // Offline fallback: build summaries from Room
            val local = sessionDao.getAll().map { s ->
                val sets = setLogDao.getBySession(s.id)
                SessionSummary(
                    id = s.id, date = s.date, routineName = null,
                    status = s.status, durationSeconds = s.durationSeconds,
                    totalSets = sets.size,
                    completedSets = sets.count { it.completed },
                )
            }
            SessionListing(local, fromCache = true)
        }
    }

    suspend fun listSessions(): List<SessionSummary> = listSessionsWithFreshness().sessions

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
        // 0. Drain set-deletion tombstones (offline deletions of server-synced sets). A 404/410
        //    means the set is already gone server-side — treat as done; other HTTP errors also
        //    clear the tombstone (the server answered and refuses; retrying forever would fight
        //    it), while connectivity failures keep it queued.
        for (sl in setLogDao.getPendingDeleteLogs()) {
            val serverSessionId = sessionDao.getById(sl.sessionId)?.serverId ?: continue
            val serverSetId = sl.serverId ?: run { setLogDao.deleteById(sl.id); null } ?: continue
            try {
                api.deleteSet(serverSessionId, serverSetId)
                setLogDao.deleteById(sl.id)
            } catch (_: IOException) {
                continue
            } catch (_: Exception) {
                setLogDao.deleteById(sl.id)
            }
        }

        // 1. Create sessions on server that were created offline
        for (session in sessionDao.getUnsynced()) {
            try {
                val serverSession = api.createSession(
                    SessionCreate(
                        routineId = routineServerId(session.routineId),
                        date = session.date,
                        note = session.note,
                    )
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
                                rpe = local.rpe, setType = local.setType,
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
                        rpe = sl.rpe, setType = sl.setType,
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
                    SetLogUpdate(
                        reps = sl.reps, weight = sl.weight, completed = sl.completed,
                        rpe = sl.rpe, setType = sl.setType,
                    )
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
        rpe = rpe, setType = setType,
        serverId = id, syncPending = false,
    )

    private fun SetLogEntity.toSetLogOut() = SetLogOut(
        id = id, sessionId = sessionId, exerciseId = exerciseId,
        setNumber = setNumber, reps = reps, weight = weight,
        completed = completed, completedAt = completedAt,
        exerciseName = exerciseName, targetSets = targetSets,
        targetReps = targetReps, targetWeight = targetWeight,
        supersetGroup = supersetGroup,
        rpe = rpe, setType = setType,
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
