package com.spotter.ui.cardio

import androidx.lifecycle.ViewModel
import com.spotter.data.model.CardioPhase
import com.spotter.data.model.Interval
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class FreeRunConfigViewModel @Inject constructor(
    private val controller: CardioRunController,
) : ViewModel() {

    fun startOpenEnded() = controller.startFree(openEnded = true, intervals = emptyList())

    fun startCustom(
        warmUpMin: Int,
        runMin: Int,
        walkMin: Int,
        repeats: Int,
        coolDownMin: Int,
    ) {
        val intervals = buildList {
            if (warmUpMin > 0) add(Interval(CardioPhase.WARM_UP, warmUpMin * 60))
            repeat(repeats.coerceAtLeast(1)) {
                if (runMin > 0) add(Interval(CardioPhase.RUN, runMin * 60))
                if (walkMin > 0) add(Interval(CardioPhase.WALK, walkMin * 60))
            }
            if (coolDownMin > 0) add(Interval(CardioPhase.COOL_DOWN, coolDownMin * 60))
        }
        // Fall back to open-ended if the config produced nothing runnable.
        if (intervals.isEmpty()) controller.startFree(openEnded = true, intervals = emptyList())
        else controller.startFree(openEnded = false, intervals = intervals)
    }
}
