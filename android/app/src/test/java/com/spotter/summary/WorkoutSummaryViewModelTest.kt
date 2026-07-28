package com.spotter.summary

import androidx.lifecycle.SavedStateHandle
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.WorkoutSessionEntity
import com.spotter.data.model.DebriefOut
import com.spotter.data.repository.AiRepository
import com.spotter.ui.summary.WorkoutSummaryViewModel
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.wheneverBlocking
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The post-workout debrief is best-effort by contract: the summary screen renders completely
 * without it, and a failure must degrade to "no card" — never an error surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutSummaryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var aiRepository: AiRepository
    private lateinit var sessionDao: WorkoutSessionDao

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        aiRepository = mock()
        sessionDao = mock()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(sessionId: String? = "local-1") = WorkoutSummaryViewModel(
        aiRepository,
        sessionDao,
        SavedStateHandle(if (sessionId == null) emptyMap() else mapOf("sessionId" to sessionId)),
    )

    private fun session(serverId: String?) = WorkoutSessionEntity(
        id = "local-1",
        userId = "u1",
        routineId = "r1",
        date = "2026-07-28",
        status = "completed",
        durationSeconds = 3600,
        note = null,
        serverId = serverId,
    )

    @Test
    fun `a landed debrief surfaces the coach prose`() = runTest(testDispatcher) {
        wheneverBlocking { sessionDao.getById("local-1") }.thenReturn(session("server-1"))
        wheneverBlocking { aiRepository.debriefSession("server-1") }
            .thenReturn(DebriefOut(debrief = "  Solid session — bench moved well.  "))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.debrief.value
        assertIs<UiState.Success<String>>(state)
        assertEquals("Solid session — bench moved well.", state.data)
    }

    @Test
    fun `a failed debrief leaves no card and no error state`() = runTest(testDispatcher) {
        wheneverBlocking { sessionDao.getById("local-1") }.thenReturn(session("server-1"))
        wheneverBlocking { aiRepository.debriefSession(any()) }
            .thenAnswer { throw IOException("LM Studio unreachable") }

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Idle == the card is omitted entirely; the rest of the summary is nav-arg driven and
        // therefore unaffected.
        assertEquals(UiState.Idle, viewModel.debrief.value)
    }

    @Test
    fun `a blank debrief renders no card`() = runTest(testDispatcher) {
        wheneverBlocking { sessionDao.getById("local-1") }.thenReturn(session("server-1"))
        wheneverBlocking { aiRepository.debriefSession("server-1") }
            .thenReturn(DebriefOut(debrief = "   "))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(UiState.Idle, viewModel.debrief.value)
    }

    @Test
    fun `an unsynced session never asks for a debrief`() = runTest(testDispatcher) {
        wheneverBlocking { sessionDao.getById("local-1") }.thenReturn(session(serverId = null))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(UiState.Idle, viewModel.debrief.value)
        verifyNoInteractions(aiRepository)
    }

    @Test
    fun `no session id on the route means no request at all`() = runTest(testDispatcher) {
        val viewModel = createViewModel(sessionId = null)
        advanceUntilIdle()

        assertEquals(UiState.Idle, viewModel.debrief.value)
        verifyNoInteractions(aiRepository)
        verifyNoInteractions(sessionDao)
    }
}
