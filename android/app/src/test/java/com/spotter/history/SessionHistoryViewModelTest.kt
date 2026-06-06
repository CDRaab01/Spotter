package com.spotter.history

import com.spotter.data.model.SessionSummary
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.history.SessionHistoryViewModel
import com.spotter.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: SessionRepository
    private lateinit var viewModel: SessionHistoryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadSessions transitions to Success`() = runTest(testDispatcher) {
        val sessions = listOf(
            SessionSummary(
                id = "s-1",
                date = "2026-06-01",
                routineName = "My Plan",
                status = "completed",
                durationSeconds = 3600,
                totalSets = 9,
                completedSets = 9,
            )
        )
        whenever(repository.listSessions()).thenReturn(sessions)

        viewModel = SessionHistoryViewModel(repository)
        advanceTimeBy(200)

        assertIs<UiState.Success<List<SessionSummary>>>(viewModel.sessions.value)
        assertEquals(1, (viewModel.sessions.value as UiState.Success).data.size)
        assertEquals("s-1", (viewModel.sessions.value as UiState.Success).data[0].id)
    }

    @Test
    fun `loadSessions transitions to Error on exception`() = runTest(testDispatcher) {
        whenever(repository.listSessions()).thenThrow(RuntimeException("Network error"))

        viewModel = SessionHistoryViewModel(repository)
        advanceTimeBy(200)

        assertIs<UiState.Error>(viewModel.sessions.value)
        assertEquals("Network error", (viewModel.sessions.value as UiState.Error).message)
    }
}
