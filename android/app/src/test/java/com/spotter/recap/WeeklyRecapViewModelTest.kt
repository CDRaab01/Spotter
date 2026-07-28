package com.spotter.recap

import com.spotter.data.model.WeeklyRecapOut
import com.spotter.data.model.WeeklyRecapStats
import com.spotter.data.repository.AiRepository
import com.spotter.ui.recap.WeeklyRecapViewModel
import com.spotter.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.wheneverBlocking
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyRecapViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var aiRepository: AiRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        aiRepository = mock()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun recap(narrative: String?) = WeeklyRecapOut(
        weekStart = "2026-07-27",
        stats = WeeklyRecapStats(
            strengthSessions = 3,
            cardioSessions = 1,
            totalVolumeLb = 24500.0,
            activeMinutes = 212,
            prs = 2,
            bodyweightDeltaLb = -1.4,
        ),
        narrative = narrative,
    )

    @Test
    fun `a narrated week loads into success`() = runTest(testDispatcher) {
        wheneverBlocking { aiRepository.weeklyRecap() }.thenReturn(recap("Strong week."))

        val viewModel = WeeklyRecapViewModel(aiRepository)
        advanceUntilIdle()

        val state = viewModel.recap.value
        assertIs<UiState.Success<WeeklyRecapOut>>(state)
        assertEquals("Strong week.", state.data.narrative)
        assertEquals(3, state.data.stats.strengthSessions)
    }

    @Test
    fun `a null narrative is a success with numbers, not an error`() = runTest(testDispatcher) {
        // The LLM being down is the normal degraded path: /ai/recap/weekly still answers 200
        // with the server-computed stats.
        wheneverBlocking { aiRepository.weeklyRecap() }.thenReturn(recap(narrative = null))

        val viewModel = WeeklyRecapViewModel(aiRepository)
        advanceUntilIdle()

        val state = viewModel.recap.value
        assertIs<UiState.Success<WeeklyRecapOut>>(state)
        assertNull(state.data.narrative)
        assertEquals(212, state.data.stats.activeMinutes)
        assertEquals(2, state.data.stats.prs)
        assertEquals(-1.4, state.data.stats.bodyweightDeltaLb)
    }

    @Test
    fun `an unreachable server surfaces the error state`() = runTest(testDispatcher) {
        wheneverBlocking { aiRepository.weeklyRecap() }.thenAnswer { throw IOException("offline") }

        val viewModel = WeeklyRecapViewModel(aiRepository)
        advanceUntilIdle()

        assertIs<UiState.Error>(viewModel.recap.value)
    }

    @Test
    fun `retry after a failure loads the recap`() = runTest(testDispatcher) {
        // Consecutive stubbing (not re-stubbing): the initial load throws, the retry succeeds.
        wheneverBlocking { aiRepository.weeklyRecap() }
            .thenAnswer { throw IOException("offline") }
            .thenReturn(recap("Back online."))

        val viewModel = WeeklyRecapViewModel(aiRepository)
        advanceUntilIdle()
        assertIs<UiState.Error>(viewModel.recap.value)

        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.recap.value
        assertIs<UiState.Success<WeeklyRecapOut>>(state)
        assertEquals("Back online.", state.data.narrative)
    }
}
