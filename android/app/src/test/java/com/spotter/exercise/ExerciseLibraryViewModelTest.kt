package com.spotter.exercise

import com.spotter.data.model.ExerciseOut
import com.spotter.data.repository.ExerciseRepository
import com.spotter.ui.exercise.ExerciseLibraryViewModel
import com.spotter.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
class ExerciseLibraryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ExerciseRepository
    private lateinit var viewModel: ExerciseLibraryViewModel

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
    fun `initial query loads all exercises into Success`() = runTest(testDispatcher) {
        whenever(repository.search("")).thenReturn(
            listOf(ExerciseOut(id = "e-1", name = "Bench Press", muscleGroup = "chest"))
        )

        viewModel = ExerciseLibraryViewModel(repository)
        val job = launch { viewModel.exercises.collect {} }
        advanceTimeBy(400)

        val state = viewModel.exercises.value
        assertIs<UiState.Success<List<ExerciseOut>>>(state)
        assertEquals("Bench Press", state.data[0].name)
        job.cancel()
    }

    @Test
    fun `query change triggers a filtered search`() = runTest(testDispatcher) {
        whenever(repository.search("")).thenReturn(emptyList())
        whenever(repository.search("squat")).thenReturn(
            listOf(ExerciseOut(id = "e-2", name = "Barbell Squat", muscleGroup = "legs"))
        )

        viewModel = ExerciseLibraryViewModel(repository)
        val job = launch { viewModel.exercises.collect {} }
        advanceTimeBy(400)

        viewModel.onQueryChange("squat")
        advanceTimeBy(400)

        val state = viewModel.exercises.value
        assertIs<UiState.Success<List<ExerciseOut>>>(state)
        assertEquals("Barbell Squat", state.data[0].name)
        job.cancel()
    }

    @Test
    fun `search failure surfaces Error`() = runTest(testDispatcher) {
        whenever(repository.search("")).thenThrow(RuntimeException("Network error"))

        viewModel = ExerciseLibraryViewModel(repository)
        val job = launch { viewModel.exercises.collect {} }
        advanceTimeBy(400)

        val state = viewModel.exercises.value
        assertIs<UiState.Error>(state)
        assertEquals("Network error", state.message)
        job.cancel()
    }
}
