package com.spotter.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WidgetContentTest {

    private val today = LocalDate.of(2026, 7, 15) // a Wednesday

    @Test
    fun inProgress_showsRoutineNameAndSetProgress() {
        val data = WidgetContent.inProgress("Push A", doneSets = 3, totalSets = 12)
        assertEquals("Push A", data.workoutName)
        assertEquals("3/12 sets", data.statusLine)
        assertTrue(data.inProgress)
    }

    @Test
    fun inProgress_blankNameFallsBack() {
        val data = WidgetContent.inProgress("  ", doneSets = 0, totalSets = 0)
        assertEquals("Workout in progress", data.workoutName)
        assertEquals("0/0 sets", data.statusLine)
    }

    @Test
    fun scheduled_noActiveProgram_promptsToPlan() {
        val data = WidgetContent.scheduled(
            today = today, slotDate = null, routineName = null, label = null,
            isRestDay = false, hasActiveProgram = false,
        )
        assertEquals("No workout scheduled", data.workoutName)
        assertEquals("Open Spotter to plan a program", data.statusLine)
        assertFalse(data.inProgress)
    }

    @Test
    fun scheduled_restDayToday() {
        val data = WidgetContent.scheduled(
            today = today, slotDate = today, routineName = null, label = "Rest",
            isRestDay = true, hasActiveProgram = true,
        )
        assertEquals("Rest day", data.workoutName)
        assertEquals("Today", data.statusLine)
    }

    @Test
    fun scheduled_todayWorkout_usesRoutineNameAsHero() {
        val data = WidgetContent.scheduled(
            today = today, slotDate = today, routineName = "Lower Body", label = "Day 2",
            isRestDay = false, hasActiveProgram = true,
        )
        assertEquals("Lower Body", data.workoutName)
        assertEquals("Today · Day 2", data.statusLine)
        assertFalse(data.inProgress)
    }

    @Test
    fun scheduled_tomorrow_showsTomorrow() {
        val data = WidgetContent.scheduled(
            today = today, slotDate = today.plusDays(1), routineName = "Pull", label = "Pull",
            isRestDay = false, hasActiveProgram = true,
        )
        assertEquals("Pull", data.workoutName)
        // Label equal to the name (case-insensitive) is not repeated in the status line.
        assertEquals("Tomorrow", data.statusLine)
    }

    @Test
    fun scheduled_futureDay_showsWeekdayName() {
        val friday = today.plusDays(2)
        val data = WidgetContent.scheduled(
            today = today, slotDate = friday, routineName = "Push", label = "Day 1",
            isRestDay = false, hasActiveProgram = true,
        )
        assertEquals("Push", data.workoutName)
        assertEquals("Friday · Day 1", data.statusLine)
    }

    @Test
    fun scheduled_noRoutineNameFallsBackToLabel() {
        val data = WidgetContent.scheduled(
            today = today, slotDate = today, routineName = null, label = "Full Body",
            isRestDay = false, hasActiveProgram = true,
        )
        assertEquals("Full Body", data.workoutName)
    }
}
