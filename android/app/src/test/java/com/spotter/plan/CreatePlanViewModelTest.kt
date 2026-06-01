package com.spotter.plan

import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.PlanCreate
import com.spotter.data.model.PlanOut
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.PlanRepository
import com.spotter.ui.plan.CreatePlanViewModel
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
class CreatePlanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var planRepository: PlanRepository
    private lateinit var exerciseRepository: ExerciseRepository
    private lateinit var viewModel: CreatePlanViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        planRepository = mock()
        exerciseRepository = mock()
        viewModel = CreatePlanViewModel(planRepository, exerciseRepository)
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
    fun `savePlan calls planRepository and navigates back`() = runTest(testDispatcher) {
        val fakePlan = PlanOut(
            id = "p-1",
            userId = "u-1",
            name = "My Plan",
            source = "manual",
            createdAt = "2026-06-01T00:00:00Z",
        )
        whenever(planRepository.createPlan(any())).thenReturn(fakePlan)

        viewModel.planName.value = "My Plan"
        viewModel.addExercise(ExerciseOut(id = "ex-1", name = "Squat", muscleGroup = null, equipment = null))

        val events = mutableListOf<Unit>()
        val job = launch { viewModel.navigateBack.collect { events.add(it) } }

        viewModel.savePlan()
        advanceTimeBy(200)

        assertEquals(1, events.size)
        verify(planRepository).createPlan(any<PlanCreate>())
        job.cancel()
    }

    @Test
    fun `savePlan with blank name does nothing`() = runTest(testDispatcher) {
        viewModel.addExercise(ExerciseOut(id = "ex-1", name = "Squat", muscleGroup = null, equipment = null))

        viewModel.savePlan()
        advanceTimeBy(200)

        verify(planRepository, never()).createPlan(any())
    }

    @Test
    fun `savePlan with no exercises does nothing`() = runTest(testDispatcher) {
        viewModel.planName.value = "My Plan"

        viewModel.savePlan()
        advanceTimeBy(200)

        verify(planRepository, never()).createPlan(any())
    }

    @Test
    fun `saveError is null initially`() {
        assertNull(viewModel.saveError.value)
    }

    @Test
    fun `clearError resets saveError`() = runTest(testDispatcher) {
        whenever(planRepository.createPlan(any())).thenThrow(RuntimeException("Network error"))

        viewModel.planName.value = "My Plan"
        viewModel.addExercise(ExerciseOut(id = "ex-1", name = "Squat", muscleGroup = null, equipment = null))
        viewModel.savePlan()
        advanceTimeBy(200)

        viewModel.clearError()

        assertNull(viewModel.saveError.value)
    }
}
