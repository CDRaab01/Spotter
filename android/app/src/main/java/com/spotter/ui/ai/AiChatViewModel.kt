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
import com.spotter.data.model.SuggestedRoutine
import com.spotter.data.model.SuggestedProgram
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.util.AppPreferences
import com.spotter.util.UiState
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
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject

@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val routineRepository: RoutineRepository,
    private val programRepository: ProgramRepository,
    private val chatMessageDao: ChatMessageDao,
    private val sessionDao: WorkoutSessionDao,
    private val appPreferences: AppPreferences,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Local session id, set when chat is opened from within an active workout. */
    private val localSessionId: String? = savedStateHandle["sessionId"]

    val messages: StateFlow<List<ChatMessage>> = chatMessageDao.getAllMessages()
        .map { entities -> entities.map { ChatMessage(it.role, it.content) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sendState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val sendState: StateFlow<UiState<Unit>> = _sendState

    private val _pendingRoutine = MutableStateFlow<SuggestedRoutine?>(null)
    val pendingRoutine: StateFlow<SuggestedRoutine?> = _pendingRoutine.asStateFlow()

    private val _pendingProgram = MutableStateFlow<SuggestedProgram?>(null)
    val pendingProgram: StateFlow<SuggestedProgram?> = _pendingProgram.asStateFlow()

    private val _routineSaved = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val routineSaved: SharedFlow<String> = _routineSaved

    private val _programSaved = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val programSaved: SharedFlow<String> = _programSaved

    fun send(userText: String) {
        if (userText.isBlank() || _sendState.value is UiState.Loading) return
        if (userText.trim().length > MAX_MESSAGE_CHARS) {
            _sendState.value = UiState.Error(
                "That message is a bit long — keep it under $MAX_MESSAGE_CHARS characters."
            )
            return
        }
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
                // Don't render an empty bubble if the model returned nothing usable.
                if (response.reply.isNotBlank()) {
                    chatMessageDao.insert(
                        ChatMessageEntity(role = "assistant", content = response.reply)
                    )
                }
                // In-workout chat is advice-only — never surface a Save card mid-session.
                if (serverSessionId == null) {
                    // Prefer a program when present; never surface both.
                    val program = response.suggestedProgram
                    if (program != null) {
                        _pendingProgram.value = program
                    } else {
                        response.suggestedRoutine?.let { _pendingRoutine.value = it }
                    }
                }
                val gotNothing = response.reply.isBlank() &&
                    response.suggestedProgram == null && response.suggestedRoutine == null
                _sendState.value = if (gotNothing) {
                    UiState.Error("The coach didn't have a response — please try again.")
                } else {
                    UiState.Success(Unit)
                }
            } catch (e: Exception) {
                _sendState.value = UiState.Error(friendlyError(e))
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

    fun clearHistory() {
        viewModelScope.launch {
            chatMessageDao.clearAll()
        }
    }

    fun clearError() {
        if (_sendState.value is UiState.Error) _sendState.value = UiState.Idle
    }

    /** Turn raw network/HTTP exceptions into a message a user can act on. */
    private fun friendlyError(e: Throwable): String = when {
        e is HttpException -> when (e.code()) {
            502 -> "The AI service is briefly unavailable. Please try again in a moment."
            503 -> "The AI service isn't reachable right now. Please try again shortly."
            504 -> "The model is still loading — give it a moment and try again."
            422 -> "That message couldn't be processed. Try rephrasing or shortening it."
            in 500..599 -> "The server hit an error. Please try again."
            else -> e.message ?: "Something went wrong. Please try again."
        }
        e is SocketTimeoutException ->
            "That took too long — the model may be busy. Please try again."
        e is IOException ->
            "Can't reach Spotter — check your connection and try again."
        else -> e.message ?: "Failed to reach Spotter. Try again."
    }

    companion object {
        /** Mirrors the server's 2000-char limit on a single chat message. */
        private const val MAX_MESSAGE_CHARS = 2000
    }
}
