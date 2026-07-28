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
                chatMessageDao.insert(ChatMessageEntity(role = "assistant", content = response.reply))
                // Exactly one suggestion type per reply (server guarantees this):
                // a live-workout adjustment wins, then a program, then a single routine.
                val adjustment = response.suggestedAdjustment
                val program = response.suggestedProgram
                when {
                    adjustment != null -> _pendingAdjustment.value = adjustment
                    program != null -> _pendingProgram.value = program
                    else -> response.suggestedRoutine?.let { _pendingRoutine.value = it }
                }
                // A profile update is INDEPENDENT of that precedence chain — it changes a saved
                // setting, not the workout, so it can arrive alongside any of the above (or alone)
                // and must not clobber, or be clobbered by, the card chosen above.
                response.suggestedProfileUpdate
                    ?.takeIf { it.hasChanges() }
                    ?.let { _pendingProfileUpdate.value = it }
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
                _routineSaved.emit(result.name)
            } catch (e: Exception) {
                _sendState.value = UiState.Error(e.message ?: "Failed to save routine.")
            }
        }
    }

    fun dismissRoutine() {
        _pendingRoutine.value = null
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
                _programSaved.emit(result.name)
            } catch (e: Exception) {
                _sendState.value = UiState.Error(e.message ?: "Failed to save program.")
            }
        }
    }

    fun dismissProgram() {
        _pendingProgram.value = null
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
                _adjustmentApplied.emit(adjustment.actions.size)
            } catch (e: Exception) {
                _sendState.value = UiState.Error(e.message ?: "Couldn't apply the change. Try again.")
            }
        }
    }

    fun dismissAdjustment() {
        _pendingAdjustment.value = null
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
    }

    fun clearHistory() {
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
