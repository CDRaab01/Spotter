package com.spotter.ai

import com.spotter.data.model.ChatResponse
import com.spotter.data.model.PlannedExerciseIn
import com.spotter.data.model.PlanOut
import com.spotter.data.model.SuggestedPlan
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.PlanRepository
import com.spotter.ui.ai.AiChatViewModel
import com.spotter.util.UiState
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
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var aiRepository: AiRepository
    private lateinit var planRepository: PlanRepository
    private lateinit var viewModel: AiChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        aiRepository = mock()
        planRepository = mock()
        viewModel = AiChatViewModel(aiRepository, planRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `send appends user message and assistant reply on success`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "Great! Let's build your plan."))

        viewModel.send("I want to build muscle")
        advanceTimeBy(200)

        val messages = viewModel.messages.value
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("I want to build muscle", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("Great! Let's build your plan.", messages[1].content)
    }

    @Test
    fun `send transitions sendState to Error on failure`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenThrow(RuntimeException("network error"))

        viewModel.send("hello")
        advanceTimeBy(200)

        assertIs<UiState.Error>(viewModel.sendState.value)
        assertEquals("network error", (viewModel.sendState.value as UiState.Error).message)
    }

    @Test
    fun `send ignores blank input`() = runTest(testDispatcher) {
        viewModel.send("   ")
        advanceTimeBy(200)

        assertEquals(0, viewModel.messages.value.size)
        assertIs<UiState.Idle>(viewModel.sendState.value)
    }

    @Test
    fun `startIntake sends the standard onboarding message`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "Hi! I'm Spotter, your personal coach."))

        viewModel.startIntake()
        advanceTimeBy(200)

        val firstMessage = viewModel.messages.value.firstOrNull()
        assertEquals("Hi, I'm ready to get started.", firstMessage?.content)
        assertEquals("user", firstMessage?.role)
    }

    @Test
    fun `clearError resets sendState to Idle`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenThrow(RuntimeException("timeout"))
        viewModel.send("test")
        advanceTimeBy(200)
        assertIs<UiState.Error>(viewModel.sendState.value)

        viewModel.clearError()

        assertIs<UiState.Idle>(viewModel.sendState.value)
    }

    @Test
    fun `messages list starts empty`() {
        assertEquals(emptyList(), viewModel.messages.value)
    }

    @Test
    fun `send stores suggestedPlan when response includes one`() = runTest(testDispatcher) {
        val plan = SuggestedPlan(
            name = "Upper Body Push",
            exercises = listOf(
                PlannedExerciseIn(
                    exerciseId = "ex-uuid-1",
                    targetSets = 3,
                    targetReps = 8,
                )
            ),
        )
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "Here is your plan.", suggestedPlan = plan))

        viewModel.send("give me a plan")
        advanceTimeBy(200)

        assertNotNull(viewModel.pendingPlan.value)
        assertEquals("Upper Body Push", viewModel.pendingPlan.value?.name)
    }

    @Test
    fun `send without plan leaves pendingPlan null`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "What equipment do you have?"))

        viewModel.send("I want to get fit")
        advanceTimeBy(200)

        assertNull(viewModel.pendingPlan.value)
    }

    @Test
    fun `savePlan calls planRepository and emits planSaved`() = runTest(testDispatcher) {
        val plan = SuggestedPlan(
            name = "Full Body A",
            exercises = listOf(
                PlannedExerciseIn(exerciseId = "ex-1", targetSets = 3, targetReps = 8)
            ),
        )
        val fakePlanOut = PlanOut(
            id = "p-1",
            userId = "u-1",
            name = "Full Body A",
            source = "ai",
            createdAt = "2026-06-01T00:00:00Z",
        )
        whenever(aiRepository.chat(any())).thenReturn(ChatResponse(reply = "Plan!", suggestedPlan = plan))
        whenever(planRepository.createPlan(any())).thenReturn(fakePlanOut)

        viewModel.send("plan")
        advanceTimeBy(200)

        val savedNames = mutableListOf<String>()
        val job = launch { viewModel.planSaved.collect { savedNames.add(it) } }

        viewModel.savePlan()
        advanceTimeBy(200)

        assertNull(viewModel.pendingPlan.value)
        assertEquals(1, savedNames.size)
        assertEquals("Full Body A", savedNames[0])
        job.cancel()
    }

    @Test
    fun `dismissPlan clears pendingPlan`() = runTest(testDispatcher) {
        val plan = SuggestedPlan(name = "Plan", exercises = emptyList())
        whenever(aiRepository.chat(any())).thenReturn(ChatResponse(reply = "ok", suggestedPlan = plan))

        viewModel.send("go")
        advanceTimeBy(200)
        assertNotNull(viewModel.pendingPlan.value)

        viewModel.dismissPlan()

        assertNull(viewModel.pendingPlan.value)
    }
}
