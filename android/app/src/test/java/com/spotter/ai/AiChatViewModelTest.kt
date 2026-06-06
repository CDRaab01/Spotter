package com.spotter.ai

import com.spotter.data.local.dao.ChatMessageDao
import com.spotter.data.local.entity.ChatMessageEntity
import com.spotter.data.model.ChatResponse
import com.spotter.data.model.RoutineExerciseIn
import com.spotter.data.model.RoutineOut
import com.spotter.data.model.ProgramOut
import com.spotter.data.model.SuggestedRoutine
import com.spotter.data.model.SuggestedProgram
import com.spotter.data.model.SuggestedProgramDay
import androidx.lifecycle.SavedStateHandle
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.RoutineRepository
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
    private lateinit var routineRepository: RoutineRepository
    private lateinit var programRepository: ProgramRepository
    private lateinit var appPreferences: AppPreferences
    private lateinit var fakeChatDao: FakeChatMessageDao
    private lateinit var viewModel: AiChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        aiRepository = mock()
        routineRepository = mock()
        programRepository = mock()
        appPreferences = mock()
        fakeChatDao = FakeChatMessageDao()
        whenever(appPreferences.userProfile).thenReturn(flowOf(UserProfile()))
        viewModel = AiChatViewModel(
            aiRepository, routineRepository, programRepository, fakeChatDao, mock(), appPreferences,
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
            .thenReturn(ChatResponse(reply = "Great! Let's build your routine."))

        viewModel.send("I want to build muscle")
        advanceTimeBy(200)

        val messages = viewModel.messages.value
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("I want to build muscle", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("Great! Let's build your routine.", messages[1].content)
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
    fun `send stores suggestedRoutine when response includes one`() = runTest(testDispatcher) {
        val routine = SuggestedRoutine(
            name = "Upper Body Push",
            exercises = listOf(
                RoutineExerciseIn(exerciseId = "ex-uuid-1", targetSets = 3, targetReps = 8)
            ),
        )
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "Here is your routine.", suggestedRoutine = routine))

        viewModel.send("give me a routine")
        advanceTimeBy(200)

        assertNotNull(viewModel.pendingRoutine.value)
        assertEquals("Upper Body Push", viewModel.pendingRoutine.value?.name)
    }

    @Test
    fun `send without routine leaves pendingRoutine null`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "What equipment do you have?"))

        viewModel.send("I want to get fit")
        advanceTimeBy(200)

        assertNull(viewModel.pendingRoutine.value)
    }

    @Test
    fun `saveRoutine calls routineRepository and emits routineSaved`() = runTest(testDispatcher) {
        val routine = SuggestedRoutine(
            name = "Full Body A",
            exercises = listOf(
                RoutineExerciseIn(exerciseId = "ex-1", targetSets = 3, targetReps = 8)
            ),
        )
        val fakeRoutineOut = RoutineOut(
            id = "p-1",
            userId = "u-1",
            name = "Full Body A",
            source = "ai",
            createdAt = "2026-06-01T00:00:00Z",
        )
        whenever(aiRepository.chat(any())).thenReturn(ChatResponse(reply = "Routine!", suggestedRoutine = routine))
        whenever(routineRepository.createRoutine(any())).thenReturn(fakeRoutineOut)

        viewModel.send("routine")
        advanceTimeBy(200)

        val savedNames = mutableListOf<String>()
        val job = launch { viewModel.routineSaved.collect { savedNames.add(it) } }

        viewModel.saveRoutine()
        advanceTimeBy(200)

        assertNull(viewModel.pendingRoutine.value)
        assertEquals(1, savedNames.size)
        assertEquals("Full Body A", savedNames[0])
        job.cancel()
    }

    @Test
    fun `dismissRoutine clears pendingRoutine`() = runTest(testDispatcher) {
        val routine = SuggestedRoutine(name = "Routine", exercises = emptyList())
        whenever(aiRepository.chat(any())).thenReturn(ChatResponse(reply = "ok", suggestedRoutine = routine))

        viewModel.send("go")
        advanceTimeBy(200)
        assertNotNull(viewModel.pendingRoutine.value)

        viewModel.dismissRoutine()

        assertNull(viewModel.pendingRoutine.value)
    }

    @Test
    fun `send prefers program over routine when both present`() = runTest(testDispatcher) {
        val routine = SuggestedRoutine(name = "Routine", exercises = emptyList())
        val program = SuggestedProgram(
            name = "PPL",
            days = listOf(SuggestedProgramDay(label = "Push", exercises = emptyList())),
        )
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "Here's your split.", suggestedRoutine = routine, suggestedProgram = program)
        )

        viewModel.send("give me a ppl program")
        advanceTimeBy(200)

        assertNotNull(viewModel.pendingProgram.value)
        assertEquals("PPL", viewModel.pendingProgram.value?.name)
        assertNull(viewModel.pendingRoutine.value)
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
