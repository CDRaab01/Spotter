package com.spotter.ui.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.dao.ChatMessageDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.ChatMessageEntity
import com.spotter.data.model.AcceptProgramRequest
import com.spotter.data.model.ChatMessage
import com.spotter.data.model.ChatRequest
import com.spotter.data.model.PendingSuggestions
import com.spotter.data.model.RoutineCreate
import com.spotter.data.model.SuggestedAdjustment
import com.spotter.data.model.SuggestedProfileUpdate
import com.spotter.data.model.SuggestedRoutine
import com.spotter.data.model.SuggestedProgram
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.ProfileRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.util.AppPreferences
import com.spotter.util.UiState
import com.spotter.util.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val routineRepository: RoutineRepository,
    private val programRepository: ProgramRepository,
    private val sessionRepository: SessionRepository,
    private val profileRepository: ProfileRepository,
    private val chatMessageDao: ChatMessageDao,
    private val sessionDao: WorkoutSessionDao,
    private val appPreferences: AppPreferences,
    private val json: Json,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Local session id, set when chat is opened from within an active workout. */
    private val localSessionId: String? = savedStateHandle["sessionId"]

    /** True when the chat was opened from an active workout (the screen shows a back affordance). */
    val sessionAware: Boolean get() = localSessionId != null

    val messages: StateFlow<List<ChatMessage>> = chatMessageDao.getAllMessages()
        .map { entities -> entities.map { ChatMessage(it.role, it.content) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sendState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val sendState: StateFlow<UiState<Unit>> = _sendState

    private val _pendingRoutine = MutableStateFlow<SuggestedRoutine?>(null)
    val pendingRoutine: StateFlow<SuggestedRoutine?> = _pendingRoutine.asStateFlow()

    private val _pendingProgram = MutableStateFlow<SuggestedProgram?>(null)
    val pendingProgram: StateFlow<SuggestedProgram?> = _pendingProgram.asStateFlow()

    /** A live-workout adjustment the AI proposed, awaiting the user's Apply tap. */
    private val _pendingAdjustment = MutableStateFlow<SuggestedAdjustment?>(null)
    val pendingAdjustment: StateFlow<SuggestedAdjustment?> = _pendingAdjustment.asStateFlow()

    /** A change to the SAVED training profile the AI proposed, awaiting the user's Apply tap. */
    private val _pendingProfileUpdate = MutableStateFlow<SuggestedProfileUpdate?>(null)
    val pendingProfileUpdate: StateFlow<SuggestedProfileUpdate?> = _pendingProfileUpdate.asStateFlow()

    /** True while [applyProfileUpdate] is in flight, so the card can disable its Apply button. */
    private val _profileUpdateInFlight = MutableStateFlow(false)
    val profileUpdateInFlight: StateFlow<Boolean> = _profileUpdateInFlight.asStateFlow()

    /** The stored profile, so the card can show each proposed change as `current → proposed`. */
    val storedProfile: StateFlow<UserProfile> = profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserProfile())

    private val _routineSaved = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val routineSaved: SharedFlow<String> = _routineSaved

    private val _programSaved = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val programSaved: SharedFlow<String> = _programSaved

    /** Emits the number of actions applied, so the screen can confirm via snackbar. */
    private val _adjustmentApplied = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val adjustmentApplied: SharedFlow<Int> = _adjustmentApplied

    /**
     * Emits once a proposed profile update is committed: true when the server acknowledged it,
     * false when it was only queued locally (offline) — the screen words the snackbar accordingly.
     */
    private val _profileUpdateApplied = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val profileUpdateApplied: SharedFlow<Boolean> = _profileUpdateApplied

    /**
     * The `chat_messages` row currently holding the pending-suggestion envelope, and the envelope
     * as last written. A suggestion belongs to the assistant turn that produced it, so acting on
     * one card rewrites that row rather than a global blob — the other cards stay put.
     */
    private var suggestionRowId: Long? = null
    private var suggestionRowSessionId: String? = null
    private var persistedSuggestions = PendingSuggestions()

    init {
        // Restore the cards for the newest assistant turn after process death. Read-only:
        // no network call, no message insert.
        viewModelScope.launch { restoreSuggestions() }
    }

    private suspend fun restoreSuggestions() {
        val row = runCatching { chatMessageDao.latestAssistantMessage() }.getOrNull() ?: return
        val raw = row.suggestionsJson ?: return
        val envelope = runCatching {
            json.decodeFromString(PendingSuggestions.serializer(), raw)
        }.getOrNull() ?: return

        suggestionRowId = row.id
        suggestionRowSessionId = row.suggestionSessionId
        persistedSuggestions = envelope
        _pendingRoutine.value = envelope.routine
        _pendingProgram.value = envelope.program
        _pendingProfileUpdate.value = envelope.profileUpdate?.takeIf { it.hasChanges() }
        // An adjustment only makes sense against the workout it was proposed for: a chat opened
        // without a session, or on a different one, must not offer to edit it. The envelope keeps
        // it either way, so reopening the right workout's chat still finds the card.
        if (localSessionId != null && row.suggestionSessionId == localSessionId) {
            _pendingAdjustment.value = envelope.adjustment
        }
    }

    /**
     * Rewrites the tracked row's envelope after the user applied or dismissed one card, so a
     * consumed suggestion can't come back on the next launch. Clears the columns outright once
     * nothing is left.
     */
    private fun persistSuggestions(update: (PendingSuggestions) -> PendingSuggestions) {
        val rowId = suggestionRowId ?: return
        val next = update(persistedSuggestions)
        persistedSuggestions = next
        val payload = if (next.isEmpty()) null else json.encodeToString(PendingSuggestions.serializer(), next)
        val rowSessionId = if (payload == null) null else suggestionRowSessionId
        if (next.isEmpty()) {
            suggestionRowId = null
            suggestionRowSessionId = null
        }
        viewModelScope.launch {
            runCatching {
                chatMessageDao.updateSuggestions(
                    id = rowId,
                    suggestionsJson = payload,
                    suggestionSessionId = rowSessionId,
                )
            }
        }
    }

    fun send(userText: String) {
        if (userText.isBlank() || _sendState.value is UiState.Loading) return
        viewModelScope.launch {
            val historySnapshot = messages.value.toList()
            val allMessages = historySnapshot + ChatMessage("user", userText)
            chatMessageDao.insert(ChatMessageEntity(role = "user", content = userText))
            _sendState.value = UiState.Loading
            try {
                val profile = appPreferences.userProfile.first()
                // The route carries the local session id; the server needs the server-side
                // id to look up the live session, so resolve it (null when unsynced).
                val serverSessionId = localSessionId?.let {
                    runCatching { sessionDao.getById(it)?.serverId }.getOrNull()
                }
                val response = aiRepository.chat(
                    ChatRequest(
                        messages = allMessages,
                        userContext = profile.toContextString().ifBlank { null },
                        currentSessionId = serverSessionId,
                    )
                )
                // Exactly one suggestion type per reply (server guarantees this):
                // a live-workout adjustment wins, then a program, then a single routine.
                val adjustment = response.suggestedAdjustment
                val program = response.suggestedProgram
                val routine = if (adjustment == null && program == null) response.suggestedRoutine else null
                // A profile update is INDEPENDENT of that precedence chain — it changes a saved
                // setting, not the workout, so it can arrive alongside any of the above (or alone)
                // and must not clobber, or be clobbered by, the card chosen above.
                val profileUpdate = response.suggestedProfileUpdate?.takeIf { it.hasChanges() }

                // The suggestion is an attribute of this assistant turn, so it is stored on the
                // turn's row — that's what makes the cards survive process death.
                val envelope = PendingSuggestions(routine, program, adjustment, profileUpdate)
                val rowId = chatMessageDao.insert(
                    ChatMessageEntity(
                        role = "assistant",
                        content = response.reply,
                        suggestionsJson = if (envelope.isEmpty()) {
                            null
                        } else {
                            json.encodeToString(PendingSuggestions.serializer(), envelope)
                        },
                        suggestionSessionId = if (envelope.isEmpty()) null else localSessionId,
                    )
                )
                if (!envelope.isEmpty()) {
                    suggestionRowId = rowId
                    suggestionRowSessionId = localSessionId
                    persistedSuggestions = envelope
                }

                adjustment?.let { _pendingAdjustment.value = it }
                program?.let { _pendingProgram.value = it }
                routine?.let { _pendingRoutine.value = it }
                profileUpdate?.let { _pendingProfileUpdate.value = it }
                _sendState.value = UiState.Success(Unit)
            } catch (e: Exception) {
                _sendState.value = UiState.Error(e.message ?: "Failed to reach Spotter. Try again.")
            }
        }
    }

    fun saveRoutine() {
        val routine = _pendingRoutine.value ?: return
        viewModelScope.launch {
            try {
                val result = routineRepository.createRoutine(
                    RoutineCreate(
                        name = routine.name,
                        source = "ai",
                        exercises = routine.exercises,
                    )
                )
                _pendingRoutine.value = null
                persistSuggestions { it.copy(routine = null) }
                _routineSaved.emit(result.name)
            } catch (e: Exception) {
                _sendState.value = UiState.Error(e.message ?: "Failed to save routine.")
            }
        }
    }

    fun dismissRoutine() {
        _pendingRoutine.value = null
        persistSuggestions { it.copy(routine = null) }
    }

    fun saveProgram() {
        val program = _pendingProgram.value ?: return
        viewModelScope.launch {
            try {
                val result = aiRepository.acceptProgram(
                    AcceptProgramRequest(name = program.name, days = program.days)
                )
                // Pull the new program + its routines into the local cache so Home/Calendar
                // immediately reflect the now-active program.
                runCatching { programRepository.sync() }
                runCatching { routineRepository.sync() }
                _pendingProgram.value = null
                persistSuggestions { it.copy(program = null) }
                _programSaved.emit(result.name)
            } catch (e: Exception) {
                _sendState.value = UiState.Error(e.message ?: "Failed to save program.")
            }
        }
    }

    fun dismissProgram() {
        _pendingProgram.value = null
        persistSuggestions { it.copy(program = null) }
    }

    /**
     * Apply the pending adjustment to the live workout (and, when [applyToRoutine],
     * the underlying routine so future workouts pick it up). On failure the card is
     * kept so the user can retry.
     */
    fun applyAdjustment(applyToRoutine: Boolean) {
        val adjustment = _pendingAdjustment.value ?: return
        val sessionId = localSessionId ?: return
        viewModelScope.launch {
            try {
                sessionRepository.applyAdjustment(sessionId, adjustment.actions, applyToRoutine)
                // Refresh the routine cache so the program breakdown / future projections
                // reflect the edit immediately (mirrors saveProgram).
                if (applyToRoutine) runCatching { routineRepository.sync() }
                _pendingAdjustment.value = null
                persistSuggestions { it.copy(adjustment = null) }
                _adjustmentApplied.emit(adjustment.actions.size)
            } catch (e: Exception) {
                _sendState.value = UiState.Error(e.message ?: "Couldn't apply the change. Try again.")
            }
        }
    }

    fun dismissAdjustment() {
        _pendingAdjustment.value = null
        persistSuggestions { it.copy(adjustment = null) }
    }

    /**
     * Commit the AI's proposed profile change: merge the proposed (non-null) fields onto the
     * stored profile and save it. On failure the card is kept so the user can retry (mirrors
     * [applyAdjustment]); an offline save is still a success — it is queued and drained later.
     */
    fun applyProfileUpdate() {
        val update = _pendingProfileUpdate.value ?: return
        if (_profileUpdateInFlight.value) return
        viewModelScope.launch {
            _profileUpdateInFlight.value = true
            try {
                val acknowledged = profileRepository.save(update.mergeOnto(profileRepository.current()))
                _pendingProfileUpdate.value = null
                persistSuggestions { it.copy(profileUpdate = null) }
                _profileUpdateApplied.emit(acknowledged)
            } catch (e: Exception) {
                _sendState.value =
                    UiState.Error(e.message ?: "Couldn't update your profile. Try again.")
            } finally {
                _profileUpdateInFlight.value = false
            }
        }
    }

    fun dismissProfileUpdate() {
        _pendingProfileUpdate.value = null
        persistSuggestions { it.copy(profileUpdate = null) }
    }

    fun clearHistory() {
        // The cards belong to the conversation being deleted — dropping the rows without them
        // would leave a stale card floating above an empty transcript.
        _pendingRoutine.value = null
        _pendingProgram.value = null
        _pendingAdjustment.value = null
        _pendingProfileUpdate.value = null
        suggestionRowId = null
        suggestionRowSessionId = null
        persistedSuggestions = PendingSuggestions()
        viewModelScope.launch {
            chatMessageDao.clearAll()
        }
    }

    fun clearError() {
        if (_sendState.value is UiState.Error) _sendState.value = UiState.Idle
    }
}

/**
 * Applies a proposed profile update onto [stored]: a non-null field is the complete new value,
 * a null field leaves the stored value untouched. The whole merged profile is what gets saved —
 * [com.spotter.data.repository.ProfileRepository.save] sends every field, so an unchanged field
 * must carry its stored value through or it would be cleared.
 */
internal fun SuggestedProfileUpdate.mergeOnto(stored: UserProfile) = UserProfile(
    experience = experience ?: stored.experience,
    goal = goal ?: stored.goal,
    equipment = equipment ?: stored.equipment,
    ageGroup = ageGroup ?: stored.ageGroup,
    limitations = limitations ?: stored.limitations,
)
