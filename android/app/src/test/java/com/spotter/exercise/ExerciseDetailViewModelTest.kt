package com.spotter.exercise

import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.ExerciseProgressPoint
import com.spotter.data.model.PersonalRecord
import com.spotter.data.remote.ApiService
import com.spotter.data.repository.ExerciseRepository
import com.spotter.ui.exercise.ExerciseDetailViewModel
import com.spotter.ui.exercise.ExerciseHistory
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
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ExerciseRepository
    private lateinit var api: ApiService
    private lateinit var viewModel: ExerciseDetailViewModel

    private val points = listOf(
        ExerciseProgressPoint(date = "2026-07-20", maxWeight = 115.0, maxReps = 8, est1rm = 143.0),
        ExerciseProgressPoint(date = "2026-07-27", maxWeight = 125.0, maxReps = 7, est1rm = 154.0),
    )
    private val record = PersonalRecord(
        exerciseId = "bench-id", exerciseName = "Bench Press", maxWeight = 125.0,
        maxWeightReps = 7, bestEst1rm = 154.0, bestVolume = 920.0, achievedOn = "2026-07-27",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
        api = mock()
        viewModel = ExerciseDetailViewModel(repository, api)
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

    @Test
    fun `load pulls the progress points and this exercise's record`() = runTest(testDispatcher) {
        whenever(repository.getExercise("bench-id"))
            .thenReturn(ExerciseOut(id = "bench-id", name = "Bench Press"))
        whenever(api.getExerciseProgress("bench-id")).thenReturn(points)
        whenever(api.getPersonalRecords()).thenReturn(
            listOf(record.copy(exerciseId = "squat-id"), record)
        )

        viewModel.load("bench-id")
        advanceTimeBy(200)

        val history = assertIs<UiState.Success<ExerciseHistory>>(viewModel.history.value).data
        assertEquals(points, history.points)
        assertEquals("bench-id", history.record?.exerciseId)
    }

    @Test
    fun `no record for this exercise is not an error`() = runTest(testDispatcher) {
        whenever(repository.getExercise("bench-id"))
            .thenReturn(ExerciseOut(id = "bench-id", name = "Bench Press"))
        whenever(api.getExerciseProgress("bench-id")).thenReturn(points)
        whenever(api.getPersonalRecords()).thenThrow(RuntimeException("boom"))

        viewModel.load("bench-id")
        advanceTimeBy(200)

        val history = assertIs<UiState.Success<ExerciseHistory>>(viewModel.history.value).data
        assertEquals(points, history.points)
        assertNull(history.record)
    }

    @Test
    fun `history errors on its own without taking the exercise down`() = runTest(testDispatcher) {
        whenever(repository.getExercise("bench-id"))
            .thenReturn(ExerciseOut(id = "bench-id", name = "Bench Press"))
        whenever(api.getExerciseProgress("bench-id")).thenAnswer { throw IOException("offline") }

        viewModel.load("bench-id")
        advanceTimeBy(200)

        assertIs<UiState.Success<ExerciseOut>>(viewModel.exercise.value)
        assertIs<UiState.Error>(viewModel.history.value)
    }
}
