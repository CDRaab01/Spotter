package com.spotter.ui.cardio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.CardioActivityType
import com.spotter.data.repository.CardioRepository
import com.spotter.util.AppPreferences
import com.spotter.util.DistanceUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** UI state for the manual "Log cardio" form's save action. */
sealed interface ManualCardioSaveState {
    data object Idle : ManualCardioSaveState
    data object Saving : ManualCardioSaveState
    data object Saved : ManualCardioSaveState
    data class Error(val message: String) : ManualCardioSaveState
}

@HiltViewModel
class ManualCardioViewModel @Inject constructor(
    private val cardioRepository: CardioRepository,
    appPreferences: AppPreferences,
) : ViewModel() {

    /** The user's distance-unit preference, so the form can label the field and convert at save. */
    val distanceUnit: StateFlow<DistanceUnit> = appPreferences.distanceUnit
        .stateIn(viewModelScope, SharingStarted.Eagerly, DistanceUnit.MI)

    private val _saveState = MutableStateFlow<ManualCardioSaveState>(ManualCardioSaveState.Idle)
    val saveState: StateFlow<ManualCardioSaveState> = _saveState.asStateFlow()

    /**
     * Persist a manual walk/run.
     *
     * @param isRun true for a run, false for a walk.
     * @param durationMinutes total minutes (must be > 0).
     * @param distanceMeters optional canonical distance in meters (already unit-converted).
     * @param date the calendar date the activity happened.
     */
    fun save(
        isRun: Boolean,
        durationMinutes: Int,
        distanceMeters: Int?,
        date: LocalDate,
    ) {
        if (_saveState.value is ManualCardioSaveState.Saving) return
        if (durationMinutes <= 0) {
            _saveState.value = ManualCardioSaveState.Error("Enter a duration in minutes.")
            return
        }
        _saveState.value = ManualCardioSaveState.Saving
        viewModelScope.launch {
            try {
                cardioRepository.logManualSession(
                    activityType = if (isRun) CardioActivityType.RUN else CardioActivityType.WALK,
                    durationSec = durationMinutes * 60,
                    distanceMeters = distanceMeters,
                    date = date.toString(),
                )
                _saveState.value = ManualCardioSaveState.Saved
            } catch (e: Exception) {
                _saveState.value = ManualCardioSaveState.Error(e.message ?: "Could not save entry.")
            }
        }
    }

    fun clearError() {
        if (_saveState.value is ManualCardioSaveState.Error) {
            _saveState.value = ManualCardioSaveState.Idle
        }
    }
}
