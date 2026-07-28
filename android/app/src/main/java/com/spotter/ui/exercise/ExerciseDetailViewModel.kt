package com.spotter.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.ExerciseProgressPoint
import com.spotter.data.model.PersonalRecord
import com.spotter.data.remote.ApiService
import com.spotter.data.repository.ExerciseRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * This exercise's logged history: the day-by-day progress points and its personal record, both
 * server-computed. There is no offline mirror for either — when the server can't be reached the
 * panel says so rather than inventing numbers from partial local data.
 */
data class ExerciseHistory(
    val points: List<ExerciseProgressPoint>,
    val record: PersonalRecord?,
)

/**
 * One exercise's detail (name, muscle groups, equipment, instructions), mirror-backed via
 * [ExerciseRepository.getExercise] so it renders offline for any exercise the mirror knows, plus
 * the server-computed [ExerciseHistory] (chart, PRs, recent sets) loaded independently — a history
 * failure never takes the instructions down with it.
 */
@HiltViewModel
class ExerciseDetailViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    private val api: ApiService,
) : ViewModel() {

    private val _exercise = MutableStateFlow<UiState<ExerciseOut>>(UiState.Loading)
    val exercise: StateFlow<UiState<ExerciseOut>> = _exercise.asStateFlow()

    private val _history = MutableStateFlow<UiState<ExerciseHistory>>(UiState.Loading)
    val history: StateFlow<UiState<ExerciseHistory>> = _history.asStateFlow()

    fun load(exerciseId: String) {
        loadExercise(exerciseId)
        loadHistory(exerciseId)
    }

    private fun loadExercise(exerciseId: String) {
        viewModelScope.launch {
            if (_exercise.value !is UiState.Success) _exercise.value = UiState.Loading
            _exercise.value = try {
                UiState.Success(exerciseRepository.getExercise(exerciseId))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load the exercise")
            }
        }
    }

    fun loadHistory(exerciseId: String) {
        viewModelScope.launch {
            if (_history.value !is UiState.Success) _history.value = UiState.Loading
            _history.value = try {
                val points = api.getExerciseProgress(exerciseId)
                // The records endpoint is per-exercise-filtered client-side; a failure there alone
                // shouldn't lose the chart, so it degrades to "no record" rather than an error.
                val record = runCatching { api.getPersonalRecords() }
                    .getOrNull()
                    ?.firstOrNull { it.exerciseId == exerciseId }
                UiState.Success(ExerciseHistory(points = points, record = record))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load this exercise's history")
            }
        }
    }
}
