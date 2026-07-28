package com.spotter.exercise

import com.spotter.data.model.ExerciseOut
import com.spotter.data.repository.ExerciseRepository
import com.spotter.ui.exercise.ExerciseDetailViewModel
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
class ExerciseDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ExerciseRepository
    private lateinit var viewModel: ExerciseDetailViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
        viewModel = ExerciseDetailViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load transitions to Success with the exercise`() = runTest(testDispatcher) {
        val exercise = ExerciseOut(
            id = "bench-id", name = "Bench Press", muscleGroup = "chest", equipment = "barbell",
            instructions = "Lie on the bench…", secondaryMuscles = "triceps, front delts",
        )
        whenever(repository.getExercise("bench-id")).thenReturn(exercise)

        viewModel.load("bench-id")
        advanceTimeBy(200)

        assertIs<UiState.Success<ExerciseOut>>(viewModel.exercise.value)
        assertEquals(exercise, (viewModel.exercise.value as UiState.Success).data)
    }

    @Test
    fun `load transitions to Error on failure`() = runTest(testDispatcher) {
        whenever(repository.getExercise("nope")).thenThrow(RuntimeException("offline"))

        viewModel.load("nope")
        advanceTimeBy(200)

        assertIs<UiState.Error>(viewModel.exercise.value)
        assertEquals("offline", (viewModel.exercise.value as UiState.Error).message)
    }

    @Test
    fun `a retry after an error can succeed`() = runTest(testDispatcher) {
        whenever(repository.getExercise("bench-id"))
            .thenThrow(RuntimeException("offline"))
            .thenReturn(ExerciseOut(id = "bench-id", name = "Bench Press"))

        viewModel.load("bench-id")
        advanceTimeBy(200)
        assertIs<UiState.Error>(viewModel.exercise.value)

        viewModel.load("bench-id")
        advanceTimeBy(200)
        assertIs<UiState.Success<ExerciseOut>>(viewModel.exercise.value)
    }
}
