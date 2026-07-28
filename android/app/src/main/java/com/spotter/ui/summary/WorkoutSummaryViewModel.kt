package com.spotter.ui.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.repository.AiRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the post-workout summary's coach debrief (the only dynamic thing on that screen —
 * every number is already carried in as a nav arg, so the summary renders instantly with or
 * without this).
 *
 * The debrief is **best-effort by contract**: LM Studio being unreachable is the normal case
 * for this deployment, so a failure never becomes an error state. The flow only ever holds
 * [UiState.Loading] (card shows a "reviewing" affordance) or [UiState.Success] (card shows the
 * prose); anything else — no session id, an unsynced session, a 409/502/504, a blank reply —
 * lands back on [UiState.Idle], which the screen renders as *no card at all*.
 */
@HiltViewModel
class WorkoutSummaryViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val sessionDao: WorkoutSessionDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Local (Room) session id threaded in by the summary route; null on older nav entries. */
    private val localSessionId: String? =
        savedStateHandle.get<String>(ARG_SESSION_ID)?.takeIf { it.isNotBlank() }

    private val _debrief = MutableStateFlow<UiState<String>>(UiState.Idle)
    val debrief: StateFlow<UiState<String>> = _debrief.asStateFlow()

    init {
        requestDebrief()
    }

    private fun requestDebrief() {
        val localId = localSessionId ?: return
        viewModelScope.launch {
            _debrief.value = UiState.Loading
            // The debrief endpoint keys on the SERVER session id; an offline-finished workout
            // has none yet, and there is nothing to debrief against — stay silent.
            val serverId = runCatching { sessionDao.getById(localId)?.serverId }.getOrNull()
            if (serverId.isNullOrBlank()) {
                _debrief.value = UiState.Idle
                return@launch
            }
            _debrief.value = try {
                val text = aiRepository.debriefSession(serverId).debrief.trim()
                if (text.isEmpty()) UiState.Idle else UiState.Success(text)
            } catch (_: Exception) {
                // Deliberately silent: no snackbar, no error card. See the class doc.
                UiState.Idle
            }
        }
    }

    companion object {
        const val ARG_SESSION_ID = "sessionId"
    }
}
