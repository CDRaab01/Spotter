package com.spotter.ui.cardio

import androidx.lifecycle.ViewModel
import com.spotter.data.model.Interval
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin VM over [CardioRunController] — the run is owned by the (singleton) controller so it
 * survives this VM's death (rotation, leaving and resuming the screen). This just exposes the
 * live state and forwards control actions.
 */
@HiltViewModel
class CardioRunViewModel @Inject constructor(
    private val controller: CardioRunController,
) : ViewModel() {

    val state: StateFlow<CardioRunState?> = controller.state

    /** Launch a Free Run (used by the config screen via this VM). */
    fun startFree(openEnded: Boolean, intervals: List<Interval>) =
        controller.startFree(openEnded, intervals)

    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun skipWarmup() = controller.skipWarmup()
    fun finish() = controller.finish()

    /** Leave without finishing — the session stays in progress and is resumable. */
    fun pauseAndExit() = controller.pauseAndExit()

    /** Acknowledge a completed run and clear the live state. */
    fun clear() = controller.clear()
}
