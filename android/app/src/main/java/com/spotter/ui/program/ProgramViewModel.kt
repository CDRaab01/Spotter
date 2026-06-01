package com.spotter.ui.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.model.ProgramCreate
import com.spotter.data.model.ProgramDayIn
import com.spotter.data.model.ProgramDaysUpdate
import com.spotter.data.model.ProgramOut
import com.spotter.data.model.ProgramUpdate
import com.spotter.data.repository.ProgramRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProgramViewModel @Inject constructor(
    private val repository: ProgramRepository,
) : ViewModel() {

    private val _programs = MutableStateFlow<UiState<List<WorkoutProgramEntity>>>(UiState.Loading)
    val programs: StateFlow<UiState<List<WorkoutProgramEntity>>> = _programs

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError

    init {
        observePrograms()
        sync()
    }

    private fun observePrograms() {
        viewModelScope.launch {
            repository.programs
                .onStart { _programs.value = UiState.Loading }
                .catch { _programs.value = UiState.Error(it.message ?: "Error") }
                .collect { _programs.value = UiState.Success(it) }
        }
    }

    fun sync() {
        viewModelScope.launch {
            try { repository.sync() } catch (_: Exception) {}
        }
    }

    fun createProgram(name: String, days: List<ProgramDayIn>) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                repository.createProgram(ProgramCreate(name = name.trim(), days = days))
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Could not create program"
            }
        }
    }

    fun activateProgram(id: String) {
        viewModelScope.launch {
            try {
                repository.updateProgram(id, ProgramUpdate(isActive = true))
                sync()
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Could not activate program"
            }
        }
    }

    fun deleteProgram(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteProgram(id)
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Could not delete program"
            }
        }
    }

    fun clearError() { _actionError.value = null }
}
