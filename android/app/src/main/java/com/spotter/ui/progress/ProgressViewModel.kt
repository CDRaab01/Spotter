package com.spotter.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.repository.MetricRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val metricRepository: MetricRepository,
) : ViewModel() {

    private val _metrics = MutableStateFlow<UiState<List<BodyMetricEntity>>>(UiState.Loading)
    val metrics: StateFlow<UiState<List<BodyMetricEntity>>> = _metrics

    init {
        observeMetrics()
        sync()
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
}
