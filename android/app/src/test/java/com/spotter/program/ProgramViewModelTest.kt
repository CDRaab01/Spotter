package com.spotter.program

import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.model.ProgramCreate
import com.spotter.data.model.ProgramDayIn
import com.spotter.data.model.ProgramOut
import com.spotter.data.model.ProgramUpdate
import com.spotter.data.repository.ProgramRepository
import com.spotter.ui.program.ProgramViewModel
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ProgramRepository
    private lateinit var viewModel: ProgramViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
        whenever(repository.programs).thenReturn(flowOf(emptyList()))
        viewModel = ProgramViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── State ─────────────────────────────────────────────────────────────────

    @Test
    fun `programs emits Success with empty list from repository`() = runTest(testDispatcher) {
        advanceTimeBy(200)
        assertIs<UiState.Success<List<WorkoutProgramEntity>>>(viewModel.programs.value)
        assertEquals(emptyList(), (viewModel.programs.value as UiState.Success).data)
    }

    @Test
    fun `programs emits Success with list from repository`() = runTest(testDispatcher) {
        val programs = listOf(WorkoutProgramEntity("p1", "PPL", false))
        whenever(repository.programs).thenReturn(flowOf(programs))
        viewModel = ProgramViewModel(repository)

        advanceTimeBy(200)

        val state = viewModel.programs.value
        assertIs<UiState.Success<List<WorkoutProgramEntity>>>(state)
        assertEquals("PPL", state.data[0].name)
    }

    @Test
    fun `actionError is null initially`() {
        assertNull(viewModel.actionError.value)
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Test
    fun `createProgram calls repository with trimmed name and days`() = runTest(testDispatcher) {
        val days = listOf(ProgramDayIn(label = "Push", order = 0))
        whenever(repository.createProgram(any())).thenReturn(ProgramOut("p1", "My Program", false))

        viewModel.createProgram("  My Program  ", days)
        advanceTimeBy(200)

        verify(repository).createProgram(ProgramCreate(name = "My Program", days = days))
    }

    @Test
    fun `createProgram ignores blank name`() = runTest(testDispatcher) {
        viewModel.createProgram("   ", emptyList())
        advanceTimeBy(200)

        verify(repository, never()).createProgram(any())
        assertNull(viewModel.actionError.value)
    }

    @Test
    fun `createProgram failure sets actionError`() = runTest(testDispatcher) {
        whenever(repository.createProgram(any())).thenThrow(RuntimeException("Network error"))

        viewModel.createProgram("Test", emptyList())
        advanceTimeBy(200)

        assertEquals("Network error", viewModel.actionError.value)
    }

    // ── Activate ──────────────────────────────────────────────────────────────

    @Test
    fun `activateProgram calls updateProgram with isActive=true`() = runTest(testDispatcher) {
        whenever(repository.updateProgram(any(), any())).thenReturn(
            ProgramOut("p1", "PPL", true)
        )

        viewModel.activateProgram("p1")
        advanceTimeBy(200)

        verify(repository).updateProgram("p1", ProgramUpdate(isActive = true))
    }

    @Test
    fun `activateProgram failure sets actionError`() = runTest(testDispatcher) {
        whenever(repository.updateProgram(any(), any())).thenThrow(RuntimeException("Server error"))

        viewModel.activateProgram("p1")
        advanceTimeBy(200)

        assertNotNull(viewModel.actionError.value)
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    fun `deleteProgram delegates to repository`() = runTest(testDispatcher) {
        viewModel.deleteProgram("p1")
        advanceTimeBy(200)

        verify(repository).deleteProgram("p1")
    }

    @Test
    fun `deleteProgram failure sets actionError`() = runTest(testDispatcher) {
        whenever(repository.deleteProgram(any())).thenThrow(RuntimeException("Not found"))

        viewModel.deleteProgram("p1")
        advanceTimeBy(200)

        assertNotNull(viewModel.actionError.value)
    }

    // ── Error clearing ────────────────────────────────────────────────────────

    @Test
    fun `clearError resets actionError to null`() = runTest(testDispatcher) {
        whenever(repository.createProgram(any())).thenThrow(RuntimeException("err"))
        viewModel.createProgram("name", emptyList())
        advanceTimeBy(200)
        assertNotNull(viewModel.actionError.value)

        viewModel.clearError()

        assertNull(viewModel.actionError.value)
    }
}
