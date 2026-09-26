package com.spotter.util

import com.spotter.data.model.EquipmentInventory
import com.spotter.data.model.PlatePair
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client mirror of the server's `app/loading.py` — same cases as `tests/test_loading.py`, so the
 * plate calculator and warm-ups agree with what the server suggests. Plus the plate calculator's
 * own search, which has to respect how many plates the user actually owns.
 */
class LoadingTest {

    private val homeGym = EquipmentInventory(
        unit = "lb",
        bars = listOf(45.0),
        plates = listOf(
            PlatePair(45.0, 2), PlatePair(25.0, 1), PlatePair(10.0, 1),
            PlatePair(5.0, 1), PlatePair(2.5, 1),
        ),
        dumbbells = listOf(10.0, 15.0, 20.0, 25.0, 35.0, 50.0),
        stackStep = null,
    )

    private fun bar(inv: EquipmentInventory) = Loading.ladder("barbell", "Bench Press", inv)!!

    // ── modes ──────────────────────────────────────────────────────────────────

    @Test
    fun `modes follow the catalog equipment`() {
        assertEquals(Loading.BARBELL, Loading.mode("barbell", "Bench Press"))
        assertEquals(Loading.SINGLE, Loading.mode("barbell", "T-Bar Row"))
        assertEquals(Loading.DUMBBELL, Loading.mode("Dumbbell", "Dumbbell Curl"))
        assertEquals(Loading.STACK, Loading.mode("cable", "Lat Pulldown"))
        assertEquals(Loading.STACK, Loading.mode("machine", "Leg Press"))
        assertNull(Loading.mode("bodyweight", "Pull-Up"))
        assertNull(Loading.ladder("bodyweight", "Push-Up", Loading.DEFAULT_LB))
    }

    // ── barbell ────────────────────────────────────────────────────────────────

    @Test
    fun `default barbell moves five pounds at a time`() {
        val ladder = bar(Loading.DEFAULT_LB)
        assertEquals(120.0, ladder.nextUp(115.0, 2.5))
        assertFalse(ladder.contains(117.5))
    }

    @Test
    fun `plate counts cap the bar`() {
        val ladder = bar(homeGym)
        assertEquals(310.0, ladder.loads.last())
        assertNull(ladder.nextUp(310.0, 5.0))
    }

    @Test
    fun `limited plates still find the right combination`() {
        val inv = homeGym.copy(plates = listOf(PlatePair(45.0, 1), PlatePair(25.0, 1)))
        assertEquals(listOf(45.0, 95.0, 135.0, 185.0), bar(inv).loads)
    }

    @Test
    fun `every bar counts`() {
        val inv = homeGym.copy(bars = listOf(45.0, 25.0), plates = listOf(PlatePair(10.0, 1), PlatePair(5.0, 1)))
        assertEquals(listOf(25.0, 35.0, 45.0, 55.0), bar(inv).loads.take(4))
    }

    // ── other implements ───────────────────────────────────────────────────────

    @Test
    fun `t-bar adds one plate at a time`() {
        val ladder = Loading.ladder("barbell", "T-Bar Row", homeGym)!!
        assertEquals(listOf(2.5, 5.0, 7.5, 10.0), ladder.loads.take(4))
    }

    @Test
    fun `dumbbells jump to the next pair that exists`() {
        val ladder = Loading.ladder("dumbbell", "Dumbbell Curl", homeGym)!!
        assertEquals(35.0, ladder.nextUp(25.0, 2.5))
    }

    @Test
    fun `stack moves by its pin and needs one listed`() {
        val inv = homeGym.copy(stackStep = 10.0)
        assertEquals(110.0, Loading.ladder("cable", "Lat Pulldown", inv)!!.nextUp(100.0, 2.5))
        assertNull(Loading.ladder("cable", "Lat Pulldown", homeGym))
    }

    // ── ladder queries ─────────────────────────────────────────────────────────

    @Test
    fun `floor nearest and nearestBelow`() {
        val ladder = bar(Loading.DEFAULT_LB)
        assertEquals(115.0, ladder.floor(117.5))
        assertNull(ladder.floor(10.0))
        assertEquals(115.0, ladder.nearest(117.5)) // tie → lighter
        assertEquals(120.0, ladder.nearest(118.0))
        assertEquals(105.0, ladder.nearestBelow(103.5, ceiling = 115.0))
        assertNull(ladder.nearestBelow(14.0, ceiling = 15.0))
    }

    @Test
    fun `kg inventory ladders are real kg loads expressed in pounds`() {
        val ladder = bar(Loading.DEFAULT_KG)
        val sixty = 60 / Loading.KG_PER_LB
        assertTrue(ladder.contains(sixty))
        val next = assertNotNull(ladder.nextUp(sixty, 2.5))
        assertTrue(abs(Loading.fromLb(next, "kg") - 62.5) < 0.01)
    }

    // ── plate calculator ───────────────────────────────────────────────────────

    @Test
    fun `plate load splits evenly across both sides`() {
        val load = assertNotNull(Loading.plateLoad(135.0, 45.0, Loading.DEFAULT_LB.plates))
        assertEquals(135.0, load.total)
        assertEquals(listOf(45.0 to 1), load.plates)
    }

    @Test
    fun `plate load respects owned counts where greedy would not`() {
        // Per side 50: greedy takes the one 45 and is stuck at 45; two 25s make it exactly.
        val plates = listOf(PlatePair(45.0, 1), PlatePair(25.0, 2))
        val load = assertNotNull(Loading.plateLoad(145.0, 45.0, plates))
        assertEquals(145.0, load.total)
        assertEquals(listOf(25.0 to 2), load.plates)
    }

    @Test
    fun `plate load prefers fewer plates at the same total`() {
        val plates = listOf(PlatePair(25.0, 2), PlatePair(10.0, 5))
        val load = assertNotNull(Loading.plateLoad(145.0, 45.0, plates))
        assertEquals(listOf(25.0 to 2), load.plates)
    }

    @Test
    fun `plate load reports the closest it can make`() {
        val load = assertNotNull(Loading.plateLoad(102.0, 45.0, Loading.DEFAULT_LB.plates))
        assertEquals(100.0, load.total)
    }

    @Test
    fun `single sided load uses both plates of a pair and no bar`() {
        val plates = listOf(PlatePair(10.0, 1), PlatePair(5.0, 1))
        val load = assertNotNull(Loading.plateLoad(20.0, null, plates))
        assertEquals(20.0, load.total)
        assertEquals(listOf(10.0 to 2), load.plates)
    }

    @Test
    fun `lighter than the bar has no answer`() {
        assertNull(Loading.plateLoad(40.0, 45.0, Loading.DEFAULT_LB.plates))
    }

    // ── warm-up ramp ───────────────────────────────────────────────────────────

    @Test
    fun `warm-up lands on loadable weights and never reaches the working weight`() {
        val onlyOlympic = EquipmentInventory(
            unit = "lb", bars = listOf(45.0),
            plates = listOf(PlatePair(10.0, 2), PlatePair(5.0, 1)),
        )
        // Loads: 45, 55, 65, 75, 85. 40/60/80 % of 85 = 34 → 45 (the bar), 51 → 55, 68 → 65.
        val sets = warmupSets(85.0, ladder = bar(onlyOlympic))
        assertEquals(listOf(45.0, 55.0, 65.0), sets.map { it.weightLbs })
        assertTrue(sets.all { it.weightLbs < 85.0 })
    }
}
