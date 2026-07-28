package com.spotter.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.ExerciseOut
import com.spotter.data.repository.ExerciseRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One exercise's detail (name, muscle groups, equipment, instructions), mirror-backed via
 * [ExerciseRepository.getExercise] so it renders offline for any exercise the mirror knows.
 */
@HiltViewModel
class ExerciseDetailViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
) : ViewModel() {

    private val _exercise = MutableStateFlow<UiState<ExerciseOut>>(UiState.Loading)
    val exercise: StateFlow<UiState<ExerciseOut>> = _exercise.asStateFlow()

    fun load(exerciseId: String) {
        viewModelScope.launch {
            if (_exercise.value !is UiState.Success) _exercise.value = UiState.Loading
            _exercise.value = try {
                UiState.Success(exerciseRepository.getExercise(exerciseId))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load the exercise")
            }
        }
    }
}
