package com.spotter.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.ExerciseProgressPoint
import com.spotter.data.model.TrackedExercise
import com.spotter.data.remote.ApiService
import com.spotter.data.repository.MetricRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val metricRepository: MetricRepository,
    private val api: ApiService,
) : ViewModel() {

    private val _metrics = MutableStateFlow<UiState<List<BodyMetricEntity>>>(UiState.Loading)
    val metrics: StateFlow<UiState<List<BodyMetricEntity>>> = _metrics

    private val _trackedExercises = MutableStateFlow<UiState<List<TrackedExercise>>>(UiState.Loading)
    val trackedExercises: StateFlow<UiState<List<TrackedExercise>>> = _trackedExercises

    val selectedExerciseId = MutableStateFlow<String?>(null)

    val exerciseProgress: StateFlow<UiState<List<ExerciseProgressPoint>>> =
        selectedExerciseId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf<UiState<List<ExerciseProgressPoint>>>(UiState.Idle)
                } else {
                    flow<UiState<List<ExerciseProgressPoint>>> {
                        emit(UiState.Loading)
                        try {
                            emit(UiState.Success(api.getExerciseProgress(id)))
                        } catch (e: Exception) {
                            emit(UiState.Error(e.message ?: "Failed to load progress"))
                        }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, UiState.Idle)

    init {
        observeMetrics()
        sync()
        loadTrackedExercises()
    }

    private fun observeMetrics() {
        viewModelScope.launch {
            metricRepository.metrics
                .onStart { _metrics.value = UiState.Loading }
                .catch { _metrics.value = UiState.Error(it.message ?: "Unknown error") }
                .collect { _metrics.value = UiState.Success(it) }
        }
    }

    fun sync() {
        viewModelScope.launch {
            try {
                metricRepository.sync()
            } catch (_: Exception) {}
        }
    }

    private fun loadTrackedExercises() {
        viewModelScope.launch {
            try {
                val exercises = api.getTrackedExercises()
                _trackedExercises.value = UiState.Success(exercises)
            } catch (e: Exception) {
                _trackedExercises.value = UiState.Error(e.message ?: "Failed to load exercises")
            }
        }
    }

    fun selectExercise(exerciseId: String?) {
        selectedExerciseId.value = exerciseId
    }

    fun logBodyweight(weight: Double) {
        viewModelScope.launch {
            try {
                metricRepository.addMetric(
                    BodyMetricCreate(date = LocalDate.now().toString(), weight = weight)
                )
            } catch (_: Exception) {}
        }
    }
}
