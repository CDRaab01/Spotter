package com.spotter.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.CalendarEntry
import com.spotter.data.repository.CalendarRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
) : ViewModel() {

    private val _entries = MutableStateFlow<UiState<List<CalendarEntry>>>(UiState.Idle)
    val entries: StateFlow<UiState<List<CalendarEntry>>> = _entries

    init {
        val now = YearMonth.now()
        loadMonth(now)
    }

    fun loadMonth(month: YearMonth) {
        viewModelScope.launch {
            _entries.value = UiState.Loading
            _entries.value = try {
                val from = month.atDay(1).toString()
                val to = month.atEndOfMonth().toString()
                UiState.Success(calendarRepository.getCalendar(from, to))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to load calendar")
            }
        }
    }
}
