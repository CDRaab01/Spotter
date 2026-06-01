package com.spotter.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.CalendarEntry
import com.spotter.data.repository.CalendarRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
) : ViewModel() {

    private val _displayedMonth = MutableStateFlow(YearMonth.now())
    val displayedMonth: StateFlow<YearMonth> = _displayedMonth.asStateFlow()

    private val _entries = MutableStateFlow<UiState<List<CalendarEntry>>>(UiState.Idle)
    val entries: StateFlow<UiState<List<CalendarEntry>>> = _entries

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    init {
        loadMonth(YearMonth.now())
    }

    fun loadMonth(month: YearMonth) {
        _displayedMonth.value = month
        _selectedDate.value = null
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

    fun nextMonth() = loadMonth(_displayedMonth.value.plusMonths(1))
    fun prevMonth() = loadMonth(_displayedMonth.value.minusMonths(1))

    fun selectDate(date: LocalDate) {
        _selectedDate.value = if (_selectedDate.value == date) null else date
    }
}
