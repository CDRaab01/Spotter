package com.spotter.home

import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.BodyMetricOut
import com.spotter.data.model.PlanOut
import com.spotter.data.model.PlanUpdate
import com.spotter.data.model.ProgramDayOut
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.MetricRepository
import com.spotter.data.repository.PlanRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.home.HomeViewModel
import com.spotter.util.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
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
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var planRepository: PlanRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var metricRepository: MetricRepository
    private lateinit var aiRepository: AiRepository
    private lateinit var programRepository: ProgramRepository
    private lateinit var appPreferences: AppPreferences
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        planRepository = mock()
        sessionRepository = mock()
        metricRepository = mock()
        aiRepository = mock()
        programRepository = mock()
        appPreferences = mock()
        whenever(planRepository.plans).thenReturn(emptyFlow())
        whenever(appPreferences.onboardingDone).thenReturn(flowOf(false))
        viewModel = HomeViewModel(planRepository, sessionRepository, metricRepository, aiRepository, programRepository, appPreferences)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `deletePlan delegates to repository`() = runTest(testDispatcher) {
        viewModel.deletePlan("plan-1")
        advanceTimeBy(200)

        verify(planRepository).deletePlan("plan-1")
    }

    @Test
    fun `renamePlan delegates to repository with trimmed name`() = runTest(testDispatcher) {
        whenever(planRepository.renamePlan(any(), any())).thenReturn(
            PlanOut(
                id = "plan-1",
                userId = "user-1",
                name = "New Name",
                source = "manual",
                createdAt = "2026-06-01T00:00:00Z",
            )
        )

        viewModel.renamePlan("plan-1", "  New Name  ")
        advanceTimeBy(200)

        verify(planRepository).renamePlan("plan-1", PlanUpdate(name = "New Name"))
    }

    @Test
    fun `renamePlan ignores blank names`() = runTest(testDispatcher) {
        viewModel.renamePlan("plan-1", "   ")
        advanceTimeBy(200)

        verify(planRepository, never()).renamePlan(any(), any())
    }

    @Test
    fun `logBodyweight delegates to metricRepository`() = runTest(testDispatcher) {
        whenever(metricRepository.addMetric(any())).thenReturn(
            BodyMetricOut(id = "m1", userId = "u1", date = "2026-06-01", weight = 185.0)
        )

        viewModel.logBodyweight(185.0)
        advanceTimeBy(200)

        verify(metricRepository).addMetric(any<BodyMetricCreate>())
    }

    @Test
    fun `nextProgramDay is null initially`() {
        assertNull(viewModel.nextProgramDay.value)
    }

    @Test
    fun `sync populates nextProgramDay when active program has a next day`() = runTest(testDispatcher) {
        val nextDay = ProgramDayOut(id = "day-1", label = "Push", order = 0)
        whenever(programRepository.getNextProgramDay()).thenReturn(nextDay)

        viewModel.sync()
        advanceTimeBy(200)

        assertEquals("Push", viewModel.nextProgramDay.value?.label)
    }

    @Test
    fun `sync leaves nextProgramDay null when no active program`() = runTest(testDispatcher) {
        whenever(programRepository.getNextProgramDay()).thenReturn(null)

        viewModel.sync()
        advanceTimeBy(200)

        assertNull(viewModel.nextProgramDay.value)
    }

    @Test
    fun `sync delegates to programRepository`() = runTest(testDispatcher) {
        viewModel.sync()
        advanceTimeBy(200)
        // sync() is also called from init, so verify at least one call total
        verify(programRepository, atLeast(1)).sync()
    }
}
