package com.spotter.ui.workout

import com.spotter.data.model.SetLogOut
import com.spotter.ui.workout.WorkoutAutoScroll.CurrentSet
import com.spotter.ui.workout.WorkoutAutoScroll.Move
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "During a session, automatically go down to the current set." */
class WorkoutAutoScrollTest {

    private fun set(id: String, number: Int, done: Boolean = false) = SetLogOut(
        id = id, sessionId = "s", exerciseId = id.take(1), setNumber = number, reps = 8,
        completed = done,
    )

    @Test
    fun `current set is the first incomplete one in list order`() {
        val blocks = listOf(
            listOf(listOf(set("a1", 1, done = true), set("a2", 2, done = true))),
            listOf(listOf(set("b1", 1, done = true), set("b2", 2), set("b3", 3))),
            listOf(listOf(set("c1", 1))),
        )
        assertEquals(CurrentSet(1, "b2"), WorkoutAutoScroll.currentSet(blocks))
    }

    @Test
    fun `a skipped set stays current even when later ones are done`() {
        val blocks = listOf(listOf(listOf(set("a1", 1), set("a2", 2, done = true))))
        assertEquals(CurrentSet(0, "a1"), WorkoutAutoScroll.currentSet(blocks))
    }

    @Test
    fun `supersets are worked round robin`() {
        // A1 set 1 done → next is A2 set 1, not A1 set 2.
        val superset = listOf(
            listOf(set("a1", 1, done = true), set("a2", 2)),
            listOf(set("x1", 1), set("x2", 2)),
        )
        assertEquals(CurrentSet(0, "x1"), WorkoutAutoScroll.currentSet(listOf(superset)))
    }

    @Test
    fun `all done means nothing is current`() {
        assertNull(WorkoutAutoScroll.currentSet(listOf(listOf(listOf(set("a1", 1, done = true))))))
    }

    @Test
    fun `opening a workout jumps to the current set`() {
        assertEquals(Move.JUMP, WorkoutAutoScroll.move(null, null, CurrentSet(3, "d1")))
    }

    @Test
    fun `finishing an exercise glides to the next`() {
        assertEquals(Move.ANIMATE, WorkoutAutoScroll.move(1, "b3", CurrentSet(2, "c1")))
    }

    @Test
    fun `the next set of the same exercise is only revealed`() {
        assertEquals(Move.REVEAL, WorkoutAutoScroll.move(1, "b2", CurrentSet(1, "b3")))
    }

    @Test
    fun `going back to an earlier set never scrolls`() {
        assertEquals(Move.NONE, WorkoutAutoScroll.move(2, "c1", CurrentSet(0, "a2")))
        assertEquals(Move.NONE, WorkoutAutoScroll.move(2, "c1", CurrentSet(2, "c1")))
        assertEquals(Move.NONE, WorkoutAutoScroll.move(2, "c1", null))
    }
}
