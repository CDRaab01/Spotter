package com.spotter.history

import com.spotter.data.model.SessionOut
import com.spotter.data.model.SetLogOut
import com.spotter.ui.history.copiedRoutineName
import com.spotter.ui.history.routineExercisesFromSession
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTemplateTest {

    private fun set(
        exerciseId: String,
        setNumber: Int,
        reps: Int,
        weight: Double?,
        completed: Boolean = true,
    ) = SetLogOut(
        id = "$exerciseId-$setNumber", sessionId = "s1", exerciseId = exerciseId,
        setNumber = setNumber, reps = reps, weight = weight, completed = completed,
    )

    private fun session(
        logs: List<SetLogOut>,
        routineName: String? = "Push Day",
        date: String = "2026-07-28",
    ) = SessionOut(
        id = "s1", userId = "u1", routineId = "r1", routineName = routineName,
        date = date, status = "completed", setLogs = logs,
    )

    @Test
    fun `derives sets, reps and weight from the completed sets`() {
        val out = routineExercisesFromSession(
            session(
                listOf(
                    set("bench", 1, 8, 115.0),
                    set("bench", 2, 8, 115.0),
                    set("bench", 3, 7, 125.0),
                )
            )
        )
        assertEquals(1, out.size)
        assertEquals("bench", out[0].exerciseId)
        assertEquals(3, out[0].targetSets)
        assertEquals(8, out[0].targetReps)
        assertEquals(115.0, out[0].targetWeight)
        assertEquals(false, out[0].isBodyweight)
    }

    @Test
    fun `skipped sets do not count toward the template`() {
        val out = routineExercisesFromSession(
            session(
                listOf(
                    set("bench", 1, 8, 115.0),
                    set("bench", 2, 8, 115.0),
                    set("bench", 3, 8, 115.0, completed = false),
                )
            )
        )
        assertEquals(2, out.single().targetSets)
    }

    @Test
    fun `a tie on reps or weight resolves to the latest completed set`() {
        val out = routineExercisesFromSession(
            session(
                listOf(
                    set("squat", 1, 5, 135.0),
                    set("squat", 2, 5, 155.0),
                )
            )
        )
        assertEquals(155.0, out.single().targetWeight)
    }

    @Test
    fun `an exercise logged without weight becomes bodyweight`() {
        val out = routineExercisesFromSession(
            session(listOf(set("pullup", 1, 8, null), set("pullup", 2, 7, null)))
        )
        assertTrue(out.single().isBodyweight)
        assertNull(out.single().targetWeight)
    }

    @Test
    fun `exercises keep the order they were trained in and are numbered`() {
        val out = routineExercisesFromSession(
            session(
                listOf(
                    set("squat", 1, 5, 135.0),
                    set("bench", 1, 8, 115.0),
                    set("squat", 2, 5, 135.0),
                )
            )
        )
        assertEquals(listOf("squat", "bench"), out.map { it.exerciseId })
        assertEquals(listOf(0, 1), out.map { it.order })
        assertEquals(2, out[0].targetSets)
    }

    @Test
    fun `a session with nothing completed yields no template`() {
        val out = routineExercisesFromSession(
            session(listOf(set("bench", 1, 8, 115.0, completed = false)))
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `copy name uses the routine name, falling back to the date`() {
        assertEquals("Push Day (copy)", copiedRoutineName(session(emptyList())))
        assertEquals("2026-07-28 (copy)", copiedRoutineName(session(emptyList(), routineName = null)))
    }
}
