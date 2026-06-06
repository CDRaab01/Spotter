package com.spotter.plan

import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.RoutineCreate
import com.spotter.data.model.RoutineOut
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.ui.plan.CreateRoutineViewModel
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CreateRoutineViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var routineRepository: RoutineRepository
    private lateinit var exerciseRepository: ExerciseRepository
    private lateinit var viewModel: CreateRoutineViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        routineRepository = mock()
        exerciseRepository = mock()
        viewModel = CreateRoutineViewModel(routineRepository, exerciseRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addExercise appends to exercises list`() = runTest(testDispatcher) {
        val ex = ExerciseOut(id = "ex-1", name = "Squat", muscleGroup = "legs", equipment = "barbell")
        viewModel.addExercise(ex)
        assertEquals(1, viewModel.exercises.value.size)
        assertEquals("ex-1", viewModel.exercises.value[0].exerciseId)
        assertEquals("Squat", viewModel.exercises.value[0].name)
    }

    @Test
    fun `addExercise marks bodyweight exercises correctly`() = runTest(testDispatcher) {
        val ex = ExerciseOut(id = "ex-2", name = "Push-Up", muscleGroup = "chest", equipment = "bodyweight")
        viewModel.addExercise(ex)
        assertEquals(true, viewModel.exercises.value[0].isBodyweight)
    }

    @Test
    fun `removeExercise removes by index`() = runTest(testDispatcher) {
        viewModel.addExercise(ExerciseOut(id = "ex-1", name = "Squat", muscleGroup = null, equipment = null))
        viewModel.addExercise(ExerciseOut(id = "ex-2", name = "Deadlift", muscleGroup = null, equipment = null))

        viewModel.removeExercise(0)

        assertEquals(1, viewModel.exercises.value.size)
        assertEquals("ex-2", viewModel.exercises.value[0].exerciseId)
    }

    @Test
    fun `removeExercise reorders remaining exercises`() = runTest(testDispatcher) {
        viewModel.addExercise(ExerciseOut(id = "ex-1", name = "A", muscleGroup = null, equipment = null))
        viewModel.addExercise(ExerciseOut(id = "ex-2", name = "B", muscleGroup = null, equipment = null))
        viewModel.addExercise(ExerciseOut(id = "ex-3", name = "C", muscleGroup = null, equipment = null))

        viewModel.removeExercise(1)

        assertEquals(0, viewModel.exercises.value[0].order)
        assertEquals(1, viewModel.exercises.value[1].order)
    }

    @Test
    fun `saveRoutine calls routineRepository and navigates back`() = runTest(testDispatcher) {
        val fakeRoutine = RoutineOut(
            id = "p-1",
            userId = "u-1",
            name = "My Routine",
            source = "manual",
            createdAt = "2026-06-01T00:00:00Z",
        )
        whenever(routineRepository.createRoutine(any())).thenReturn(fakeRoutine)

        viewModel.routineName.value = "My Routine"
        viewModel.addExercise(ExerciseOut(id = "ex-1", name = "Squat", muscleGroup = null, equipment = null))

        val events = mutableListOf<Unit>()
        val job = launch { viewModel.navigateBack.collect { events.add(it) } }

        viewModel.saveRoutine()
        advanceTimeBy(200)

        assertEquals(1, events.size)
        verify(routineRepository).createRoutine(any<RoutineCreate>())
        job.cancel()
    }

    @Test
    fun `saveRoutine with blank name does nothing`() = runTest(testDispatcher) {
        viewModel.addExercise(ExerciseOut(id = "ex-1", name = "Squat", muscleGroup = null, equipment = null))

        viewModel.saveRoutine()
        advanceTimeBy(200)

        verify(routineRepository, never()).createRoutine(any())
    }

    @Test
    fun `saveRoutine with no exercises does nothing`() = runTest(testDispatcher) {
        viewModel.routineName.value = "My Routine"

        viewModel.saveRoutine()
        advanceTimeBy(200)

        verify(routineRepository, never()).createRoutine(any())
    }

    @Test
    fun `saveError is null initially`() {
        assertNull(viewModel.saveError.value)
    }

    @Test
    fun `clearError resets saveError`() = runTest(testDispatcher) {
        whenever(routineRepository.createRoutine(any())).thenThrow(RuntimeException("Network error"))

        viewModel.routineName.value = "My Routine"
        viewModel.addExercise(ExerciseOut(id = "ex-1", name = "Squat", muscleGroup = null, equipment = null))
        viewModel.saveRoutine()
        advanceTimeBy(200)

        viewModel.clearError()

        assertNull(viewModel.saveError.value)
    }
}
