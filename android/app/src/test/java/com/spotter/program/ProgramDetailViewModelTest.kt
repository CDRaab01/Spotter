package com.spotter.program

import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.entity.ProgramDayEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.data.model.ProgramDayIn
import com.spotter.data.model.ProgramDaysUpdate
import com.spotter.data.model.ProgramOut
import com.spotter.data.repository.RoutineRepository
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
    private lateinit var routineRepository: RoutineRepository
    private lateinit var routineExerciseDao: RoutineExerciseDao
    private lateinit var viewModel: ProgramDetailViewModel

    private val pushRoutine = WorkoutRoutineEntity(
        id = "routine1", userId = "u1", name = "Push", source = "manual", createdAt = "2026-01-01",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        programRepository = mock()
        routineRepository = mock()
        routineExerciseDao = mock { onBlocking { getByRoutineId(any()) } doReturn emptyList() }
        whenever(routineRepository.routines).thenReturn(flowOf(listOf(pushRoutine)))
        viewModel = ProgramDetailViewModel(programRepository, routineRepository, routineExerciseDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `addDay appends a day with the typed label`() {
        viewModel.addDay(pushRoutine, "Push Day")
        assertEquals(1, viewModel.days.value.size)
        assertEquals("Push Day", viewModel.days.value[0].label)
        assertEquals("routine1", viewModel.days.value[0].routineId)
    }

    @Test
    fun `addDay falls back to routine name when label is blank`() {
        viewModel.addDay(pushRoutine, "   ")
        assertEquals("Push", viewModel.days.value[0].label)
    }

    @Test
    fun `removeDay drops the day at the index`() {
        viewModel.addDay(pushRoutine, "A")
        viewModel.addDay(pushRoutine, "B")
        viewModel.removeDay(0)
        assertEquals(listOf("B"), viewModel.days.value.map { it.label })
    }

    @Test
    fun `moveDay swaps adjacent days`() {
        viewModel.addDay(pushRoutine, "A")
        viewModel.addDay(pushRoutine, "B")
        viewModel.moveDay(0, 1)
        assertEquals(listOf("B", "A"), viewModel.days.value.map { it.label })
    }

    @Test
    fun `load populates days and name from repository`() = runTest(testDispatcher) {
        whenever(programRepository.programName(any())).thenReturn("PPL")
        whenever(programRepository.daysFor(any())).thenReturn(
            listOf(
                ProgramDayEntity("d1", "prog1", "routine1", "Push", 0, "Push"),
                ProgramDayEntity("d2", "prog1", "routine2", "Pull", 1, "Pull"),
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
        viewModel.addDay(pushRoutine, "Push")
        viewModel.addDay(pushRoutine, "Pull")

        viewModel.save()
        advanceTimeBy(200)

        verify(programRepository).replaceDays(
            eq("prog1"),
            eq(
                ProgramDaysUpdate(
                    listOf(
                        ProgramDayIn(routineId = "routine1", label = "Push", order = 0),
                        ProgramDayIn(routineId = "routine1", label = "Pull", order = 1),
                    )
                )
            ),
        )
    }
}
