package com.spotter.widget

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The fields the "today's workout" widget renders — persisted by [WidgetUpdater] and decoded by
 * [SpotterWidget]. Kept tiny and stable: the hero workout name, one status line, and an in-progress
 * flag (so the widget can tint the status when a session is live).
 */
@Serializable
data class WidgetData(
    val workoutName: String,
    val statusLine: String,
    val inProgress: Boolean = false,
)

/**
 * Pure assembly of [WidgetData] from already-fetched inputs — no I/O, so it's exhaustively unit
 * tested. [WidgetUpdater] does the Room reads and hands the results here. Mirrors the Home "next up"
 * logic: an in-progress session wins; otherwise the active program's soonest projected day, with
 * rest days and the no-program case handled explicitly.
 */
object WidgetContent {

    /** A workout is live today: hero is the routine name, status is the set progress. */
    fun inProgress(routineName: String?, doneSets: Int, totalSets: Int): WidgetData =
        WidgetData(
            workoutName = routineName?.trim()?.ifBlank { null } ?: "Workout in progress",
            statusLine = "$doneSets/$totalSets sets",
            inProgress = true,
        )

    /**
     * No session in progress — describe the next scheduled day.
     * @param slotDate the projected date, or null when there's nothing scheduled.
     * @param isRestDay the projected day is a program rest day (no routine).
     * @param hasActiveProgram whether an active program with days exists at all.
     */
    fun scheduled(
        today: LocalDate,
        slotDate: LocalDate?,
        routineName: String?,
        label: String?,
        isRestDay: Boolean,
        hasActiveProgram: Boolean,
    ): WidgetData {
        if (!hasActiveProgram || slotDate == null) {
            return WidgetData("No workout scheduled", "Open Spotter to plan a program", false)
        }
        val whenLabel = relativeDay(today, slotDate)
        if (isRestDay) {
            return WidgetData("Rest day", whenLabel, false)
        }
        val name = routineName?.trim()?.ifBlank { null }
            ?: label?.trim()?.ifBlank { null }
            ?: "Workout"
        val status = buildString {
            append(whenLabel)
            // Append the program day's own label only when it adds information beyond the name.
            val trimmedLabel = label?.trim()
            if (!trimmedLabel.isNullOrBlank() && !trimmedLabel.equals(name, ignoreCase = true)) {
                append(" · ")
                append(trimmedLabel)
            }
        }
        return WidgetData(name, status, false)
    }

    private fun relativeDay(today: LocalDate, date: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    }
}
