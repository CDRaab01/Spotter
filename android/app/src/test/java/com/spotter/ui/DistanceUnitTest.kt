package com.spotter.ui

import com.spotter.ui.theme.formatDistance
import com.spotter.ui.theme.metersToDisplay
import com.spotter.ui.theme.parseToMeters
import com.spotter.util.DistanceUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class DistanceUnitTest {

    @Test
    fun `miles parse to meters`() {
        // 1 mile = 1609.344 m -> rounds to 1609.
        assertEquals(1609, DistanceUnit.MI.parseToMeters("1"))
        // A 5k run entered as 3.1 miles.
        assertEquals(4989, DistanceUnit.MI.parseToMeters("3.1"))
    }

    @Test
    fun `km parse to meters`() {
        assertEquals(5000, DistanceUnit.KM.parseToMeters("5"))
        assertEquals(1500, DistanceUnit.KM.parseToMeters("1.5"))
    }

    @Test
    fun `blank and invalid parse to null`() {
        assertNull(DistanceUnit.MI.parseToMeters(""))
        assertNull(DistanceUnit.MI.parseToMeters("   "))
        assertNull(DistanceUnit.MI.parseToMeters("abc"))
        // Negative distances are rejected.
        assertNull(DistanceUnit.KM.parseToMeters("-2"))
    }

    @Test
    fun `meters convert back to the display unit`() {
        assertEquals(1.0, DistanceUnit.KM.metersToDisplay(1000), 0.0001)
        assertEquals(1.0, DistanceUnit.MI.metersToDisplay(1609), 0.001)
    }

    @Test
    fun `round trip miles is stable to two decimals`() {
        val meters = DistanceUnit.MI.parseToMeters("3.11")!!
        assertEquals("3.11 mi", DistanceUnit.MI.formatDistance(meters))
    }
}
