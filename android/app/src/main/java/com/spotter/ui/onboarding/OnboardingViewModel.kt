package com.spotter.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.repository.ProfileRepository
import com.spotter.util.AppPreferences
import com.spotter.util.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    val totalSteps = 5

    private val _currentStep = MutableStateFlow(1)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _draft = MutableStateFlow(UserProfile())
    val draft: StateFlow<UserProfile> = _draft.asStateFlow()

    private val _navigateToHome = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToHome: SharedFlow<Unit> = _navigateToHome

    fun nextStep() {
        if (_currentStep.value < totalSteps) {
            _currentStep.value++
        } else {
            finish()
        }
    }

    fun prevStep() {
        if (_currentStep.value > 1) _currentStep.value--
    }

    fun setExperience(value: String) = _draft.update { it.copy(experience = value) }
    fun setGoal(value: String) = _draft.update { it.copy(goal = value) }
    fun setEquipment(value: String) = _draft.update { it.copy(equipment = value) }
    fun setAgeGroup(value: String) = _draft.update { it.copy(ageGroup = value) }
    fun setLimitations(value: String) = _draft.update { it.copy(limitations = value) }

    /**
     * Persists the questionnaire locally (which also marks onboarding done) and pushes it to the
     * server so the coach keeps it beyond this install.
     *
     * The push is **best-effort and never blocks finishing onboarding**: if it fails the answers
     * are still saved locally, and [ProfileRepository] has queued them for the next sync round.
     */
    private fun finish() {
        viewModelScope.launch {
            appPreferences.saveProfile(_draft.value)
            runCatching { profileRepository.save(_draft.value) }
            _navigateToHome.emit(Unit)
        }
    }
}
