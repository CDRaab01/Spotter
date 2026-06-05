package com.spotter.program

import com.spotter.data.model.AcceptProgramRequest
import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.ProgramOut
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.PlanRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.ui.program.PresetDay
import com.spotter.ui.program.PresetExercise
import com.spotter.ui.program.PresetProgram
import com.spotter.ui.program.ProgramPresetsViewModel
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramPresetsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var exerciseRepository: ExerciseRepository
    private lateinit var aiRepository: AiRepository
    private lateinit var programRepository: ProgramRepository
    private lateinit var planRepository: PlanRepository
    private lateinit var viewModel: ProgramPresetsViewModel

    private val preset = PresetProgram(
        id = "test",
        displayName = "Test Program",
        description = "d",
        days = listOf(
            PresetDay(
                "Day A",
                listOf(
                    PresetExercise("Bench Press", 5, 5),
                    PresetExercise("Unknown Lift", 3, 8),  // unresolved → dropped
                ),
            ),
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        exerciseRepository = mock()
        aiRepository = mock()
        programRepository = mock()
        planRepository = mock()
        viewModel = ProgramPresetsViewModel(
            exerciseRepository, aiRepository, programRepository, planRepository,
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `applyPreset resolves names, drops unknowns, and accepts the program`() =
        runTest(testDispatcher) {
            whenever(exerciseRepository.search(any())).thenReturn(
                listOf(ExerciseOut(id = "bench-id", name = "Bench Press"))
            )
            whenever(aiRepository.acceptProgram(any()))
                .thenReturn(ProgramOut(id = "p1", name = "Test Program", isActive = true))

            val applied = mutableListOf<String>()
            val job = launch { viewModel.applied.collect { applied.add(it) } }

            viewModel.applyPreset(preset)
            advanceTimeBy(200)

            val captor = argumentCaptor<AcceptProgramRequest>()
            verify(aiRepository).acceptProgram(captor.capture())
            val req = captor.firstValue
            assertEquals("Test Program", req.name)
            assertEquals(1, req.days.size)
            // Only the resolvable exercise survives, mapped to its id.
            assertEquals(1, req.days[0].exercises.size)
            assertEquals("bench-id", req.days[0].exercises[0].exerciseId)
            assertEquals(listOf("Test Program"), applied)
            verify(programRepository).sync()
            verify(planRepository).sync()
            job.cancel()
        }

    @Test
    fun `applyPreset emits error and does not accept when nothing resolves`() =
        runTest(testDispatcher) {
            whenever(exerciseRepository.search(any())).thenReturn(emptyList())

            val errors = mutableListOf<String>()
            val job = launch { viewModel.error.collect { errors.add(it) } }

            viewModel.applyPreset(preset)
            advanceTimeBy(200)

            verify(aiRepository, never()).acceptProgram(any())
            assertEquals(1, errors.size)
            job.cancel()
        }
}
