package com.spotter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.ChatMessage
import com.spotter.data.model.ChatRequest
import com.spotter.data.model.PlanCreate
import com.spotter.data.model.PlanUpdate
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.ProgramDayOut
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.MetricRepository
import com.spotter.data.repository.PlanRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.util.AppPreferences
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val sessionRepository: SessionRepository,
    private val metricRepository: MetricRepository,
    private val aiRepository: AiRepository,
    private val programRepository: ProgramRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _plans = MutableStateFlow<UiState<List<WorkoutPlanEntity>>>(UiState.Loading)
    val plans: StateFlow<UiState<List<WorkoutPlanEntity>>> = _plans

    private val _startState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val startState: StateFlow<UiState<Unit>> = _startState

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError

    private val _navigateToWorkout = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToWorkout: SharedFlow<String> = _navigateToWorkout.asSharedFlow()

    private val _generatingPlan = MutableStateFlow(false)
    val generatingPlan: StateFlow<Boolean> = _generatingPlan.asStateFlow()

    private val _streak = MutableStateFlow(0)
    val streak: StateFlow<Int> = _streak.asStateFlow()

    private val _weeklyWorkouts = MutableStateFlow(0)
    val weeklyWorkouts: StateFlow<Int> = _weeklyWorkouts.asStateFlow()

    private val _nextProgramDay = MutableStateFlow<ProgramDayOut?>(null)
    val nextProgramDay: StateFlow<ProgramDayOut?> = _nextProgramDay.asStateFlow()

    private var autoGenerateTriggered = false

    init {
        observePlans()
        sync()
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                val sessions = sessionRepository.listSessions()
                val completedDates = sessions
                    .filter { it.status == "completed" }
                    .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    .toSet()

                var streak = 0
                var day = LocalDate.now()
                while (completedDates.contains(day)) { streak++; day = day.minusDays(1) }
                _streak.value = streak

                val weekStart = LocalDate.now().minusDays(6)
                _weeklyWorkouts.value = completedDates.count { !it.isBefore(weekStart) }
            } catch (_: Exception) {}
        }
    }

    private fun observePlans() {
        viewModelScope.launch {
            planRepository.plans
                .onStart { _plans.value = UiState.Loading }
                .catch { _plans.value = UiState.Error(it.message ?: "Unknown error") }
                .collect { localPlans ->
                    _plans.value = UiState.Success(localPlans)
                    if (!autoGenerateTriggered && localPlans.isEmpty()) {
                        val onboardingDone = appPreferences.onboardingDone.first()
                        if (onboardingDone) {
                            autoGenerateTriggered = true
                            generateInitialPlan()
                        }
                    }
                }
        }
    }

    fun sync() {
        viewModelScope.launch {
            try { planRepository.sync() } catch (_: Exception) {}
            try { sessionRepository.syncPending() } catch (_: Exception) {}
            try { programRepository.sync() } catch (_: Exception) {}
            _nextProgramDay.value = programRepository.getNextProgramDay()
        }
    }

    fun generateInitialPlan() {
        viewModelScope.launch {
            _generatingPlan.value = true
            try {
                val profile = appPreferences.userProfile.first()
                val response = aiRepository.chat(
                    ChatRequest(
                        messages = listOf(
                            ChatMessage(
                                role = "user",
                                content = "Based on my profile, generate a starter workout plan for me.",
                            )
                        ),
                        userContext = profile.toContextString().ifBlank { null },
                    )
                )
                response.suggestedPlan?.let { plan ->
                    planRepository.createPlan(
                        PlanCreate(name = plan.name, source = "ai", exercises = plan.exercises)
                    )
                }
            } catch (_: Exception) {
                // silent — user can still create a plan manually
            } finally {
                _generatingPlan.value = false
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
            } catch (e: Exception) {
                _startState.value = UiState.Error(e.message ?: "Could not start workout")
                return@launch
            }
            _startState.value = UiState.Idle
        }
    }

    fun deletePlan(planId: String) {
        viewModelScope.launch {
            try {
                planRepository.deletePlan(planId)
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Could not delete plan"
            }
        }
    }

    fun renamePlan(planId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            try {
                planRepository.renamePlan(planId, PlanUpdate(name = newName.trim()))
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Could not rename plan"
            }
        }
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

    fun clearActionError() {
        _actionError.value = null
    }
}
