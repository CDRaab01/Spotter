package com.spotter.data.repository

import com.spotter.data.model.CalendarEntry
import com.spotter.data.remote.ApiService
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The calendar is a read-only, derived view (past sessions + scheduled workouts). It has no local
 * store, so an offline read used to throw. Cache the last successful fetch per date range in memory
 * and serve it on failure, so the screen degrades to last-known instead of crashing (the 1.0 bar:
 * never a throw). In-memory per process is enough for a secondary, non-critical view.
 */
@Singleton
class CalendarRepository @Inject constructor(private val api: ApiService) {
    private val cache = ConcurrentHashMap<String, List<CalendarEntry>>()

    suspend fun getCalendar(from: String, to: String): List<CalendarEntry> {
        val key = "$from..$to"
        return try {
            api.getCalendar(from, to).also { cache[key] = it }
        } catch (_: Exception) {
            cache[key] ?: emptyList()
        }
    }
}
