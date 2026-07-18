package com.spotter.history

import com.spotter.data.model.SessionSummary
import com.spotter.data.repository.SessionListing
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.history.SessionHistoryViewModel
import com.spotter.util.AppPreferences
import com.spotter.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: SessionRepository
    private lateinit var appPreferences: AppPreferences
    private lateinit var viewModel: SessionHistoryViewModel

    private val sessions = listOf(
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
        appPreferences = mock()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadSessions transitions to Success and stays fresh on a server read`() =
        runTest(testDispatcher) {
            whenever(repository.listSessionsWithFreshness())
                .thenReturn(SessionListing(sessions, fromCache = false))

            viewModel = SessionHistoryViewModel(repository, appPreferences)
            advanceTimeBy(200)

            assertIs<UiState.Success<List<SessionSummary>>>(viewModel.sessions.value)
            assertEquals(1, (viewModel.sessions.value as UiState.Success).data.size)
            assertEquals("s-1", (viewModel.sessions.value as UiState.Success).data[0].id)
            assertNull(viewModel.staleAsOfMs.value)
        }

    @Test
    fun `loadSessions transitions to Error on exception`() = runTest(testDispatcher) {
        // e.g. retrofit2.HttpException — the repository only degrades on IOException, so an
        // HTTP error propagates and must keep erroring here, not silently serve stale rows.
        whenever(repository.listSessionsWithFreshness()).thenThrow(RuntimeException("Network error"))

        viewModel = SessionHistoryViewModel(repository, appPreferences)
        advanceTimeBy(200)

        assertIs<UiState.Error>(viewModel.sessions.value)
        assertEquals("Network error", (viewModel.sessions.value as UiState.Error).message)
    }

    @Test
    fun `a cached listing surfaces staleAsOfMs from the last successful sync`() =
        runTest(testDispatcher) {
            whenever(repository.listSessionsWithFreshness())
                .thenReturn(SessionListing(sessions, fromCache = true))
            whenever(appPreferences.lastSuccessfulSyncMs).thenReturn(flowOf(1234L))

            viewModel = SessionHistoryViewModel(repository, appPreferences)
            advanceTimeBy(200)

            assertIs<UiState.Success<List<SessionSummary>>>(viewModel.sessions.value)
            assertEquals(1234L, viewModel.staleAsOfMs.value)
        }

    @Test
    fun `a cached listing before any successful sync shows no banner`() =
        runTest(testDispatcher) {
            whenever(repository.listSessionsWithFreshness())
                .thenReturn(SessionListing(sessions, fromCache = true))
            whenever(appPreferences.lastSuccessfulSyncMs).thenReturn(flowOf(null))

            viewModel = SessionHistoryViewModel(repository, appPreferences)
            advanceTimeBy(200)

            assertNull(viewModel.staleAsOfMs.value)
        }
}
