package com.spotter.data.repository

import com.spotter.data.local.dao.ExerciseDao
import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.dao.WorkoutRoutineDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.ExerciseEntity
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.SetLogEntity
import com.spotter.data.local.entity.WorkoutSessionEntity
import com.spotter.data.model.SessionUpdate
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.SetLogUpdate
import com.spotter.data.remote.ApiService
import com.spotter.util.TokenStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two new offline seams of [SessionRepository]: an offline-finished workout gets its
 * muscle-group breakdown from the exercise mirror, and the freshness-aware session listing
 * degrades on IOException only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {

    private lateinit var api: ApiService
    private lateinit var sessionDao: WorkoutSessionDao
    private lateinit var setLogDao: SetLogDao
    private lateinit var routineExerciseDao: RoutineExerciseDao
    private lateinit var routineDao: WorkoutRoutineDao
    private lateinit var exerciseDao: ExerciseDao
    private lateinit var tokenStore: TokenStore
    private lateinit var repo: SessionRepository

    @Before
    fun setup() {
        api = mock()
        sessionDao = mock()
        setLogDao = mock()
        routineExerciseDao = mock()
        routineDao = mock()
        exerciseDao = mock()
        tokenStore = mock()
        repo = SessionRepository(
            api, sessionDao, setLogDao, routineExerciseDao, routineDao, exerciseDao, tokenStore,
        )
    }

    private fun session(id: String = "local") = WorkoutSessionEntity(
        id = id, userId = "u1", routineId = "r1", date = "2026-07-17",
        status = "in_progress", durationSeconds = null, note = null,
        serverId = "srv-1", syncPending = false,
    )

    private fun setLog(
        id: String,
        exerciseId: String,
        reps: Int,
        weight: Double?,
        completed: Boolean = true,
        serverId: String? = null,
    ) = SetLogEntity(
        id = id, sessionId = "local", exerciseId = exerciseId, setNumber = 1,
        reps = reps, weight = weight, completed = completed, completedAt = null,
        serverId = serverId,
    )

    @Test
    fun `finishing offline computes the muscle-group breakdown from the mirror`() = runTest {
        whenever(sessionDao.getById("local")).thenReturn(session())
        whenever(api.updateSession(any(), any())).thenAnswer { throw IOException("offline") }
        whenever(setLogDao.getBySession("local")).thenReturn(
            listOf(
                setLog("a", "bench", reps = 8, weight = 100.0),
                setLog("b", "bench", reps = 8, weight = 100.0),
                setLog("c", "squat", reps = 5, weight = null),
                setLog("d", "bench", reps = 8, weight = 100.0, completed = false),
            )
        )
        whenever(exerciseDao.getByIds(any())).thenReturn(
            listOf(
                ExerciseEntity("bench", "Bench Press", "chest", "barbell"),
                ExerciseEntity("squat", "Squat", "legs", "barbell"),
            )
        )

        val out = repo.updateSession(
            "local", SessionUpdate(status = "completed", durationSeconds = 600),
        )

        assertEquals(listOf("chest", "legs"), out.muscleGroups.map { it.muscleGroup })
        val chest = out.muscleGroups.first { it.muscleGroup == "chest" }
        assertEquals(2, chest.sets)                 // the incomplete bench set doesn't count
        assertEquals(725.7f, chest.volume)          // 2 × 8 × 100 lb × 0.453592, one decimal (kg)
        val legs = out.muscleGroups.first { it.muscleGroup == "legs" }
        assertEquals(1, legs.sets)
        assertEquals(0f, legs.volume)               // bodyweight set: counts, no volume
    }

    @Test
    fun `offline finish with an unseeded mirror degrades to an empty breakdown`() = runTest {
        whenever(sessionDao.getById("local")).thenReturn(session())
        whenever(api.updateSession(any(), any())).thenAnswer { throw IOException("offline") }
        whenever(setLogDao.getBySession("local")).thenReturn(
            listOf(setLog("a", "bench", reps = 8, weight = 100.0))
        )
        whenever(exerciseDao.getByIds(any())).thenReturn(emptyList())

        val out = repo.updateSession("local", SessionUpdate(status = "completed"))

        assertTrue(out.muscleGroups.isEmpty(), "unknown exercises must degrade, not crash")
    }

    @Test
    fun `listSessionsWithFreshness serves the Room mirror on a connectivity failure`() = runTest {
        whenever(api.listSessions()).thenAnswer { throw IOException("offline") }
        whenever(sessionDao.getAll()).thenReturn(listOf(session()))
        whenever(setLogDao.getBySession("local")).thenReturn(
            listOf(
                setLog("a", "bench", reps = 8, weight = 100.0),
                setLog("b", "bench", reps = 8, weight = 100.0, completed = false),
            )
        )

        val result = repo.listSessionsWithFreshness()

        assertTrue(result.fromCache)
        val summary = result.sessions.single()
        assertEquals("local", summary.id)
        assertEquals(2, summary.totalSets)
        assertEquals(1, summary.completedSets)
    }

    @Test
    fun `listSessionsWithFreshness propagates HTTP errors instead of degrading`() = runTest {
        whenever(api.listSessions()).thenThrow(
            HttpException(Response.error<Any>(500, "".toResponseBody("application/json".toMediaType())))
        )

        assertFailsWith<HttpException> { repo.listSessionsWithFreshness() }
    }

    // ── Set deletion (tombstone pattern) ───────────────────────────────────────

    @Test
    fun `deleteSet on a never-synced set hard-deletes locally without a server call`() = runTest {
        whenever(sessionDao.getById("local")).thenReturn(session())
        whenever(setLogDao.getById("a"))
            .thenReturn(setLog("a", "bench", reps = 8, weight = 100.0, serverId = null))

        repo.deleteSet("local", "a")

        verify(setLogDao).deleteById("a")
        verify(api, never()).deleteSet(any(), any())
    }

    @Test
    fun `deleteSet online deletes on the server then locally`() = runTest {
        whenever(sessionDao.getById("local")).thenReturn(session())
        whenever(setLogDao.getById("a"))
            .thenReturn(setLog("a", "bench", reps = 8, weight = 100.0, serverId = "ss-1"))

        repo.deleteSet("local", "a")

        verify(api).deleteSet("srv-1", "ss-1")
        verify(setLogDao).deleteById("a")
    }

    @Test
    fun `deleteSet offline leaves a hidden pendingDelete tombstone`() = runTest {
        val local = setLog("a", "bench", reps = 8, weight = 100.0, serverId = "ss-1")
        whenever(sessionDao.getById("local")).thenReturn(session())
        whenever(setLogDao.getById("a")).thenReturn(local)
        whenever(api.deleteSet(any(), any())).thenAnswer { throw IOException("offline") }

        repo.deleteSet("local", "a")

        // The row survives as a tombstone (hidden by the DAO's pendingDelete filter) so the
        // getSession merge can't resurrect the server's copy; the sync drain retries the DELETE.
        verify(setLogDao).upsert(local.copy(pendingDelete = true))
        verify(setLogDao, never()).deleteById(any())
    }

    @Test
    fun `deleteSet HTTP error restores the row and propagates`() = runTest {
        val local = setLog("a", "bench", reps = 8, weight = 100.0, serverId = "ss-1")
        whenever(sessionDao.getById("local")).thenReturn(session())
        whenever(setLogDao.getById("a")).thenReturn(local)
        whenever(api.deleteSet(any(), any())).thenThrow(
            HttpException(Response.error<Any>(409, "".toResponseBody("application/json".toMediaType())))
        )

        assertFailsWith<HttpException> { repo.deleteSet("local", "a") }

        verify(setLogDao).upsert(local.copy(pendingDelete = true))
        verify(setLogDao).upsert(local.copy(pendingDelete = false))
        verify(setLogDao, never()).deleteById(any())
    }

    /** The remaining drain steps' list getters (suspend mocks default to null, not empty). */
    private suspend fun stubEmptySyncQueues() {
        whenever(sessionDao.getUnsynced()).thenReturn(emptyList())
        whenever(setLogDao.getUnsyncedNewLogs()).thenReturn(emptyList())
        whenever(setLogDao.getSyncPendingLogs()).thenReturn(emptyList())
        whenever(sessionDao.getSyncPendingSessions()).thenReturn(emptyList())
    }

    @Test
    fun `syncPending drains set-deletion tombstones`() = runTest {
        stubEmptySyncQueues()
        val tombstone = setLog("a", "bench", reps = 8, weight = 100.0, serverId = "ss-1")
            .copy(pendingDelete = true)
        whenever(setLogDao.getPendingDeleteLogs()).thenReturn(listOf(tombstone))
        whenever(sessionDao.getById("local")).thenReturn(session())

        repo.syncPending()

        verify(api).deleteSet("srv-1", "ss-1")
        verify(setLogDao).deleteById("a")
    }

    @Test
    fun `syncPending keeps a tombstone queued across a connectivity failure`() = runTest {
        stubEmptySyncQueues()
        val tombstone = setLog("a", "bench", reps = 8, weight = 100.0, serverId = "ss-1")
            .copy(pendingDelete = true)
        whenever(setLogDao.getPendingDeleteLogs()).thenReturn(listOf(tombstone))
        whenever(sessionDao.getById("local")).thenReturn(session())
        whenever(api.deleteSet(any(), any())).thenAnswer { throw IOException("still offline") }

        repo.syncPending()

        verify(setLogDao, never()).deleteById(any())
    }

    // ── RPE / set type ─────────────────────────────────────────────────────────

    @Test
    fun `updateSet carries rpe and setType to the server and the mirror`() = runTest {
        val local = setLog("a", "bench", reps = 8, weight = 100.0, serverId = "ss-1")
        whenever(sessionDao.getById("local")).thenReturn(session())
        whenever(setLogDao.getById("a")).thenReturn(local)
        val serverOut = SetLogOut(
            id = "ss-1", sessionId = "srv-1", exerciseId = "bench", setNumber = 1,
            reps = 8, weight = 100.0, completed = true, rpe = 8.5, setType = "drop",
        )
        whenever(api.updateSet(any(), any(), any())).thenReturn(serverOut)

        val req = SetLogUpdate(rpe = 8.5, setType = "drop")
        val out = repo.updateSet("local", "a", req)

        verify(api).updateSet("srv-1", "ss-1", req)
        assertEquals(8.5, out.rpe)
        assertEquals("drop", out.setType)
        // The Room mirror keeps the new fields too (nothing silently dropped).
        val upserts = argumentCaptor<SetLogEntity>()
        verify(setLogDao, org.mockito.kotlin.times(2)).upsert(upserts.capture())
        assertEquals(8.5, upserts.firstValue.rpe)
        assertEquals("drop", upserts.firstValue.setType)
    }

    @Test
    fun `getRestSeconds maps only exercises carrying an override`() = runTest {
        whenever(sessionDao.getById("local")).thenReturn(session())
        whenever(routineExerciseDao.getByRoutineId("r1")).thenReturn(
            listOf(
                routineExercise("bench", restSeconds = 45),
                routineExercise("squat", restSeconds = null),
            )
        )

        assertEquals(mapOf("bench" to 45), repo.getRestSeconds("local"))
    }

    @Test
    fun `getRestSeconds is empty for an ad-hoc session without a routine`() = runTest {
        whenever(sessionDao.getById("local")).thenReturn(session().copy(routineId = null))

        assertEquals(emptyMap(), repo.getRestSeconds("local"))
    }

    private fun routineExercise(exerciseId: String, restSeconds: Int?) = RoutineExerciseEntity(
        routineId = "r1", exerciseId = exerciseId, exerciseName = exerciseId,
        targetSets = 3, targetReps = 8, targetWeight = 100.0, isBodyweight = false,
        order = 0, restSeconds = restSeconds,
    )
}
