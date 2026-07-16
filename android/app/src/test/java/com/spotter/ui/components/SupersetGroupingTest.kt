package com.spotter.ui.components

import com.spotter.ui.components.SupersetGrouping.Block
import com.spotter.ui.components.SupersetGrouping.Single
import com.spotter.ui.components.SupersetGrouping.Superset
import org.junit.Assert.assertEquals
import org.junit.Test

class SupersetGroupingTest {

    private data class Ex(val name: String, val group: Int?)

    private fun group(vararg ex: Ex): List<Block<Ex>> =
        SupersetGrouping.group(ex.toList()) { it.group }

    @Test
    fun `empty list yields no blocks`() {
        assertEquals(emptyList<Block<Ex>>(), SupersetGrouping.group(emptyList<Ex>()) { null })
    }

    @Test
    fun `all ungrouped are singles`() {
        val blocks = group(Ex("a", null), Ex("b", null), Ex("c", null))
        assertEquals(3, blocks.size)
        assert(blocks.all { it is Single })
    }

    @Test
    fun `consecutive shared group becomes a superset`() {
        val blocks = group(Ex("a", null), Ex("b", 1), Ex("c", 1), Ex("d", null))
        assertEquals(3, blocks.size)
        assert(blocks[0] is Single)
        val ss = blocks[1] as Superset
        assertEquals(1, ss.group)
        assertEquals(listOf("b", "c"), ss.items.map { it.name })
        assert(blocks[2] is Single)
    }

    @Test
    fun `two adjacent supersets stay separate blocks`() {
        val blocks = group(Ex("a", 1), Ex("b", 1), Ex("c", 2), Ex("d", 2))
        assertEquals(2, blocks.size)
        assertEquals(1, (blocks[0] as Superset).group)
        assertEquals(2, (blocks[1] as Superset).group)
    }

    @Test
    fun `lone member of a group is a single not a superset`() {
        val blocks = group(Ex("a", 1), Ex("b", null))
        assertEquals(2, blocks.size)
        assert(blocks[0] is Single)
        assertEquals("a", (blocks[0] as Single).item.name)
    }

    @Test
    fun `three-member superset keeps order`() {
        val blocks = group(Ex("a", 3), Ex("b", 3), Ex("c", 3))
        assertEquals(1, blocks.size)
        val ss = blocks[0] as Superset
        assertEquals(listOf("a", "b", "c"), ss.items.map { it.name })
    }

    @Test
    fun `labels are letter and position based`() {
        assertEquals("A", SupersetGrouping.groupLabel(1))
        assertEquals("B", SupersetGrouping.groupLabel(2))
        assertEquals("A1", SupersetGrouping.positionLabel(1, 0))
        assertEquals("A2", SupersetGrouping.positionLabel(1, 1))
        assertEquals("B1", SupersetGrouping.positionLabel(2, 0))
    }
}
