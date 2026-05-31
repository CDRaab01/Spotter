package com.spotter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.data.repository.PlanRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val planRepository: PlanRepository,
) : ViewModel() {

    private val _plans = MutableStateFlow<UiState<List<WorkoutPlanEntity>>>(UiState.Loading)
    val plans: StateFlow<UiState<List<WorkoutPlanEntity>>> = _plans

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
}
