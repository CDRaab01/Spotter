package com.spotter.home

import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.BodyMetricOut
import com.spotter.data.model.PlanOut
import com.spotter.data.model.PlanUpdate
import com.spotter.data.repository.MetricRepository
import com.spotter.data.repository.PlanRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var planRepository: PlanRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var metricRepository: MetricRepository
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        planRepository = mock()
        sessionRepository = mock()
        metricRepository = mock()
        whenever(planRepository.plans).thenReturn(emptyFlow())
        viewModel = HomeViewModel(planRepository, sessionRepository, metricRepository)
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
}
