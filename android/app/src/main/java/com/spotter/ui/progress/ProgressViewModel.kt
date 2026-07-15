package com.spotter.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.ExerciseProgressPoint
import com.spotter.data.model.PersonalRecord
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** A weigh-in draft from the log dialog: weight is required; everything else is optional. */
data class BodyLogDraft(
    val weight: Double,
    val bodyfat: Double? = null,
    val neck: Double? = null,
    val chest: Double? = null,
    val waist: Double? = null,
    val hips: Double? = null,
    val arm: Double? = null,
    val thigh: Double? = null,
)

enum class ChartRange(val label: String) {
    ONE_MONTH("1M"),
    SIX_MONTHS("6M"),
    ONE_YEAR("1Y"),
    ALL_TIME("All");

    fun cutoff(): LocalDate? = when (this) {
        ONE_MONTH -> LocalDate.now().minusMonths(1)
        SIX_MONTHS -> LocalDate.now().minusMonths(6)
        ONE_YEAR -> LocalDate.now().minusYears(1)
        ALL_TIME -> null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val metricRepository: MetricRepository,
    private val api: ApiService,
) : ViewModel() {

    private val _allMetrics = MutableStateFlow<UiState<List<BodyMetricEntity>>>(UiState.Loading)

    private val _trackedExercises = MutableStateFlow<UiState<List<TrackedExercise>>>(UiState.Loading)
    val trackedExercises: StateFlow<UiState<List<TrackedExercise>>> = _trackedExercises

    private val _personalRecords = MutableStateFlow<UiState<List<PersonalRecord>>>(UiState.Loading)
    val personalRecords: StateFlow<UiState<List<PersonalRecord>>> = _personalRecords

    val selectedExerciseId = MutableStateFlow<String?>(null)

    private val _chartRange = MutableStateFlow(ChartRange.SIX_MONTHS)
    val chartRange: StateFlow<ChartRange> = _chartRange

    val metrics: StateFlow<UiState<List<BodyMetricEntity>>> =
        combine(_allMetrics, _chartRange) { metricsState, range ->
            filterByRange(metricsState, range) { LocalDate.parse(it.date) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    private val _rawExerciseProgress: StateFlow<UiState<List<ExerciseProgressPoint>>> =
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

    val exerciseProgress: StateFlow<UiState<List<ExerciseProgressPoint>>> =
        combine(_rawExerciseProgress, _chartRange) { progressState, range ->
            filterByRange(progressState, range) { LocalDate.parse(it.date) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Idle)

    init {
        observeMetrics()
        sync()
        loadTrackedExercises()
        loadPersonalRecords()
    }

    private fun observeMetrics() {
        viewModelScope.launch {
            metricRepository.metrics
                .onStart { _allMetrics.value = UiState.Loading }
                .catch { _allMetrics.value = UiState.Error(it.message ?: "Unknown error") }
                .collect { _allMetrics.value = UiState.Success(it) }
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

    private fun loadPersonalRecords() {
        viewModelScope.launch {
            try {
                _personalRecords.value = UiState.Success(api.getPersonalRecords())
            } catch (e: Exception) {
                _personalRecords.value = UiState.Error(e.message ?: "Failed to load records")
            }
        }
    }

    fun selectExercise(exerciseId: String?) {
        selectedExerciseId.value = exerciseId
    }

    fun setChartRange(range: ChartRange) {
        _chartRange.value = range
    }

    fun logBodyweight(draft: BodyLogDraft) {
        viewModelScope.launch {
            try {
                metricRepository.addMetric(
                    BodyMetricCreate(
                        date = LocalDate.now().toString(),
                        weight = draft.weight,
                        bodyfat = draft.bodyfat,
                        neck = draft.neck,
                        chest = draft.chest,
                        waist = draft.waist,
                        hips = draft.hips,
                        arm = draft.arm,
                        thigh = draft.thigh,
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun <T> filterByRange(
        state: UiState<List<T>>,
        range: ChartRange,
        dateOf: (T) -> LocalDate,
    ): UiState<List<T>> {
        val cutoff = range.cutoff() ?: return state
        return when (state) {
            is UiState.Success -> UiState.Success(
                state.data.filter { !dateOf(it).isBefore(cutoff) }
            )
            else -> state
        }
    }
}
