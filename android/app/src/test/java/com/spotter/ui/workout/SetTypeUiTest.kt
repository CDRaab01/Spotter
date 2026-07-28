package com.spotter.ui.workout

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure display/input helpers behind the set-type badge and the RPE entry. */
class SetTypeUiTest {

    @Test
    fun `badge letters map W D F A and normal has none`() {
        assertEquals("W", setTypeBadge("warmup"))
        assertEquals("D", setTypeBadge("drop"))
        assertEquals("F", setTypeBadge("failure"))
        assertEquals("A", setTypeBadge("amrap"))
        assertNull(setTypeBadge("normal"))
        assertNull(setTypeBadge("something-new")) // unknown server value degrades to no badge
    }

    @Test
    fun `set type vocabulary matches the server enum`() {
        assertEquals(listOf("normal", "warmup", "drop", "failure", "amrap"), SET_TYPES)
    }

    @Test
    fun `rpe input filter keeps digits and a single one-decimal dot`() {
        assertEquals("8", sanitizeRpeInput("8"))
        assertEquals("8.5", sanitizeRpeInput("8.5"))
        assertEquals("8.5", sanitizeRpeInput("8.55")) // one decimal max
        assertEquals("8.5", sanitizeRpeInput("8.5.5")) // second dot dropped
        assertEquals("10", sanitizeRpeInput("105")) // two whole digits max
        assertEquals("9.5", sanitizeRpeInput("9x.5y"))
    }

    @Test
    fun `parseRpe clamps to 1-10 and rounds to one decimal`() {
        assertEquals(8.5, parseRpe("8.5"))
        assertEquals(10.0, parseRpe("10"))
        assertEquals(10.0, parseRpe("10.0"))
        assertEquals(1.0, parseRpe("0.5")) // under-floor clamps up
        assertNull(parseRpe("")) // blank clears the entry
        assertNull(parseRpe("."))
    }

    @Test
    fun `formatRpe drops the decimal on whole values`() {
        assertEquals("8", formatRpe(8.0))
        assertEquals("8.5", formatRpe(8.5))
    }
}
