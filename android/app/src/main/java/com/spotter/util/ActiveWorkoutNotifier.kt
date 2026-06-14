package com.spotter.util

import android.content.Context
import com.spotter.ui.workout.WorkoutSessionService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the DB-derived [ActiveWorkoutStore] signal to the [WorkoutSessionService] foreground
 * notification: starts the service when a workout goes in-progress. The service self-stops when the
 * session ends, so this only needs to (re)start it on the false→true edge. Registered once from
 * [com.spotter.SpotterApp].
 */
@Singleton
class ActiveWorkoutNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeWorkoutStore: ActiveWorkoutStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun register() {
        scope.launch {
            activeWorkoutStore.activeSession
                .map { it != null }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) WorkoutSessionService.start(context)
                }
        }
    }
}
