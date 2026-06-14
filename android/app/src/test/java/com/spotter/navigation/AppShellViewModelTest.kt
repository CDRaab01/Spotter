package com.spotter.navigation

import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.local.entity.SetLogEntity
import com.spotter.data.local.entity.WorkoutSessionEntity
import com.spotter.data.model.CardioPhase
import com.spotter.data.model.CardioStatus
import com.spotter.data.repository.CardioRepository
import com.spotter.ui.cardio.CardioPrograms
import com.spotter.ui.cardio.CardioRunController
import com.spotter.ui.cardio.CardioRunState
import com.spotter.ui.navigation.ActiveBarUi
import com.spotter.ui.navigation.AppShellViewModel
import com.spotter.util.ActiveCardioStore
import com.spotter.util.ActiveWorkoutStore
import com.spotter.util.DeepLinkBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AppShellViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var workoutStore: ActiveWorkoutStore
    private lateinit var cardioStore: ActiveCardioStore
    private lateinit var controller: CardioRunController
    private lateinit var cardioRepo: CardioRepository
    private lateinit var setLogDao: SetLogDao
    private lateinit var deepLinkBus: DeepLinkBus

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        workoutStore = mock()
        cardioStore = mock()
        controller = mock()
        cardioRepo = mock()
        setLogDao = mock()
        deepLinkBus = mock()
        whenever(deepLinkBus.targets).thenReturn(MutableSharedFlow())
        // Defaults: nothing in progress, no live run.
        whenever(workoutStore.activeSession).thenReturn(flowOf(null))
        whenever(cardioStore.activeCardio).thenReturn(flowOf(null))
        whenever(controller.state).thenReturn(MutableStateFlow(null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun workout(id: String = "w1") = WorkoutSessionEntity(
        id = id, userId = "u", routineId = "r", date = "2026-06-14",
        status = "in_progress", durationSeconds = null, note = null,
    )

    private fun cardio(programId: String = CardioPrograms.C25K_ID, elapsed: Int = 754) =
        CardioSessionEntity(
            id = "c1", programId = programId, weekNumber = 1, dayNumber = 1,
            startedAt = "2026-06-14T10:00:00Z", status = CardioStatus.IN_PROGRESS,
            totalElapsedSec = elapsed,
        )

    private fun setLog(num: Int, done: Boolean) = SetLogEntity(
        id = "s$num", sessionId = "w1", exerciseId = "e", setNumber = num,
        reps = 5, weight = 100.0, completed = done, completedAt = null,
    )

    private fun build() = AppShellViewModel(
        workoutStore, cardioStore, controller, cardioRepo, setLogDao, deepLinkBus,
    )

    @Test
    fun `workout bar reports set progress`() = runTest(testDispatcher) {
        whenever(workoutStore.activeSession).thenReturn(flowOf(workout()))
        whenever(setLogDao.observeBySession("w1")).thenReturn(
            flowOf((1..12).map { setLog(it, done = it <= 5) }),
        )

        val vm = build()
        val job = launch { vm.activeBar.collect {} }
        advanceUntilIdle()

        val bar = vm.activeBar.value
        assertIs<ActiveBarUi.Workout>(bar)
        assertEquals(5, bar.doneSets)
        assertEquals(12, bar.totalSets)
        job.cancel()
    }

    @Test
    fun `cardio wins when both a workout and cardio are in progress`() = runTest(testDispatcher) {
        whenever(workoutStore.activeSession).thenReturn(flowOf(workout()))
        whenever(setLogDao.observeBySession("w1")).thenReturn(flowOf(listOf(setLog(1, true))))
        whenever(cardioStore.activeCardio).thenReturn(flowOf(cardio()))

        val vm = build()
        val job = launch { vm.activeBar.collect {} }
        advanceUntilIdle()

        assertIs<ActiveBarUi.Cardio>(vm.activeBar.value)
        job.cancel()
    }

    @Test
    fun `cardio detail is frozen elapsed when controller has no live state`() = runTest(testDispatcher) {
        whenever(cardioStore.activeCardio).thenReturn(flowOf(cardio(elapsed = 754)))
        whenever(controller.state).thenReturn(MutableStateFlow(null))

        val vm = build()
        val job = launch { vm.activeBar.collect {} }
        advanceUntilIdle()

        val bar = vm.activeBar.value
        assertIs<ActiveBarUi.Cardio>(bar)
        assertEquals("Paused · 12:34", bar.detail)
        job.cancel()
    }

    @Test
    fun `cardio detail shows live phase and countdown when running`() = runTest(testDispatcher) {
        whenever(cardioStore.activeCardio).thenReturn(flowOf(cardio()))
        whenever(controller.state).thenReturn(
            MutableStateFlow(
                CardioRunState(
                    intervals = emptyList(), currentIndex = 0, phase = CardioPhase.RUN,
                    intervalElapsedSec = 40, intervalRemainingSec = 80, intervalDurationSec = 120,
                    totalElapsedSec = 200, totalDurationSec = 1800,
                    isPaused = false, isComplete = false, isOpenEnded = false,
                    label = "Couch to 5K", weekDayLabel = "WEEK 1 DAY 1",
                ),
            ),
        )

        val vm = build()
        val job = launch { vm.activeBar.collect {} }
        advanceUntilIdle()

        val bar = vm.activeBar.value
        assertIs<ActiveBarUi.Cardio>(bar)
        assertEquals("Run · 1:20 left", bar.detail)
        job.cancel()
    }

    @Test
    fun `resumeCardio re-arms the controller for a guided session`() = runTest(testDispatcher) {
        val vm = build()
        vm.resumeCardio(cardio(programId = CardioPrograms.C25K_ID))

        verify(controller).startGuided(
            programId = org.mockito.kotlin.eq(CardioPrograms.C25K_ID),
            week = org.mockito.kotlin.eq(1),
            day = org.mockito.kotlin.eq(1),
            intervals = org.mockito.kotlin.any(),
            label = org.mockito.kotlin.any(),
            weekDayLabel = org.mockito.kotlin.any(),
            resume = org.mockito.kotlin.any(),
        )
    }

    @Test
    fun `resumeCardio re-arms a free run`() = runTest(testDispatcher) {
        val vm = build()
        vm.resumeCardio(cardio(programId = CardioPrograms.FREE_RUN_ID))

        verify(controller).startFree(
            openEnded = org.mockito.kotlin.eq(true),
            intervals = org.mockito.kotlin.any(),
            resume = org.mockito.kotlin.any(),
        )
    }
}
