package com.spotter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.data.model.SessionCreate
import com.spotter.data.repository.PlanRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _plans = MutableStateFlow<UiState<List<WorkoutPlanEntity>>>(UiState.Loading)
    val plans: StateFlow<UiState<List<WorkoutPlanEntity>>> = _plans

    private val _startState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val startState: StateFlow<UiState<Unit>> = _startState

    private val _navigateToWorkout = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToWorkout: SharedFlow<String> = _navigateToWorkout.asSharedFlow()

    init {
        observePlans()
        sync()
    }

    private fun observePlans() {
        viewModelScope.launch {
            planRepository.plans
                .onStart { _plans.value = UiState.Loading }
                .catch { _plans.value = UiState.Error(it.message ?: "Unknown error") }
                .collect { _plans.value = UiState.Success(it) }
        }
    }

    fun sync() {
        viewModelScope.launch {
            try {
                planRepository.sync()
            } catch (_: Exception) {
                // offline — local data still shown
            }
        }
    }

    fun startSession(planId: String) {
        if (_startState.value is UiState.Loading) return
        viewModelScope.launch {
            _startState.value = UiState.Loading
            try {
                val session = sessionRepository.createSession(
                    SessionCreate(planId = planId, date = LocalDate.now().toString()),
                )
                _navigateToWorkout.emit(session.id)
                _startState.value = UiState.Idle
            } catch (e: Exception) {
                _startState.value = UiState.Error(e.message ?: "Could not start workout")
            }
        }
    }
}
