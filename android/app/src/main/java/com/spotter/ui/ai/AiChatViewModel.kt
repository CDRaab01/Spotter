package com.spotter.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.ChatMessage
import com.spotter.data.model.ChatRequest
import com.spotter.data.model.PlanCreate
import com.spotter.data.model.SuggestedPlan
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.PlanRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val planRepository: PlanRepository,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _sendState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val sendState: StateFlow<UiState<Unit>> = _sendState

    private val _pendingPlan = MutableStateFlow<SuggestedPlan?>(null)
    val pendingPlan: StateFlow<SuggestedPlan?> = _pendingPlan.asStateFlow()

    private val _planSaved = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val planSaved: SharedFlow<String> = _planSaved

    fun send(userText: String) {
        if (userText.isBlank() || _sendState.value is UiState.Loading) return
        val newMessages = _messages.value + ChatMessage("user", userText)
        _messages.value = newMessages
        viewModelScope.launch {
            _sendState.value = UiState.Loading
            try {
                val response = aiRepository.chat(ChatRequest(newMessages))
                _messages.value = newMessages + ChatMessage("assistant", response.reply)
                response.suggestedPlan?.let { _pendingPlan.value = it }
                _sendState.value = UiState.Success(Unit)
            } catch (e: Exception) {
                _sendState.value = UiState.Error(e.message ?: "Failed to reach Spotter. Try again.")
            }
        }
    }

    fun savePlan() {
        val plan = _pendingPlan.value ?: return
        viewModelScope.launch {
            try {
                val result = planRepository.createPlan(
                    PlanCreate(
                        name = plan.name,
                        source = "ai",
                        exercises = plan.exercises,
                    )
                )
                _pendingPlan.value = null
                _planSaved.emit(result.name)
            } catch (e: Exception) {
                _sendState.value = UiState.Error(e.message ?: "Failed to save plan.")
            }
        }
    }

    fun dismissPlan() {
        _pendingPlan.value = null
    }

    fun startIntake() {
        send("Hi, I'm ready to get started.")
    }

    fun clearError() {
        if (_sendState.value is UiState.Error) _sendState.value = UiState.Idle
    }
}
