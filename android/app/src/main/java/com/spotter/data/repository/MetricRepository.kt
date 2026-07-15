package com.spotter.data.repository

import com.spotter.data.local.dao.BodyMetricDao
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.BodyMetricOut
import com.spotter.data.remote.ApiService
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

/**
 * Bodyweight log — **offline-capable**, write-through like [SessionRepository]/[CardioRepository]:
 * a weigh-in lands in Room immediately (so it is never lost, even offline), is pushed best-effort,
 * and drains from a `syncPending` queue on the next [sync] / connectivity regain. Room is the read
 * source of truth; the network is the eventual authority for synced rows.
 */
class MetricRepository @Inject constructor(
    private val api: ApiService,
    private val dao: BodyMetricDao,
) {
    val metrics: Flow<List<BodyMetricEntity>> = dao.observeAll()

    suspend fun sync() {
        drainPending()
        val remote = api.getWeightMetrics()
        // Synced rows are keyed by their server id, so this REPLACEs them without duplicating.
        dao.upsertAll(remote.map { it.toEntity() })
    }

    /** Log a weigh-in. Persists locally first and never throws — offline, it queues for [sync]. */
    suspend fun addMetric(req: BodyMetricCreate) {
        val localId = UUID.randomUUID().toString()
        dao.upsert(req.toEntity(id = localId, userId = "", serverId = null, syncPending = true))
        // Best-effort immediate push; on success promote the local row to the acknowledged one.
        runCatching { promote(localId, api.addWeightMetric(req)) }
    }

    /** Push every offline weigh-in still queued; leaves any that fail for the next drain. */
    private suspend fun drainPending() {
        for (local in dao.getUnsynced()) {
            val saved = runCatching {
                api.addWeightMetric(local.toCreate())
            }.getOrNull() ?: continue // still offline — keep it pending
            promote(local.id, saved)
        }
    }

    /** Replace a local-id offline row with its acknowledged server row (PK becomes the server id). */
    private suspend fun promote(localId: String, saved: BodyMetricOut) {
        dao.deleteById(localId)
        dao.upsert(saved.toEntity())
    }

    private fun BodyMetricOut.toEntity() = BodyMetricEntity(
        id = id, userId = userId, date = date, weight = weight, bodyfat = bodyfat,
        neck = neck, chest = chest, waist = waist, hips = hips, arm = arm, thigh = thigh,
        serverId = id, syncPending = false,
    )

    private fun BodyMetricCreate.toEntity(
        id: String,
        userId: String,
        serverId: String?,
        syncPending: Boolean,
    ) = BodyMetricEntity(
        id = id, userId = userId, date = date, weight = weight, bodyfat = bodyfat,
        neck = neck, chest = chest, waist = waist, hips = hips, arm = arm, thigh = thigh,
        serverId = serverId, syncPending = syncPending,
    )

    private fun BodyMetricEntity.toCreate() = BodyMetricCreate(
        date = date, weight = weight, bodyfat = bodyfat,
        neck = neck, chest = chest, waist = waist, hips = hips, arm = arm, thigh = thigh,
    )
}
