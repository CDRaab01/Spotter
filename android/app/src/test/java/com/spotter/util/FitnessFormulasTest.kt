package com.spotter.util

import org.junit.Test
import kotlin.test.assertEquals

class FitnessFormulasTest {

    @Test
    fun `single rep returns input weight unchanged`() {
        assertEquals(100.0, estimatedOneRM(100.0, 1))
    }

    @Test
    fun `zero reps returns input weight unchanged`() {
        assertEquals(80.0, estimatedOneRM(80.0, 0))
    }

    @Test
    fun `five reps at 100 lb applies Epley formula`() {
        val expected = 100.0 * (1 + 5 / 30.0)
        assertEquals(expected, estimatedOneRM(100.0, 5), absoluteTolerance = 0.001)
    }

    @Test
    fun `ten reps at 200 lb applies Epley formula`() {
        val expected = 200.0 * (1 + 10 / 30.0)
        assertEquals(expected, estimatedOneRM(200.0, 10), absoluteTolerance = 0.001)
    }

    @Test
    fun `estimated 1RM exceeds working weight for multi-rep sets`() {
        val rm = estimatedOneRM(225.0, 5)
        assert(rm > 225.0) { "Expected 1RM ($rm) to exceed working weight (225)" }
    }

    @Test
    fun `higher rep count produces higher 1RM estimate for same weight`() {
        val rm8 = estimatedOneRM(135.0, 8)
        val rm5 = estimatedOneRM(135.0, 5)
        assert(rm8 > rm5) { "8-rep 1RM ($rm8) should exceed 5-rep 1RM ($rm5)" }
    }

    private fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double) {
        assert(kotlin.math.abs(expected - actual) <= absoluteTolerance) {
            "Expected $expected but was $actual (tolerance ±$absoluteTolerance)"
        }
    }
}
