package com.spotter.plan

import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.PlannedExerciseIn
import com.spotter.data.model.PlannedExerciseOut
import com.spotter.data.model.PlanOut
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.SessionOut
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.PlanRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.plan.DraftExercise
import com.spotter.ui.plan.PlanDetailViewModel
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
class PlanDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var planRepository: PlanRepository
    private lateinit var exerciseRepository: ExerciseRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var viewModel: PlanDetailViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        planRepository = mock()
        exerciseRepository = mock()
        sessionRepository = mock()
        viewModel = PlanDetailViewModel(planRepository, exerciseRepository, sessionRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakePlan() = PlanOut(
        id = "plan-1",
        userId = "user-1",
        name = "My Plan",
        source = "manual",
        createdAt = "2026-06-01T00:00:00Z",
        exercises = listOf(
            PlannedExerciseOut(
                id = "pe-1",
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
    fun `loadPlan transitions to Success`() = runTest(testDispatcher) {
        val plan = fakePlan()
        whenever(planRepository.getPlan("plan-1")).thenReturn(plan)

        viewModel.loadPlan("plan-1")
        advanceTimeBy(200)

        assertIs<UiState.Success<PlanOut>>(viewModel.plan.value)
        assertEquals(plan, (viewModel.plan.value as UiState.Success).data)
    }

    @Test
    fun `startEdit populates draftExercises from plan`() = runTest(testDispatcher) {
        val plan = fakePlan()
        whenever(planRepository.getPlan("plan-1")).thenReturn(plan)

        viewModel.loadPlan("plan-1")
        advanceTimeBy(200)

        viewModel.startEdit()

        assertEquals(1, viewModel.draftExercises.value.size)
        assertEquals("ex-1", viewModel.draftExercises.value[0].exerciseId)
        assertEquals("Squat", viewModel.draftExercises.value[0].name)
        assertTrue(viewModel.isEditing.value)
    }

    @Test
    fun `cancelEdit clears isEditing`() = runTest(testDispatcher) {
        val plan = fakePlan()
        whenever(planRepository.getPlan("plan-1")).thenReturn(plan)

        viewModel.loadPlan("plan-1")
        advanceTimeBy(200)
        viewModel.startEdit()
        assertTrue(viewModel.isEditing.value)

        viewModel.cancelEdit()

        assertFalse(viewModel.isEditing.value)
        assertEquals(emptyList(), viewModel.draftExercises.value)
    }

    @Test
    fun `addExercise appends to draftExercises`() = runTest(testDispatcher) {
        val plan = fakePlan()
        whenever(planRepository.getPlan("plan-1")).thenReturn(plan)

        viewModel.loadPlan("plan-1")
        advanceTimeBy(200)
        viewModel.startEdit()

        val newEx = ExerciseOut(id = "ex-2", name = "Deadlift", muscleGroup = "back", equipment = "barbell")
        viewModel.addExercise(newEx)

        assertEquals(2, viewModel.draftExercises.value.size)
        assertEquals("ex-2", viewModel.draftExercises.value[1].exerciseId)
    }

    @Test
    fun `removeExercise removes by index`() = runTest(testDispatcher) {
        val plan = fakePlan()
        whenever(planRepository.getPlan("plan-1")).thenReturn(plan)

        viewModel.loadPlan("plan-1")
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
    fun `saveEdits calls repository and reloads plan`() = runTest(testDispatcher) {
        val plan = fakePlan()
        whenever(planRepository.getPlan("plan-1")).thenReturn(plan)
        whenever(planRepository.updateExercises(any(), any())).thenReturn(plan)

        viewModel.loadPlan("plan-1")
        advanceTimeBy(200)
        viewModel.startEdit()

        viewModel.saveEdits("plan-1")
        advanceTimeBy(200)

        verify(planRepository).updateExercises(any(), any())
        assertFalse(viewModel.isEditing.value)
    }
}
