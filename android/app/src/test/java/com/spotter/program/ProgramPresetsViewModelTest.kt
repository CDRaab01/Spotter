package com.spotter.program

import com.spotter.data.local.dao.ExerciseDao
import com.spotter.data.local.entity.ExerciseEntity
import com.spotter.data.model.AcceptProgramRequest
import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.ProgramOut
import com.spotter.data.remote.ApiService
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.ui.program.PresetApplyResult
import com.spotter.ui.program.PresetDay
import com.spotter.ui.program.PresetExercise
import com.spotter.ui.program.PresetProgram
import com.spotter.ui.program.ProgramPresetsViewModel
import com.spotter.ui.program.presetAppliedMessage
import com.spotter.ui.program.restDay
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
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramPresetsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var exerciseRepository: ExerciseRepository
    private lateinit var aiRepository: AiRepository
    private lateinit var programRepository: ProgramRepository
    private lateinit var routineRepository: RoutineRepository
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
                    PresetExercise("Unknown Lift", 3, 8),  // unresolved → dropped + reported
                ),
            ),
            restDay(),
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        exerciseRepository = mock()
        aiRepository = mock()
        programRepository = mock()
        routineRepository = mock()
        viewModel = ProgramPresetsViewModel(
            exerciseRepository, aiRepository, programRepository, routineRepository,
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `applyPreset resolves names, drops unknowns, and accepts the program`() =
        runTest(testDispatcher) {
            whenever(exerciseRepository.listAll()).thenReturn(
                listOf(ExerciseOut(id = "bench-id", name = "Bench Press"))
            )
            whenever(aiRepository.acceptProgram(any()))
                .thenReturn(ProgramOut(id = "p1", name = "Test Program", isActive = true))

            val applied = mutableListOf<PresetApplyResult>()
            val job = launch { viewModel.applied.collect { applied.add(it) } }

            viewModel.applyPreset(preset)
            advanceTimeBy(200)

            val captor = argumentCaptor<AcceptProgramRequest>()
            verify(aiRepository).acceptProgram(captor.capture())
            val req = captor.firstValue
            assertEquals("Test Program", req.name)
            // The rest day rides along as a labelled day with no exercises.
            assertEquals(2, req.days.size)
            assertEquals(listOf(0, 1), req.days.map { it.order })
            assertTrue(req.days[1].exercises.isEmpty())
            // Only the resolvable exercise survives, mapped to its id.
            assertEquals(1, req.days[0].exercises.size)
            assertEquals("bench-id", req.days[0].exercises[0].exerciseId)
            // Preset provenance + the activate flag reach the server.
            assertEquals("preset", req.source)
            assertEquals("d", req.description)
            assertTrue(req.activate)
            assertEquals(1, applied.size)
            assertEquals("Test Program", applied[0].programName)
            assertTrue(applied[0].activated)
            // The unresolved lift is surfaced, not silently dropped.
            assertEquals(listOf("Unknown Lift"), applied[0].dropped)
            verify(programRepository).sync()
            verify(routineRepository).sync()
            job.cancel()
        }

    @Test
    fun `applyPreset with activate false asks the server not to activate`() =
        runTest(testDispatcher) {
            whenever(exerciseRepository.listAll()).thenReturn(
                listOf(ExerciseOut(id = "bench-id", name = "Bench Press"))
            )
            whenever(aiRepository.acceptProgram(any()))
                .thenReturn(ProgramOut(id = "p1", name = "Test Program", isActive = false))

            val applied = mutableListOf<PresetApplyResult>()
            val job = launch { viewModel.applied.collect { applied.add(it) } }

            viewModel.applyPreset(preset, activate = false)
            advanceTimeBy(200)

            val captor = argumentCaptor<AcceptProgramRequest>()
            verify(aiRepository).acceptProgram(captor.capture())
            assertEquals(false, captor.firstValue.activate)
            assertEquals(false, applied.single().activated)
            job.cancel()
        }

    @Test
    fun `a training day that resolves to nothing is skipped, never sent as a rest day`() =
        runTest(testDispatcher) {
            val twoDays = preset.copy(
                days = preset.days + PresetDay(
                    "Day B",
                    listOf(PresetExercise("Also Unknown", 3, 8)),
                ),
            )
            whenever(exerciseRepository.listAll()).thenReturn(
                listOf(ExerciseOut(id = "bench-id", name = "Bench Press"))
            )
            whenever(aiRepository.acceptProgram(any()))
                .thenReturn(ProgramOut(id = "p1", name = "Test Program", isActive = true))

            viewModel.applyPreset(twoDays)
            advanceTimeBy(200)

            val captor = argumentCaptor<AcceptProgramRequest>()
            verify(aiRepository).acceptProgram(captor.capture())
            // Day A (partial) + the rest day only — Day B is gone rather than an empty "rest".
            assertEquals(listOf("Day A", "Rest"), captor.firstValue.days.map { it.label })
        }

    @Test
    fun `applied message names what could not be added`() {
        assertEquals(
            "\"Test Program\" added & activated",
            presetAppliedMessage(PresetApplyResult("Test Program", activated = true)),
        )
        assertEquals(
            "\"Test Program\" added · couldn't add Unknown Lift",
            presetAppliedMessage(
                PresetApplyResult("Test Program", activated = false, dropped = listOf("Unknown Lift"))
            ),
        )
    }

    @Test
    fun `applyPreset emits error and does not accept when nothing resolves`() =
        runTest(testDispatcher) {
            whenever(exerciseRepository.listAll()).thenReturn(emptyList())

            val errors = mutableListOf<String>()
            val job = launch { viewModel.error.collect { errors.add(it) } }

            viewModel.applyPreset(preset)
            advanceTimeBy(200)

            verify(aiRepository, never()).acceptProgram(any())
            assertEquals(1, errors.size)
            job.cancel()
        }

    @Test
    fun `applyPreset resolves names offline from the exercise mirror`() =
        runTest(testDispatcher) {
            // A REAL ExerciseRepository over a dead API + a seeded mirror: name→id resolution
            // must come from Room when the server is unreachable.
            val api: ApiService = mock()
            val dao: ExerciseDao = mock()
            whenever(api.searchExercises(any())).thenAnswer { throw IOException("offline") }
            whenever(dao.getAll()).thenReturn(
                listOf(ExerciseEntity("bench-id", "Bench Press", "chest", "barbell"))
            )
            whenever(aiRepository.acceptProgram(any()))
                .thenReturn(ProgramOut(id = "p1", name = "Test Program", isActive = true))
            viewModel = ProgramPresetsViewModel(
                ExerciseRepository(api, dao), aiRepository, programRepository, routineRepository,
            )

            viewModel.applyPreset(preset)
            advanceTimeBy(200)

            val captor = argumentCaptor<AcceptProgramRequest>()
            verify(aiRepository).acceptProgram(captor.capture())
            assertEquals("bench-id", captor.firstValue.days[0].exercises[0].exerciseId)
        }
}
