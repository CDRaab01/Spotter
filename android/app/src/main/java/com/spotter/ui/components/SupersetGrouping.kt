package com.spotter.ui.components

/**
 * Pure presentation grouping for supersets. The server already derives a per-exercise
 * `supersetGroup` (nullable Int, 1-based) on routine exercises / set logs; this folds an ordered
 * list of exercises into display [Block]s so the UI can visually pair the ones that share a group
 * (the Hevy/Strong "A1/A2 with shared rest" convention) instead of rendering every exercise as an
 * isolated card.
 *
 * Kept free of Compose/Android types so the grouping rules are unit-testable. Members of a superset
 * are adjacent in exercise order, so grouping is done over *consecutive* equal, non-null groups —
 * a lone member (a group with a single exercise) is treated as an ordinary [Single], since a
 * one-exercise "superset" has nothing to pair with.
 */
object SupersetGrouping {

    sealed interface Block<out T> {
        val items: List<T>
    }

    data class Single<T>(val item: T) : Block<T> {
        override val items: List<T> get() = listOf(item)
    }

    data class Superset<T>(val group: Int, override val items: List<T>) : Block<T>

    /** Folds [items] into display blocks; [groupOf] extracts each item's nullable superset group. */
    fun <T> group(items: List<T>, groupOf: (T) -> Int?): List<Block<T>> {
        val blocks = mutableListOf<Block<T>>()
        var i = 0
        while (i < items.size) {
            val g = groupOf(items[i])
            if (g == null) {
                blocks.add(Single(items[i]))
                i++
                continue
            }
            // Gather the consecutive run sharing this non-null group.
            var j = i + 1
            while (j < items.size && groupOf(items[j]) == g) j++
            val run = items.subList(i, j)
            if (run.size >= 2) {
                blocks.add(Superset(g, run.toList()))
            } else {
                blocks.add(Single(run[0]))
            }
            i = j
        }
        return blocks
    }

    /** The letter label for a 1-based superset [group]: 1 -> "A", 2 -> "B". */
    fun groupLabel(group: Int): String = ('A' + (group - 1).coerceAtLeast(0)).toString()

    /** The per-member position tag within a group, e.g. group 1 index 0 -> "A1". */
    fun positionLabel(group: Int, indexInGroup: Int): String =
        "${groupLabel(group)}${indexInGroup + 1}"
}
