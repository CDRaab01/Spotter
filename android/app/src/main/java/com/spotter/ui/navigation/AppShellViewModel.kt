package com.spotter.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.entity.WorkoutSessionEntity
import com.spotter.util.ActiveWorkoutStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Shell-level state: the in-progress workout the resume strip points at. */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    activeWorkoutStore: ActiveWorkoutStore,
) : ViewModel() {
    val activeSession: StateFlow<WorkoutSessionEntity?> = activeWorkoutStore.activeSession
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
