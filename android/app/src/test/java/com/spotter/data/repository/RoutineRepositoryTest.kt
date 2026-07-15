package com.spotter.data.repository

import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.WorkoutRoutineDao
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.data.model.RoutineCreate
import com.spotter.data.model.RoutineOut
import com.spotter.data.model.RoutineUpdate
import com.spotter.data.remote.ApiService
import com.spotter.util.TokenStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The routine sync engine: edits are written to Room first and never throw, then queue for the
 * server. A pull reconciles by serverId so a locally-created routine isn't duplicated, and an
 * offline delete hides the routine immediately while queuing the server-side removal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutineRepositoryTest {

    private lateinit var dao: FakeRoutineDao
    private lateinit var exDao: FakeExerciseDao
    private lateinit var api: ApiService
    private lateinit var tokenStore: TokenStore
    private lateinit var repo: RoutineRepository

    @Before
    fun setup() {
        dao = FakeRoutineDao()
        exDao = FakeExerciseDao()
        api = mock()
        tokenStore = mock()
        repo = RoutineRepository(api, dao, exDao, tokenStore)
    }

    @Test
    fun `createRoutine offline queues it and never throws`() = runTest {
        whenever(tokenStore.getUserId()).thenReturn("u")
        whenever(api.createRoutine(any())).thenThrow(RuntimeException("offline"))

        repo.createRoutine(RoutineCreate(name = "Push Day"))

        val row = dao.observeAll().first().single()
        assertEquals("Push Day", row.name)
        assertTrue(row.syncPending, "an offline routine must be queued")
        assertNull(row.serverId)
    }

    @Test
    fun `deleteRoutine offline hides it and queues the delete`() = runTest {
        // A synced routine already in Room.
        dao.upsert(
            WorkoutRoutineEntity(
                id = "srv-1", userId = "u", name = "Legs", source = "manual",
                createdAt = "t", serverId = "srv-1", syncPending = false,
            ),
        )
        whenever(api.deleteRoutine(any())).thenThrow(RuntimeException("offline"))

        repo.deleteRoutine("srv-1")

        assertTrue(dao.observeAll().first().isEmpty(), "a deleted routine is hidden immediately")
        assertEquals(1, dao.getPendingDeletes().size, "the delete stays queued")
    }

    @Test
    fun `sync drains an offline-created routine and the pull does not duplicate it`() = runTest {
        whenever(tokenStore.getUserId()).thenReturn("u")
        val saved = RoutineOut(id = "srv-9", userId = "u", name = "Push Day", source = "manual", createdAt = "t")
        // Create fails offline; the drain retry succeeds; the pull returns the now-synced routine.
        whenever(api.createRoutine(any())).thenThrow(RuntimeException("offline")).thenReturn(saved)
        whenever(api.getRoutines()).thenReturn(listOf(saved))

        repo.createRoutine(RoutineCreate(name = "Push Day"))
        assertTrue(dao.observeAll().first().single().syncPending)

        repo.sync()

        val rows = dao.observeAll().first()
        assertEquals(1, rows.size, "the drained row and the pulled row must not double up")
        assertEquals("srv-9", rows.single().serverId)
        assertFalse(rows.single().syncPending)
    }
}

private class FakeRoutineDao : WorkoutRoutineDao {
    private val rows = MutableStateFlow<Map<String, WorkoutRoutineEntity>>(emptyMap())

    override fun observeAll(): Flow<List<WorkoutRoutineEntity>> =
        rows.map { m -> m.values.filter { !it.pendingDelete }.sortedByDescending { it.createdAt } }

    override suspend fun getById(id: String): WorkoutRoutineEntity? = rows.value[id]

    override suspend fun getByServerId(serverId: String): WorkoutRoutineEntity? =
        rows.value.values.firstOrNull { it.serverId == serverId }

    override suspend fun upsert(routine: WorkoutRoutineEntity) {
        rows.value = rows.value + (routine.id to routine)
    }

    override suspend fun upsertAll(routines: List<WorkoutRoutineEntity>) {
        rows.value = rows.value + routines.associateBy { it.id }
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun getUnsynced(): List<WorkoutRoutineEntity> =
        rows.value.values.filter { it.syncPending && !it.pendingDelete }

    override suspend fun getPendingDeletes(): List<WorkoutRoutineEntity> =
        rows.value.values.filter { it.pendingDelete }

    override suspend fun syncedServerIds(): List<String> =
        rows.value.values.filter { it.serverId != null && !it.syncPending && !it.pendingDelete }
            .mapNotNull { it.serverId }
}

private class FakeExerciseDao : RoutineExerciseDao {
    private val rows = mutableListOf<RoutineExerciseEntity>()

    override suspend fun getByRoutineId(routineId: String): List<RoutineExerciseEntity> =
        rows.filter { it.routineId == routineId }.sortedBy { it.order }

    override suspend fun upsertAll(exercises: List<RoutineExerciseEntity>) {
        rows.addAll(exercises)
    }

    override suspend fun deleteByRoutineId(routineId: String) {
        rows.removeAll { it.routineId == routineId }
    }
}
