package com.spotter.ai

import com.spotter.data.local.dao.ChatMessageDao
import com.spotter.data.local.entity.ChatMessageEntity
import com.spotter.data.model.ChatResponse
import com.spotter.data.model.PendingSuggestions
import com.spotter.data.model.RoutineExerciseIn
import com.spotter.data.model.RoutineOut
import com.spotter.data.model.ProgramOut
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SuggestedAdjustment
import com.spotter.data.model.SuggestedAdjustmentAction
import com.spotter.data.model.SuggestedProfileUpdate
import com.spotter.data.model.SuggestedRoutine
import com.spotter.data.model.SuggestedProgram
import com.spotter.data.model.SuggestedProgramDay
import androidx.lifecycle.SavedStateHandle
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.ProfileRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.data.repository.SessionRepository
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
import kotlinx.serialization.json.Json
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

// In-memory DAO for tests. Survives ViewModel recreation, so a second ViewModel built over the
// same instance is exactly the process-death case.
private class FakeChatMessageDao : ChatMessageDao {
    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    private var nextId = 1L
    override fun getAllMessages(): Flow<List<ChatMessageEntity>> = _messages.asStateFlow()
    override suspend fun insert(message: ChatMessageEntity): Long {
        val id = nextId++
        _messages.value = _messages.value + message.copy(id = id)
        return id
    }
    override suspend fun latestAssistantMessage(): ChatMessageEntity? =
        _messages.value.lastOrNull { it.role == "assistant" }
    override suspend fun updateSuggestions(
        id: Long,
        suggestionsJson: String?,
        suggestionSessionId: String?,
    ) {
        _messages.value = _messages.value.map {
            if (it.id == id) {
                it.copy(suggestionsJson = suggestionsJson, suggestionSessionId = suggestionSessionId)
            } else {
                it
            }
        }
    }
    override suspend fun clearAll() { _messages.value = emptyList() }

    /** Test helper: the envelope currently stored on the newest assistant row (null = none). */
    fun storedSuggestions(): String? = _messages.value.lastOrNull { it.role == "assistant" }?.suggestionsJson
}

@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    /** Mirrors AppModule.provideJson so the persisted envelope round-trips exactly as in the app. */
    private val testJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private lateinit var aiRepository: AiRepository
    private lateinit var routineRepository: RoutineRepository
    private lateinit var programRepository: ProgramRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var sessionDao: WorkoutSessionDao
    private lateinit var appPreferences: AppPreferences
    private lateinit var fakeChatDao: FakeChatMessageDao
    private lateinit var viewModel: AiChatViewModel

    /** What the user already has saved; the merge must carry these through untouched. */
    private val storedProfile = UserProfile(
        experience = "intermediate",
        goal = "build_muscle",
        equipment = "dumbbells up to 50lb, pull-up bar",
        ageGroup = "30_39",
        limitations = "left shoulder",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        aiRepository = mock()
        routineRepository = mock()
        programRepository = mock()
        sessionRepository = mock()
        profileRepository = mock()
        sessionDao = mock()
        appPreferences = mock()
        fakeChatDao = FakeChatMessageDao()
        whenever(appPreferences.userProfile).thenReturn(flowOf(UserProfile()))
        whenever(profileRepository.profile).thenReturn(flowOf(storedProfile))
        viewModel = buildViewModel(SavedStateHandle())
    }

    /** Build a VM; pass a SavedStateHandle with "sessionId" to simulate in-workout chat. */
    private fun buildViewModel(savedStateHandle: SavedStateHandle) = AiChatViewModel(
        aiRepository, routineRepository, programRepository, sessionRepository, profileRepository,
        fakeChatDao, sessionDao, appPreferences, testJson, savedStateHandle,
    )

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

    // ── Live workout adjustments ────────────────────────────────────────────

    private val swapAction = SuggestedAdjustmentAction(
        type = "swap",
        exerciseId = "ex-bench",
        exerciseName = "Bench Press",
        newExerciseId = "ex-db",
        newExerciseName = "DB Bench Press",
        weight = 40.0,
        summary = "Swap Bench Press for DB Bench Press",
    )

    private fun stubSession() = SessionOut(
        id = "local-1", userId = "u-1", date = "2026-06-12", status = "in_progress",
    )

    @Test
    fun `send stores suggestedAdjustment and not routine or program`() = runTest(testDispatcher) {
        val vm = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(
                reply = "Let's swap that.",
                suggestedAdjustment = SuggestedAdjustment(actions = listOf(swapAction)),
            )
        )

        vm.send("I can't do bench press")
        advanceTimeBy(200)

        assertNotNull(vm.pendingAdjustment.value)
        assertEquals(1, vm.pendingAdjustment.value?.actions?.size)
        assertNull(vm.pendingRoutine.value)
        assertNull(vm.pendingProgram.value)
    }

    @Test
    fun `applyAdjustment with future on calls repo, syncs routines, emits count`() = runTest(testDispatcher) {
        val vm = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedAdjustment = SuggestedAdjustment(listOf(swapAction)))
        )
        whenever(sessionRepository.applyAdjustment(any(), any(), any())).thenReturn(stubSession())

        vm.send("swap it")
        advanceTimeBy(200)

        val applied = mutableListOf<Int>()
        val job = launch { vm.adjustmentApplied.collect { applied.add(it) } }

        vm.applyAdjustment(applyToRoutine = true)
        advanceTimeBy(200)

        org.mockito.kotlin.verify(sessionRepository).applyAdjustment("local-1", listOf(swapAction), true)
        org.mockito.kotlin.verify(routineRepository).sync()
        assertNull(vm.pendingAdjustment.value)
        assertEquals(listOf(1), applied)
        job.cancel()
    }

    @Test
    fun `applyAdjustment with future off does not sync routines`() = runTest(testDispatcher) {
        val vm = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedAdjustment = SuggestedAdjustment(listOf(swapAction)))
        )
        whenever(sessionRepository.applyAdjustment(any(), any(), any())).thenReturn(stubSession())

        vm.send("swap")
        advanceTimeBy(200)
        vm.applyAdjustment(applyToRoutine = false)
        advanceTimeBy(200)

        org.mockito.kotlin.verify(sessionRepository).applyAdjustment("local-1", listOf(swapAction), false)
        org.mockito.kotlin.verify(routineRepository, org.mockito.kotlin.never()).sync()
        assertNull(vm.pendingAdjustment.value)
    }

    @Test
    fun `applyAdjustment keeps card on failure`() = runTest(testDispatcher) {
        val vm = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedAdjustment = SuggestedAdjustment(listOf(swapAction)))
        )
        whenever(sessionRepository.applyAdjustment(any(), any(), any()))
            .thenThrow(RuntimeException("network"))

        vm.send("swap")
        advanceTimeBy(200)
        vm.applyAdjustment(applyToRoutine = true)
        advanceTimeBy(200)

        assertIs<UiState.Error>(vm.sendState.value)
        assertNotNull(vm.pendingAdjustment.value)  // retained for retry
    }

    @Test
    fun `dismissAdjustment clears without calling repo`() = runTest(testDispatcher) {
        val vm = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedAdjustment = SuggestedAdjustment(listOf(swapAction)))
        )
        vm.send("swap")
        advanceTimeBy(200)
        assertNotNull(vm.pendingAdjustment.value)

        vm.dismissAdjustment()

        assertNull(vm.pendingAdjustment.value)
        org.mockito.kotlin.verifyNoInteractions(sessionRepository)
    }

    // ── Saved training-profile updates ──────────────────────────────────────

    /** Only equipment changes; every other field must survive the merge untouched. */
    private val equipmentUpdate = SuggestedProfileUpdate(
        equipment = "dumbbells up to 50lb, pull-up bar, squat rack",
        summary = "Add a squat rack to your equipment",
    )

    private val mergedProfile = storedProfile.copy(
        equipment = "dumbbells up to 50lb, pull-up bar, squat rack",
    )

    @Test
    fun `send surfaces pendingProfileUpdate`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "Nice pickup!", suggestedProfileUpdate = equipmentUpdate)
        )

        viewModel.send("I bought a squat rack")
        advanceTimeBy(200)

        assertEquals(equipmentUpdate, viewModel.pendingProfileUpdate.value)
    }

    @Test
    fun `send ignores a profile update proposing nothing`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedProfileUpdate = SuggestedProfileUpdate(summary = "no-op"))
        )

        viewModel.send("hi")
        advanceTimeBy(200)

        assertNull(viewModel.pendingProfileUpdate.value)
    }

    @Test
    fun `profile update arrives alongside a program - both cards show`() = runTest(testDispatcher) {
        val program = SuggestedProgram(
            name = "PPL",
            days = listOf(SuggestedProgramDay(label = "Push", exercises = emptyList())),
        )
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(
                reply = "Here's a rack-friendly split.",
                suggestedProgram = program,
                suggestedProfileUpdate = equipmentUpdate,
            )
        )

        viewModel.send("I bought a squat rack, build me a program")
        advanceTimeBy(200)

        assertNotNull(viewModel.pendingProgram.value)
        assertNotNull(viewModel.pendingProfileUpdate.value)
    }

    @Test
    fun `applyProfileUpdate merges onto stored, saves, clears card`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedProfileUpdate = equipmentUpdate)
        )
        whenever(profileRepository.current()).thenReturn(storedProfile)
        whenever(profileRepository.save(any())).thenReturn(true)

        viewModel.send("squat rack")
        advanceTimeBy(200)

        val applied = mutableListOf<Boolean>()
        val job = launch { viewModel.profileUpdateApplied.collect { applied.add(it) } }

        viewModel.applyProfileUpdate()
        advanceTimeBy(200)

        // Unchanged fields keep their stored values; only equipment moves.
        org.mockito.kotlin.verify(profileRepository).save(mergedProfile)
        assertNull(viewModel.pendingProfileUpdate.value)
        assertEquals(listOf(true), applied)
        job.cancel()
    }

    @Test
    fun `applyProfileUpdate reports a queued offline save`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedProfileUpdate = equipmentUpdate)
        )
        whenever(profileRepository.current()).thenReturn(storedProfile)
        whenever(profileRepository.save(any())).thenReturn(false)

        viewModel.send("squat rack")
        advanceTimeBy(200)

        val applied = mutableListOf<Boolean>()
        val job = launch { viewModel.profileUpdateApplied.collect { applied.add(it) } }

        viewModel.applyProfileUpdate()
        advanceTimeBy(200)

        assertEquals(listOf(false), applied)
        assertNull(viewModel.pendingProfileUpdate.value)
        job.cancel()
    }

    @Test
    fun `applyProfileUpdate keeps card on failure`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedProfileUpdate = equipmentUpdate)
        )
        whenever(profileRepository.current()).thenReturn(storedProfile)
        whenever(profileRepository.save(any())).thenThrow(RuntimeException("server said no"))

        viewModel.send("squat rack")
        advanceTimeBy(200)
        viewModel.applyProfileUpdate()
        advanceTimeBy(200)

        assertIs<UiState.Error>(viewModel.sendState.value)
        assertNotNull(viewModel.pendingProfileUpdate.value) // retained for retry
        assertEquals(false, viewModel.profileUpdateInFlight.value)
    }

    @Test
    fun `dismissProfileUpdate clears without saving`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedProfileUpdate = equipmentUpdate)
        )
        viewModel.send("squat rack")
        advanceTimeBy(200)
        assertNotNull(viewModel.pendingProfileUpdate.value)

        viewModel.dismissProfileUpdate()
        advanceTimeBy(200)

        assertNull(viewModel.pendingProfileUpdate.value)
        org.mockito.kotlin.verify(profileRepository, org.mockito.kotlin.never()).save(any())
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

    // ── Suggestion cards survive process death ──────────────────────────────
    //
    // The cards used to be in-memory only, so a process kill left the assistant bubble saying
    // "tap Save Program" with no card. They now ride on the assistant message row that produced
    // them; a second ViewModel over the same DAO is the process-death simulation.

    private val ppl = SuggestedProgram(
        name = "PPL",
        days = listOf(SuggestedProgramDay(label = "Push", exercises = emptyList())),
    )

    @Test
    fun `send persists the suggestion envelope on the assistant row`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "Here's your split.", suggestedProgram = ppl))

        viewModel.send("ppl")
        advanceTimeBy(200)

        val stored = fakeChatDao.storedSuggestions()
        assertNotNull(stored)
        assertEquals(ppl, testJson.decodeFromString(PendingSuggestions.serializer(), stored).program)
    }

    @Test
    fun `send without a suggestion stores no envelope`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(ChatResponse(reply = "What equipment?"))

        viewModel.send("hi")
        advanceTimeBy(200)

        assertNull(fakeChatDao.storedSuggestions())
    }

    @Test
    fun `a fresh ViewModel restores the program card after process death`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "Here's your split.", suggestedProgram = ppl))
        viewModel.send("ppl")
        advanceTimeBy(200)

        val reborn = buildViewModel(SavedStateHandle())
        advanceTimeBy(200)

        assertEquals(ppl, reborn.pendingProgram.value)
        // Restoring is a pure read — no extra chat round-trip, no duplicated message.
        org.mockito.kotlin.verify(aiRepository, org.mockito.kotlin.times(1)).chat(any())
        assertEquals(2, reborn.messages.value.size)
    }

    @Test
    fun `a fresh ViewModel restores a routine and a profile update`() = runTest(testDispatcher) {
        val routine = SuggestedRoutine(
            name = "Upper Body Push",
            exercises = listOf(RoutineExerciseIn(exerciseId = "ex-1", targetSets = 3, targetReps = 8)),
        )
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(
                reply = "Routine + a note on your kit.",
                suggestedRoutine = routine,
                suggestedProfileUpdate = equipmentUpdate,
            )
        )
        viewModel.send("routine")
        advanceTimeBy(200)

        val reborn = buildViewModel(SavedStateHandle())
        advanceTimeBy(200)

        assertEquals(routine, reborn.pendingRoutine.value)
        assertEquals(equipmentUpdate, reborn.pendingProfileUpdate.value)
    }

    @Test
    fun `only the latest assistant turn restores a card`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "Here's your split.", suggestedProgram = ppl))
        viewModel.send("ppl")
        advanceTimeBy(200)

        // A later turn proposes nothing — the older turn's card must not come back.
        whenever(aiRepository.chat(any())).thenReturn(ChatResponse(reply = "Sounds good."))
        viewModel.send("thanks")
        advanceTimeBy(200)

        val reborn = buildViewModel(SavedStateHandle())
        advanceTimeBy(200)

        assertNull(reborn.pendingProgram.value)
    }

    @Test
    fun `adjustment restores in a chat on the same session`() = runTest(testDispatcher) {
        val vm = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedAdjustment = SuggestedAdjustment(listOf(swapAction)))
        )
        vm.send("swap it")
        advanceTimeBy(200)

        val reborn = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        advanceTimeBy(200)

        assertEquals(listOf(swapAction), reborn.pendingAdjustment.value?.actions)
    }

    @Test
    fun `adjustment does not restore in a chat without a session`() = runTest(testDispatcher) {
        val vm = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedAdjustment = SuggestedAdjustment(listOf(swapAction)))
        )
        vm.send("swap it")
        advanceTimeBy(200)

        val reborn = buildViewModel(SavedStateHandle())
        advanceTimeBy(200)

        assertNull(reborn.pendingAdjustment.value)
    }

    @Test
    fun `adjustment does not restore in a chat on a different session`() = runTest(testDispatcher) {
        val vm = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedAdjustment = SuggestedAdjustment(listOf(swapAction)))
        )
        vm.send("swap it")
        advanceTimeBy(200)

        val reborn = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-2")))
        advanceTimeBy(200)

        assertNull(reborn.pendingAdjustment.value)
    }

    @Test
    fun `saveProgram clears the persisted envelope`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "split", suggestedProgram = ppl))
        whenever(aiRepository.acceptProgram(any()))
            .thenReturn(ProgramOut(id = "prog-1", name = "PPL", isActive = true))

        viewModel.send("ppl")
        advanceTimeBy(200)
        viewModel.saveProgram()
        advanceTimeBy(200)

        assertNull(fakeChatDao.storedSuggestions())
        val reborn = buildViewModel(SavedStateHandle())
        advanceTimeBy(200)
        assertNull(reborn.pendingProgram.value)
    }

    @Test
    fun `dismissProgram clears the persisted envelope`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any()))
            .thenReturn(ChatResponse(reply = "split", suggestedProgram = ppl))

        viewModel.send("ppl")
        advanceTimeBy(200)
        viewModel.dismissProgram()
        advanceTimeBy(200)

        assertNull(fakeChatDao.storedSuggestions())
        val reborn = buildViewModel(SavedStateHandle())
        advanceTimeBy(200)
        assertNull(reborn.pendingProgram.value)
    }

    @Test
    fun `applying one card leaves the other persisted`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(
                reply = "Rack-friendly split.",
                suggestedProgram = ppl,
                suggestedProfileUpdate = equipmentUpdate,
            )
        )
        whenever(aiRepository.acceptProgram(any()))
            .thenReturn(ProgramOut(id = "prog-1", name = "PPL", isActive = true))

        viewModel.send("squat rack + program")
        advanceTimeBy(200)
        viewModel.saveProgram()
        advanceTimeBy(200)

        val reborn = buildViewModel(SavedStateHandle())
        advanceTimeBy(200)
        assertNull(reborn.pendingProgram.value)
        assertEquals(equipmentUpdate, reborn.pendingProfileUpdate.value)
    }

    @Test
    fun `applyAdjustment failure keeps the persisted envelope`() = runTest(testDispatcher) {
        val vm = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedAdjustment = SuggestedAdjustment(listOf(swapAction)))
        )
        whenever(sessionRepository.applyAdjustment(any(), any(), any()))
            .thenThrow(RuntimeException("network"))

        vm.send("swap")
        advanceTimeBy(200)
        vm.applyAdjustment(applyToRoutine = true)
        advanceTimeBy(200)

        assertNotNull(fakeChatDao.storedSuggestions())
        val reborn = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        advanceTimeBy(200)
        assertNotNull(reborn.pendingAdjustment.value)  // still retryable after a restart
    }

    @Test
    fun `dismissAdjustment clears the persisted envelope`() = runTest(testDispatcher) {
        val vm = buildViewModel(SavedStateHandle(mapOf("sessionId" to "local-1")))
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedAdjustment = SuggestedAdjustment(listOf(swapAction)))
        )
        vm.send("swap")
        advanceTimeBy(200)
        vm.dismissAdjustment()
        advanceTimeBy(200)

        assertNull(fakeChatDao.storedSuggestions())
    }

    @Test
    fun `applyProfileUpdate clears the persisted envelope`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "ok", suggestedProfileUpdate = equipmentUpdate)
        )
        whenever(profileRepository.current()).thenReturn(storedProfile)
        whenever(profileRepository.save(any())).thenReturn(true)

        viewModel.send("squat rack")
        advanceTimeBy(200)
        viewModel.applyProfileUpdate()
        advanceTimeBy(200)

        assertNull(fakeChatDao.storedSuggestions())
    }

    @Test
    fun `clearHistory clears the pending cards too`() = runTest(testDispatcher) {
        whenever(aiRepository.chat(any())).thenReturn(
            ChatResponse(reply = "split", suggestedProgram = ppl, suggestedProfileUpdate = equipmentUpdate)
        )
        viewModel.send("ppl")
        advanceTimeBy(200)
        assertNotNull(viewModel.pendingProgram.value)
        assertNotNull(viewModel.pendingProfileUpdate.value)

        viewModel.clearHistory()
        advanceTimeBy(200)

        assertNull(viewModel.pendingRoutine.value)
        assertNull(viewModel.pendingProgram.value)
        assertNull(viewModel.pendingAdjustment.value)
        assertNull(viewModel.pendingProfileUpdate.value)
        assertNull(fakeChatDao.storedSuggestions())
    }
}
