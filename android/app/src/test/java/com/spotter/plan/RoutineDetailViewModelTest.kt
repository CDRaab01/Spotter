package com.spotter.plan

import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.RoutineExerciseIn
import com.spotter.data.model.RoutineExerciseOut
import com.spotter.data.model.RoutineOut
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.SessionOut
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.plan.DraftExercise
import com.spotter.ui.plan.RoutineDetailViewModel
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var routineRepository: RoutineRepository
    private lateinit var exerciseRepository: ExerciseRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var viewModel: RoutineDetailViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        routineRepository = mock()
        exerciseRepository = mock()
        sessionRepository = mock()
        viewModel = RoutineDetailViewModel(routineRepository, exerciseRepository, sessionRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeRoutine() = RoutineOut(
        id = "routine-1",
        userId = "user-1",
        name = "My Routine",
        source = "manual",
        createdAt = "2026-06-01T00:00:00Z",
        exercises = listOf(
            RoutineExerciseOut(
                id = "re-1",
                exerciseId = "ex-1",
                targetSets = 3,
                targetReps = 8,
                targetWeight = 135.0,
                isBodyweight = false,
                order = 0,
                exerciseName = "Squat",
            )
        ),
    )

    @Test
    fun `loadRoutine transitions to Success`() = runTest(testDispatcher) {
        val routine = fakeRoutine()
        whenever(routineRepository.getRoutine("routine-1")).thenReturn(routine)

        viewModel.loadRoutine("routine-1")
        advanceTimeBy(200)

        assertIs<UiState.Success<RoutineOut>>(viewModel.routine.value)
        assertEquals(routine, (viewModel.routine.value as UiState.Success).data)
    }

    @Test
    fun `startEdit populates draftExercises from routine`() = runTest(testDispatcher) {
        val routine = fakeRoutine()
        whenever(routineRepository.getRoutine("routine-1")).thenReturn(routine)

        viewModel.loadRoutine("routine-1")
        advanceTimeBy(200)

        viewModel.startEdit()

        assertEquals(1, viewModel.draftExercises.value.size)
        assertEquals("ex-1", viewModel.draftExercises.value[0].exerciseId)
        assertEquals("Squat", viewModel.draftExercises.value[0].name)
        assertTrue(viewModel.isEditing.value)
    }

    @Test
    fun `cancelEdit clears isEditing`() = runTest(testDispatcher) {
        val routine = fakeRoutine()
        whenever(routineRepository.getRoutine("routine-1")).thenReturn(routine)

        viewModel.loadRoutine("routine-1")
        advanceTimeBy(200)
        viewModel.startEdit()
        assertTrue(viewModel.isEditing.value)

        viewModel.cancelEdit()

        assertFalse(viewModel.isEditing.value)
        assertEquals(emptyList(), viewModel.draftExercises.value)
    }

    @Test
    fun `addExercise appends to draftExercises`() = runTest(testDispatcher) {
        val routine = fakeRoutine()
        whenever(routineRepository.getRoutine("routine-1")).thenReturn(routine)

        viewModel.loadRoutine("routine-1")
        advanceTimeBy(200)
        viewModel.startEdit()

        val newEx = ExerciseOut(id = "ex-2", name = "Deadlift", muscleGroup = "back", equipment = "barbell")
        viewModel.addExercise(newEx)

        assertEquals(2, viewModel.draftExercises.value.size)
        assertEquals("ex-2", viewModel.draftExercises.value[1].exerciseId)
    }

    @Test
    fun `removeExercise removes by index`() = runTest(testDispatcher) {
        val routine = fakeRoutine()
        whenever(routineRepository.getRoutine("routine-1")).thenReturn(routine)

        viewModel.loadRoutine("routine-1")
        advanceTimeBy(200)
        viewModel.startEdit()

        val newEx = ExerciseOut(id = "ex-2", name = "Deadlift", muscleGroup = "back", equipment = "barbell")
        viewModel.addExercise(newEx)
        assertEquals(2, viewModel.draftExercises.value.size)

        viewModel.removeExercise(0)

        assertEquals(1, viewModel.draftExercises.value.size)
        assertEquals("ex-2", viewModel.draftExercises.value[0].exerciseId)
    }

    @Test
    fun `saveEdits calls repository and reloads routine`() = runTest(testDispatcher) {
        val routine = fakeRoutine()
        whenever(routineRepository.getRoutine("routine-1")).thenReturn(routine)
        whenever(routineRepository.updateExercises(any(), any())).thenReturn(routine)

        viewModel.loadRoutine("routine-1")
        advanceTimeBy(200)
        viewModel.startEdit()

        viewModel.saveEdits("routine-1")
        advanceTimeBy(200)

        verify(routineRepository).updateExercises(any(), any())
        assertFalse(viewModel.isEditing.value)
    }
}
