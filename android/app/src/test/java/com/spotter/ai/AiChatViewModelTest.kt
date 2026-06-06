package com.spotter.ai

import com.spotter.data.local.dao.ChatMessageDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.ChatMessageEntity
import com.spotter.data.local.entity.WorkoutSessionEntity
import com.spotter.data.model.ChatResponse
import com.spotter.data.model.PlannedExerciseIn
import com.spotter.data.model.PlanOut
import com.spotter.data.model.ProgramOut
import com.spotter.data.model.SuggestedPlan
import com.spotter.data.model.SuggestedProgram
import com.spotter.data.model.SuggestedProgramDay
import androidx.lifecycle.SavedStateHandle
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.PlanRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.ui.ai.AiChatViewModel
import com.spotter.util.AppPreferences
import com.spotter.util.UiState
import com.spotter.util.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// In-memory DAO for tests
private class FakeChatMessageDao : ChatMessageDao {
    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    override fun getAllMessages(): Flow<List<ChatMessageEntity>> = _messages.asStateFlow()
    override suspend fun insert(message: ChatMessageEntity) {
        _messages.value = _messages.value + message
    }
    override suspend fun clearAll() { _messages.value = emptyList() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var aiRepository: AiRepository
    private lateinit var planRepository: PlanRepository
    private lateinit var programRepository: ProgramRepository
    private lateinit var appPreferences: AppPreferences
    private lateinit var fakeChatDao: FakeChatMessageDao
    private lateinit var viewModel: AiChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        aiRepository = mock()
        planRepository = mock()
        programRepository = mock()
        appPreferences = mock()
        fakeChatDao = FakeChatMessageDao()
        whenever(appPreferences.userProfile).thenReturn(flowOf(UserProfile()))
        viewModel = AiChatViewModel(
            aiRepository, planRepository, programRepository, fakeChatDao, mock(), appPreferences,
            SavedStateHandle(),
        )
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
                PlannedExerciseIn(exerciseId = "ex-uuid-1", targetSets = 3, targetReps = 8)
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

    @Test
    fun `send prefers program over plan when both present`() = runTest(testDispatcher) {
        val plan = SuggestedPlan(name = "Plan", exercises = emptyList())
        val program = SuggestedProgram(
            name = "PPL",
            days = listOf(SuggestedProgramDay(label = "Push", exercises = emptyList())),
        )
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "Here's your split.", suggestedPlan = plan, suggestedProgram = program)
        )

        viewModel.send("give me a ppl program")
        advanceTimeBy(200)

        assertNotNull(viewModel.pendingProgram.value)
        assertEquals("PPL", viewModel.pendingProgram.value?.name)
        assertNull(viewModel.pendingPlan.value)
    }

    @Test
    fun `saveProgram accepts program, clears pending, emits programSaved`() = runTest(testDispatcher) {
        val program = SuggestedProgram(
            name = "PPL",
            days = listOf(SuggestedProgramDay(label = "Push", exercises = emptyList())),
        )
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "split", suggestedProgram = program))
        whenever(aiRepository.acceptProgram(any()))
            .thenReturn(ProgramOut(id = "prog-1", name = "PPL", isActive = true))

        viewModel.send("ppl")
        advanceTimeBy(200)

        val saved = mutableListOf<String>()
        val job = launch { viewModel.programSaved.collect { saved.add(it) } }

        viewModel.saveProgram()
        advanceTimeBy(200)

        assertNull(viewModel.pendingProgram.value)
        assertEquals(listOf("PPL"), saved)
        job.cancel()
    }

    @Test
    fun `dismissProgram clears pendingProgram`() = runTest(testDispatcher) {
        val program = SuggestedProgram(name = "P", days = emptyList())
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "ok", suggestedProgram = program))
        viewModel.send("go")
        advanceTimeBy(200)
        assertNotNull(viewModel.pendingProgram.value)

        viewModel.dismissProgram()

        assertNull(viewModel.pendingProgram.value)
    }

    private fun httpException(code: Int): HttpException {
        val body = "{\"detail\":\"x\"}".toResponseBody("application/json".toMediaTypeOrNull())
        return HttpException(Response.error<Any>(code, body))
    }

    @Test
    fun `send maps HTTP 502 to a friendly message`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenThrow(httpException(502))

        viewModel.send("hello")
        advanceTimeBy(200)

        assertEquals(
            "The AI service is briefly unavailable. Please try again in a moment.",
            (viewModel.sendState.value as UiState.Error).message,
        )
    }

    @Test
    fun `send maps HTTP 504 to a model-loading message`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenThrow(httpException(504))

        viewModel.send("hello")
        advanceTimeBy(200)

        assertEquals(
            "The model is still loading — give it a moment and try again.",
            (viewModel.sendState.value as UiState.Error).message,
        )
    }

    @Test
    fun `send rejects over-length input without calling the repository`() = runTest(testDispatcher) {
        viewModel.send("x".repeat(2001))
        advanceTimeBy(200)

        assertIs<UiState.Error>(viewModel.sendState.value)
        assertEquals(0, viewModel.messages.value.size)
        verify(aiRepository, never()).chat(any())
    }

    @Test
    fun `blank reply inserts no assistant message and sets an error`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(ChatResponse(reply = ""))

        viewModel.send("hello")
        advanceTimeBy(200)

        // Only the user message persisted — no empty assistant bubble.
        assertEquals(1, viewModel.messages.value.size)
        assertEquals("user", viewModel.messages.value[0].role)
        assertIs<UiState.Error>(viewModel.sendState.value)
    }

    @Test
    fun `in-workout chat does not surface a Save card`() = runTest(testDispatcher) {
        val sessionDao = mock<WorkoutSessionDao>()
        val entity = WorkoutSessionEntity(
            id = "local-1", userId = "u", planId = null, date = "2026-06-01",
            status = "in_progress", durationSeconds = null, note = null, serverId = "server-1",
        )
        whenever(sessionDao.getById("local-1")).thenReturn(entity)
        val program = SuggestedProgram(
            name = "PPL",
            days = listOf(SuggestedProgramDay(label = "Push", exercises = emptyList())),
        )
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "Here's some advice.", suggestedProgram = program))
        val vm = AiChatViewModel(
            aiRepository, planRepository, programRepository, fakeChatDao, sessionDao, appPreferences,
            SavedStateHandle(mapOf("sessionId" to "local-1")),
        )

        vm.send("build me a program")
        advanceTimeBy(200)

        assertNull(vm.pendingProgram.value)
        assertNull(vm.pendingPlan.value)
    }

    @Test
    fun `clearHistory empties messages`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(ChatResponse(reply = "Hi"))
        viewModel.send("hello")
        advanceTimeBy(200)
        assertEquals(2, viewModel.messages.value.size)

        viewModel.clearHistory()
        advanceTimeBy(200)

        assertEquals(0, viewModel.messages.value.size)
    }
}
