package com.spotter.ui.settings

import com.spotter.data.model.EquipmentInventory
import com.spotter.data.model.EquipmentOut
import com.spotter.data.model.PlatePair
import com.spotter.ui.theme.formatWeight
import com.spotter.util.Loading
import com.spotter.util.WeightUnit
import org.junit.Test
import kotlin.test.assertEquals

class EquipmentOptionsTest {

    @Test
    fun `unset equipment says what is being assumed`() {
        assertEquals(
            "Not set — suggestions assume a standard gym",
            EquipmentOptions.summary(EquipmentOut(configured = false, inventory = Loading.DEFAULT_LB)),
        )
    }

    @Test
    fun `summary names the bar, smallest plate and dumbbell range`() {
        val out = EquipmentOut(
            configured = true,
            inventory = EquipmentInventory(
                unit = "lb",
                bars = listOf(45.0),
                plates = listOf(PlatePair(45.0, 2), PlatePair(2.5, 1)),
                dumbbells = (1..10).map { it * 5.0 },
            ),
        )
        assertEquals(
            "45 lb bar · plates down to 2.5 lb · dumbbells 5–50 lb in 5 lb steps",
            EquipmentOptions.summary(out),
        )
    }

    @Test
    fun `uneven dumbbells are listed`() {
        assertEquals("10, 15, 25 lb", EquipmentOptions.dumbbellSummary(listOf(25.0, 10.0, 15.0), "lb"))
        assertEquals("none", EquipmentOptions.dumbbellSummary(emptyList(), "lb"))
    }

    @Test
    fun `setting pairs adds, updates and removes a plate size`() {
        var inv = EquipmentInventory(plates = listOf(PlatePair(45.0, 2)))
        inv = EquipmentOptions.withPlatePairs(inv, 2.5, 1)
        assertEquals(listOf(PlatePair(45.0, 2), PlatePair(2.5, 1)), inv.plates)
        inv = EquipmentOptions.withPlatePairs(inv, 45.0, 3)
        assertEquals(3, EquipmentOptions.pairsFor(inv, 45.0))
        inv = EquipmentOptions.withPlatePairs(inv, 2.5, 0)
        assertEquals(listOf(PlatePair(45.0, 3)), inv.plates)
    }

    @Test
    fun `smallest barbell jump is two of the smallest plate`() {
        assertEquals(5.0, EquipmentOptions.smallestBarJump(Loading.DEFAULT_LB))
        assertEquals(null, EquipmentOptions.smallestBarJump(EquipmentInventory()))
    }

    @Test
    fun `owned oddities are never hidden from the editor`() {
        assertEquals(listOf(55.0, 45.0, 35.0), EquipmentOptions.merged(listOf(45.0, 35.0), listOf(55.0), descending = true))
    }

    @Test
    fun `weights are rounded, not truncated`() {
        // The "+2 lb" report: 117.5 used to render as "117 lb".
        assertEquals("117.5 lb", WeightUnit.LBS.formatWeight(117.5))
        assertEquals("120 lb", WeightUnit.LBS.formatWeight(120.0))
        assertEquals("60 kg", WeightUnit.KG.formatWeight(60 / Loading.KG_PER_LB))
        assertEquals("61.2 kg", WeightUnit.KG.formatWeight(135.0))
    }
}
