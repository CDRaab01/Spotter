package com.spotter.program

import com.spotter.data.local.dao.PlannedExerciseDao
import com.spotter.data.local.entity.ProgramDayEntity
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.data.model.ProgramDayIn
import com.spotter.data.model.ProgramDaysUpdate
import com.spotter.data.model.ProgramOut
import com.spotter.data.repository.PlanRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.ui.program.ProgramDetailViewModel
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var programRepository: ProgramRepository
    private lateinit var planRepository: PlanRepository
    private lateinit var plannedExerciseDao: PlannedExerciseDao
    private lateinit var viewModel: ProgramDetailViewModel

    private val pushPlan = WorkoutPlanEntity(
        id = "plan1", userId = "u1", name = "Push", source = "manual", createdAt = "2026-01-01",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        programRepository = mock()
        planRepository = mock()
        plannedExerciseDao = mock { onBlocking { getByPlanId(any()) } doReturn emptyList() }
        whenever(planRepository.plans).thenReturn(flowOf(listOf(pushPlan)))
        viewModel = ProgramDetailViewModel(programRepository, planRepository, plannedExerciseDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addDay appends a day with the typed label`() {
        viewModel.addDay(pushPlan, "Push Day")
        assertEquals(1, viewModel.days.value.size)
        assertEquals("Push Day", viewModel.days.value[0].label)
        assertEquals("plan1", viewModel.days.value[0].planId)
    }

    @Test
    fun `addDay falls back to plan name when label is blank`() {
        viewModel.addDay(pushPlan, "   ")
        assertEquals("Push", viewModel.days.value[0].label)
    }

    @Test
    fun `removeDay drops the day at the index`() {
        viewModel.addDay(pushPlan, "A")
        viewModel.addDay(pushPlan, "B")
        viewModel.removeDay(0)
        assertEquals(listOf("B"), viewModel.days.value.map { it.label })
    }

    @Test
    fun `moveDay swaps adjacent days`() {
        viewModel.addDay(pushPlan, "A")
        viewModel.addDay(pushPlan, "B")
        viewModel.moveDay(0, 1)
        assertEquals(listOf("B", "A"), viewModel.days.value.map { it.label })
    }

    @Test
    fun `load populates days and name from repository`() = runTest(testDispatcher) {
        whenever(programRepository.programName(any())).thenReturn("PPL")
        whenever(programRepository.daysFor(any())).thenReturn(
            listOf(
                ProgramDayEntity("d1", "prog1", "plan1", "Push", 0, "Push"),
                ProgramDayEntity("d2", "prog1", "plan2", "Pull", 1, "Pull"),
            )
        )

        viewModel.load("prog1")
        advanceTimeBy(200)

        assertEquals("PPL", viewModel.programName.value)
        assertEquals(listOf("Push", "Pull"), viewModel.days.value.map { it.label })
    }

    @Test
    fun `save sends days with recomputed order`() = runTest(testDispatcher) {
        whenever(programRepository.daysFor(any())).thenReturn(emptyList())
        whenever(programRepository.replaceDays(any(), any())).thenReturn(
            ProgramOut("prog1", "PPL", false)
        )

        viewModel.load("prog1")
        advanceTimeBy(200)
        viewModel.addDay(pushPlan, "Push")
        viewModel.addDay(pushPlan, "Pull")

        viewModel.save()
        advanceTimeBy(200)

        verify(programRepository).replaceDays(
            eq("prog1"),
            eq(
                ProgramDaysUpdate(
                    listOf(
                        ProgramDayIn(planId = "plan1", label = "Push", order = 0),
                        ProgramDayIn(planId = "plan1", label = "Pull", order = 1),
                    )
                )
            ),
        )
    }
}
