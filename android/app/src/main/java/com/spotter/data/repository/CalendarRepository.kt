package com.spotter.data.repository

import com.spotter.data.model.CalendarEntry
import com.spotter.data.remote.ApiService
import javax.inject.Inject

class CalendarRepository @Inject constructor(private val api: ApiService) {
    suspend fun getCalendar(from: String, to: String): List<CalendarEntry> =
        api.getCalendar(from, to)
}
