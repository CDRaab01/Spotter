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

    @Test
    fun `warmup returns three ramp-up sets at 40-60-80 percent`() {
        val sets = warmupSets(200.0)
        assertEquals(3, sets.size)
        assertEquals(listOf(40, 60, 80), sets.map { it.percent })
        assertEquals(listOf(8, 5, 3), sets.map { it.reps })
    }

    @Test
    fun `warmup weights are rounded to nearest 5 lb`() {
        // 135 * 0.4 = 54 -> 55; 135 * 0.6 = 81 -> 80; 135 * 0.8 = 108 -> 110
        val sets = warmupSets(135.0)
        assertEquals(listOf(55.0, 80.0, 110.0), sets.map { it.weightLbs })
    }

    @Test
    fun `warmup is empty for non-positive working weight`() {
        assert(warmupSets(0.0).isEmpty())
        assert(warmupSets(-50.0).isEmpty())
    }

    @Test
    fun `warmup weights never exceed the working weight`() {
        val working = 315.0
        assert(warmupSets(working).all { it.weightLbs < working })
    }

    private fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double) {
        assert(kotlin.math.abs(expected - actual) <= absoluteTolerance) {
            "Expected $expected but was $actual (tolerance ±$absoluteTolerance)"
        }
    }
}
