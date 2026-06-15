package com.spotter.util

import com.spotter.data.local.dao.CardioSessionDao
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.model.CardioStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveCardioStoreTest {

    private fun cardio(id: String, startedAt: String) = CardioSessionEntity(
        id = id,
        programId = "c25k",
        weekNumber = 1,
        dayNumber = 1,
        startedAt = startedAt,
        status = CardioStatus.IN_PROGRESS,
        totalElapsedSec = 60,
    )

    @Test
    fun `surfaces only today's in-progress session`() = runTest {
        val dao = mock<CardioSessionDao>()
        val todays = cardio("today", Instant.now().toString())
        val yesterdays = cardio("old", Instant.now().minus(2, ChronoUnit.DAYS).toString())
        whenever(dao.observeInProgress()).thenReturn(flowOf(listOf(yesterdays, todays)))

        val store = ActiveCardioStore(dao)
        assertEquals("today", store.activeCardio.first()?.id)
    }

    @Test
    fun `null when nothing in progress`() = runTest {
        val dao = mock<CardioSessionDao>()
        whenever(dao.observeInProgress()).thenReturn(flowOf(emptyList()))

        val store = ActiveCardioStore(dao)
        assertNull(store.activeCardio.first())
    }

    @Test
    fun `null when only stale past-day sessions remain`() = runTest {
        val dao = mock<CardioSessionDao>()
        val old = cardio("old", Instant.now().minus(3, ChronoUnit.DAYS).toString())
        whenever(dao.observeInProgress()).thenReturn(flowOf(listOf(old)))

        val store = ActiveCardioStore(dao)
        assertNull(store.activeCardio.first())
    }
}
