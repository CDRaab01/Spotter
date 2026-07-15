package com.spotter.data.repository

import com.spotter.data.local.dao.BodyMetricDao
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.BodyMetricOut
import com.spotter.data.remote.ApiService
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
 * The offline guarantee for the bodyweight log: a weigh-in is written to Room first and never lost,
 * even when the network throws — the pre-fix behavior silently dropped it. Then it drains and is
 * promoted to the acknowledged server row (keyed by server id, so a pull won't duplicate it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetricRepositoryTest {

    private lateinit var dao: FakeBodyMetricDao
    private lateinit var api: ApiService
    private lateinit var repo: MetricRepository

    @Before
    fun setup() {
        dao = FakeBodyMetricDao()
        api = mock()
        repo = MetricRepository(api, dao)
    }

    @Test
    fun `addMetric persists a pending row when the network is down`() = runTest {
        whenever(api.addWeightMetric(any())).thenThrow(RuntimeException("offline"))

        repo.addMetric(BodyMetricCreate(date = "2026-07-15", weight = 80.0))

        val rows = dao.observeAll().first()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(80.0, row.weight)
        assertTrue(row.syncPending, "an offline weigh-in must be queued, not dropped")
        assertNull(row.serverId)
    }

    @Test
    fun `addMetric promotes to the server row when online`() = runTest {
        whenever(api.addWeightMetric(any())).thenReturn(
            BodyMetricOut(id = "srv-1", userId = "u", date = "2026-07-15", weight = 80.0),
        )

        repo.addMetric(BodyMetricCreate(date = "2026-07-15", weight = 80.0))

        val row = dao.observeAll().first().single()
        assertEquals("srv-1", row.id)
        assertEquals("srv-1", row.serverId)
        assertFalse(row.syncPending)
    }

    @Test
    fun `sync drains a queued offline weigh-in without duplicating it`() = runTest {
        val saved = BodyMetricOut(id = "srv-1", userId = "u", date = "2026-07-15", weight = 80.0)
        // First push (during addMetric) fails; the drain retry succeeds.
        whenever(api.addWeightMetric(any())).thenThrow(RuntimeException("offline")).thenReturn(saved)
        whenever(api.getWeightMetrics()).thenReturn(listOf(saved))

        repo.addMetric(BodyMetricCreate(date = "2026-07-15", weight = 80.0))
        assertTrue(dao.observeAll().first().single().syncPending) // queued while offline

        repo.sync()

        val rows = dao.observeAll().first()
        assertEquals(1, rows.size, "the promoted row and the pulled row must not double up")
        assertEquals("srv-1", rows.single().id)
        assertFalse(rows.single().syncPending)
    }
}

/** In-memory [BodyMetricDao] keyed by id, with a live-observable list. */
private class FakeBodyMetricDao : BodyMetricDao {
    private val rows = MutableStateFlow<Map<String, BodyMetricEntity>>(emptyMap())

    override fun observeAll(): Flow<List<BodyMetricEntity>> =
        rows.map { snapshot -> snapshot.values.sortedBy { it.date } }

    override suspend fun upsert(metric: BodyMetricEntity) {
        rows.value = rows.value + (metric.id to metric)
    }

    override suspend fun upsertAll(metrics: List<BodyMetricEntity>) {
        rows.value = rows.value + metrics.associateBy { it.id }
    }

    override suspend fun getUnsynced(): List<BodyMetricEntity> =
        rows.value.values.filter { it.syncPending }.sortedBy { it.date }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }
}
