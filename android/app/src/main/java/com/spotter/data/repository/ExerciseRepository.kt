package com.spotter.data.repository

import com.spotter.data.local.dao.ExerciseDao
import com.spotter.data.local.entity.ExerciseEntity
import com.spotter.data.model.ExerciseOut
import com.spotter.data.remote.ApiService
import java.io.IOException
import javax.inject.Inject

/**
 * Exercise catalog — server-seeded, mirrored into Room so library search, preset name→id
 * resolution, and the offline muscle-group summary keep working without connectivity.
 *
 * Reads prefer the server and refresh the mirror as a side effect. Only a connectivity failure
 * ([IOException]) degrades to the mirror — an HTTP error (`retrofit2.HttpException`) keeps
 * propagating, because the server answered and hiding its error behind cached rows would be
 * dishonest.
 */
class ExerciseRepository @Inject constructor(
    private val api: ApiService,
    private val dao: ExerciseDao,
) {
    suspend fun search(query: String): List<ExerciseOut> = try {
        api.searchExercises(query).also { dao.upsertAll(it.map { e -> e.toEntity() }) }
    } catch (_: IOException) {
        // LIKE wildcards (%/_) in the query pass through unescaped — acceptable looseness for
        // exercise names; the empty query matches the whole mirror like the server's does.
        dao.search("%${query.trim()}%").map { it.toOut() }
    }

    /** The full catalog (unfiltered `GET /exercises`), mirror-backed like [search]. */
    suspend fun listAll(): List<ExerciseOut> = try {
        api.searchExercises().also { dao.upsertAll(it.map { e -> e.toEntity() }) }
    } catch (_: IOException) {
        dao.getAll().map { it.toOut() }
    }

    /**
     * One exercise by id (`GET /exercises/{id}`), mirror-backed like [search]: an online read
     * seeds the mirror (incl. instructions/secondary muscles for the detail screen); a
     * connectivity failure falls back to the mirror row, which may predate the detail fields
     * (they render as absent). An id the mirror doesn't know keeps throwing the IOException.
     */
    suspend fun getExercise(id: String): ExerciseOut = try {
        api.getExercise(id).also { dao.upsertAll(listOf(it.toEntity())) }
    } catch (e: IOException) {
        dao.getById(id)?.toOut() ?: throw e
    }

    /**
     * Best-effort full-catalog refresh — the opportunistic seed run by the Home sync round and
     * the reconnect observer. Swallows every failure silently (it's a seed, not a feature) and
     * returns whether the server was reached, so callers may use it as a freshness signal.
     */
    suspend fun refreshCatalog(): Boolean =
        runCatching { dao.upsertAll(api.searchExercises().map { it.toEntity() }) }.isSuccess
}

private fun ExerciseOut.toEntity() = ExerciseEntity(
    id = id, name = name, muscleGroup = muscleGroup, equipment = equipment,
    instructions = instructions, secondaryMuscles = secondaryMuscles,
)

private fun ExerciseEntity.toOut() = ExerciseOut(
    id = id, name = name, muscleGroup = muscleGroup, equipment = equipment,
    instructions = instructions, secondaryMuscles = secondaryMuscles,
)
