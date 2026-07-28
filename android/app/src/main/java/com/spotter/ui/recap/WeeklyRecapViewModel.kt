package com.spotter.ui.recap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.WeeklyRecapOut
import com.spotter.data.repository.AiRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Your week": the server-computed weekly numbers plus an optional LLM narrative.
 *
 * The endpoint always answers 200 with the stats, so an [UiState.Error] here means the *server*
 * couldn't be reached — a narrative-less recap is a normal success, not a degraded error.
 */
@HiltViewModel
class WeeklyRecapViewModel @Inject constructor(
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _recap = MutableStateFlow<UiState<WeeklyRecapOut>>(UiState.Loading)
    val recap: StateFlow<UiState<WeeklyRecapOut>> = _recap.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _recap.value = UiState.Loading
            _recap.value = try {
                UiState.Success(aiRepository.weeklyRecap())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load your week")
            }
        }
    }
}
