package com.spotter.ui.workout

import com.spotter.data.model.SetLogOut

/**
 * Keeps the set you're on in view during a workout — pure decisions, so they're unit-testable;
 * [WorkoutScreen] turns them into list scrolls.
 *
 * The complaint it fixes: the list always opened at the top (and a completed set's rest ring
 * pushed the next set off the bottom), so every return to a workout meant scrolling back down.
 */
internal object WorkoutAutoScroll {

    /** The set to do next: its list item ([blockIndex]) and id. */
    data class CurrentSet(val blockIndex: Int, val setId: String)

    enum class Move {
        /** First look at this workout on this screen: go straight to the current exercise. */
        JUMP,

        /** The previous exercise is done: glide to the next one. */
        ANIMATE,

        /** Next set of the same exercise: only nudge it into view if it isn't. */
        REVEAL,

        /** Nothing to do (no change, all done, or the lifter went back to an earlier set). */
        NONE,
    }

    /**
     * The first incomplete set in the order the list is worked. [blocks] mirrors the LazyColumn:
     * one entry per item, each holding its exercise cards' sets. A standalone card is worked set
     * by set; a superset round-robin (A1 set 1, A2 set 1, A1 set 2 …), so within a block sets are
     * ordered by (set number, card position).
     */
    fun currentSet(blocks: List<List<List<SetLogOut>>>): CurrentSet? {
        blocks.forEachIndexed { blockIndex, cards ->
            val next = cards
                .flatMapIndexed { position, sets -> sets.map { it to position } }
                .filter { (set, _) -> !set.completed }
                .minWithOrNull(compareBy({ it.first.setNumber }, { it.second }))
            if (next != null) return CurrentSet(blockIndex, next.first.id)
        }
        return null
    }

    /**
     * What to do now that the current set is [current], given the one last acted on
     * ([previousBlock]/[previousSetId]; null block = never on this screen instance).
     * Moving *backwards* (un-ticking an earlier set) never scrolls: the lifter is already
     * looking at it.
     */
    fun move(previousBlock: Int?, previousSetId: String?, current: CurrentSet?): Move = when {
        current == null -> Move.NONE
        previousBlock == null -> Move.JUMP
        current.setId == previousSetId -> Move.NONE
        current.blockIndex > previousBlock -> Move.ANIMATE
        current.blockIndex == previousBlock -> Move.REVEAL
        else -> Move.NONE
    }
}
