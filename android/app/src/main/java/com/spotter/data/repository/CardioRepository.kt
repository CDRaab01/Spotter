package com.spotter.data.repository

import com.spotter.data.local.dao.CardioSessionDao
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.model.CardioActivityType
import com.spotter.data.model.CardioManualCreate
import com.spotter.data.model.CardioSessionCreate
import com.spotter.data.model.CardioSessionOut
import com.spotter.data.model.CardioSessionUpdate
import com.spotter.data.model.CardioStatus
import com.spotter.data.remote.ApiService
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source-of-truth-is-the-server cardio session store with a Room mirror, mirroring
 * [SessionRepository]: writes go through Room immediately (so the UI and Resume work offline and
 * across process death) and are pushed to the server best-effort, with [syncPending] tracking
 * anything not yet acknowledged.
 */
@Singleton
class CardioRepository @Inject constructor(
    private val api: ApiService,
    private val dao: CardioSessionDao,
) {
    fun sessionsFor(programId: String): Flow<List<CardioSessionEntity>> =
        dao.observeByProgram(programId)

    suspend fun getLocal(id: String): CardioSessionEntity? = dao.getById(id)

    /** Start a new session — local first, then push to the server for its id. */
    suspend fun startSession(programId: String, week: Int?, day: Int?): CardioSessionEntity {
        // A fresh start supersedes any stale in-progress session for the same program so the
        // overview never shows two "Attempted today" rows.
        for (stale in dao.getInProgress(programId)) {
            finish(stale.id, CardioStatus.ABANDONED, stale.totalElapsedSec)
        }
        val local = CardioSessionEntity(
            id = UUID.randomUUID().toString(),
            serverId = null,
            programId = programId,
            weekNumber = week,
            dayNumber = day,
            startedAt = Instant.now().toString(),
            completedAt = null,
            status = CardioStatus.IN_PROGRESS,
            totalElapsedSec = 0,
            syncPending = true,
        )
        dao.upsert(local)
        val synced = try {
            val remote = api.createCardioSession(
                CardioSessionCreate(programId = programId, weekNumber = week, dayNumber = day)
            )
            local.copy(serverId = remote.id, startedAt = remote.startedAt, syncPending = false)
        } catch (_: Exception) {
            local // offline — stays syncPending, picked up by sync()
        }
        dao.upsert(synced)
        return synced
    }

    /**
     * Log a walk/run after the fact — creates a *completed* session (no live timer). Local-first
     * (so it shows in history + counts toward stats immediately, even offline) then pushed to the
     * server via the dedicated manual-create endpoint; [sync] carries it up later if offline.
     *
     * @param date ISO date (yyyy-MM-dd) the activity happened.
     * @param distanceMeters optional canonical distance in meters (null for a time-only walk).
     */
    suspend fun logManualSession(
        activityType: String,
        durationSec: Int,
        distanceMeters: Int?,
        date: String,
    ): CardioSessionEntity {
        // Anchor completedAt at noon UTC on the chosen day so it buckets onto the intended
        // calendar date everywhere (matches the server's manual-create anchoring).
        val completedAt = "${date}T12:00:00Z"
        val local = CardioSessionEntity(
            id = UUID.randomUUID().toString(),
            serverId = null,
            programId = MANUAL_PROGRAM_ID,
            weekNumber = null,
            dayNumber = null,
            startedAt = completedAt,
            completedAt = completedAt,
            status = CardioStatus.COMPLETED,
            totalElapsedSec = durationSec,
            activityType = activityType,
            distanceMeters = distanceMeters,
            syncPending = true,
        )
        dao.upsert(local)
        val synced = try {
            val remote = api.createManualCardioSession(
                CardioManualCreate(
                    activityType = activityType,
                    durationSec = durationSec,
                    distanceMeters = distanceMeters,
                    date = date,
                )
            )
            local.copy(
                serverId = remote.id,
                startedAt = remote.startedAt,
                completedAt = remote.completedAt,
                syncPending = false,
            )
        } catch (_: Exception) {
            local // offline — stays syncPending, picked up by sync()
        }
        dao.upsert(synced)
        return synced
    }

    /** Persist progress mid-run (best-effort server PATCH; always updates Room). */
    suspend fun updateProgress(id: String, elapsedSec: Int) {
        val current = dao.getById(id) ?: return
        val updated = current.copy(totalElapsedSec = elapsedSec)
        val pushed = push(updated, CardioSessionUpdate(totalElapsedSec = elapsedSec))
        dao.upsert(pushed)
    }

    suspend fun completeSession(id: String, elapsedSec: Int) =
        finish(id, CardioStatus.COMPLETED, elapsedSec)

    suspend fun abandonSession(id: String, elapsedSec: Int) =
        finish(id, CardioStatus.ABANDONED, elapsedSec)

    private suspend fun finish(id: String, status: String, elapsedSec: Int) {
        val current = dao.getById(id) ?: return
        val completedAt = if (status == CardioStatus.COMPLETED) Instant.now().toString() else null
        val updated = current.copy(
            status = status,
            totalElapsedSec = elapsedSec,
            completedAt = completedAt,
        )
        val pushed = push(
            updated,
            CardioSessionUpdate(status = status, totalElapsedSec = elapsedSec),
        )
        dao.upsert(pushed)
    }

    /** Try to PATCH the server; returns the entity with the resulting sync state. */
    private suspend fun push(entity: CardioSessionEntity, update: CardioSessionUpdate): CardioSessionEntity {
        val serverId = entity.serverId ?: return entity.copy(syncPending = true)
        return try {
            api.updateCardioSession(serverId, update)
            entity.copy(syncPending = false)
        } catch (_: Exception) {
            entity.copy(syncPending = true)
        }
    }

    /** Pull server history into Room and push any pending local changes. */
    suspend fun sync(programId: String? = null) {
        // 1. Push pending local sessions first so we don't clobber them with the pull.
        for (pending in dao.getSyncPending()) {
            try {
                if (pending.serverId == null && pending.programId == MANUAL_PROGRAM_ID) {
                    // Manual entries have their own create endpoint (a completed session with
                    // activity_type/distance) — don't run them through the live-run create path,
                    // which would drop those fields.
                    val remote = api.createManualCardioSession(
                        CardioManualCreate(
                            activityType = pending.activityType ?: CardioActivityType.RUN,
                            durationSec = pending.totalElapsedSec,
                            distanceMeters = pending.distanceMeters,
                            date = pending.completedAt?.substringBefore('T'),
                        )
                    )
                    dao.upsert(
                        pending.copy(
                            serverId = remote.id,
                            completedAt = remote.completedAt,
                            syncPending = false,
                        )
                    )
                } else if (pending.serverId == null) {
                    val remote = api.createCardioSession(
                        CardioSessionCreate(
                            programId = pending.programId,
                            weekNumber = pending.weekNumber,
                            dayNumber = pending.dayNumber,
                        )
                    )
                    // Carry over any progress/terminal state captured offline.
                    api.updateCardioSession(
                        remote.id,
                        CardioSessionUpdate(
                            status = pending.status,
                            totalElapsedSec = pending.totalElapsedSec,
                        ),
                    )
                    dao.upsert(pending.copy(serverId = remote.id, syncPending = false))
                } else {
                    api.updateCardioSession(
                        pending.serverId,
                        CardioSessionUpdate(
                            status = pending.status,
                            totalElapsedSec = pending.totalElapsedSec,
                        ),
                    )
                    dao.upsert(pending.copy(syncPending = false))
                }
            } catch (_: Exception) {
                // still offline; leave pending
            }
        }

        // 2. Pull server-side history into the mirror, skipping rows we already mirror
        // locally (a local-UUID row carrying the same serverId) so we don't double up.
        try {
            val remote = api.listCardioSessions(programId)
            val knownServerIds = dao.getSynced().mapNotNull { it.serverId }.toSet()
            val fresh = remote.filter { it.id !in knownServerIds }.map { it.toEntity() }
            dao.upsertAll(fresh)
        } catch (_: Exception) {
            // offline — keep whatever's cached
        }
    }
}

/** Sentinel program id for after-the-fact manual entries (mirrors the server's constant). */
private const val MANUAL_PROGRAM_ID = "manual"

private fun CardioSessionOut.toEntity() = CardioSessionEntity(
    id = id,
    serverId = id,
    programId = programId,
    weekNumber = weekNumber,
    dayNumber = dayNumber,
    startedAt = startedAt,
    completedAt = completedAt,
    status = status,
    totalElapsedSec = totalElapsedSec,
    activityType = activityType,
    distanceMeters = distanceMeters,
    syncPending = false,
)
