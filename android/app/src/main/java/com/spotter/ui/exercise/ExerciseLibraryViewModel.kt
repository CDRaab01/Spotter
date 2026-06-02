package com.spotter.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.ExerciseOut
import com.spotter.data.repository.ExerciseRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ExerciseLibraryViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
) : ViewModel() {

    val query = MutableStateFlow("")

    val exercises: StateFlow<UiState<List<ExerciseOut>>> =
        query
            .debounce(250)
            .flatMapLatest { q ->
                flow {
                    emit(UiState.Loading)
                    try {
                        emit(UiState.Success(exerciseRepository.search(q.trim())))
                    } catch (e: Exception) {
                        emit(UiState.Error(e.message ?: "Failed to load exercises"))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    fun onQueryChange(value: String) {
        query.value = value
    }
}
