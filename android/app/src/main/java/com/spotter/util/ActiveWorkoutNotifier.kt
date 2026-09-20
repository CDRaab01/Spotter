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
 *
 * `Application.onCreate` also runs when the process is cold-started in the **background** (a widget
 * update, a nudge worker). With a workout in progress that edge fires immediately, and Android 12+
 * refuses a background foreground-service start by throwing — which used to crash the process. The
 * start is now non-throwing, and a refused one is retried from [onAppForegrounded].
 */
@Singleton
class ActiveWorkoutNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeWorkoutStore: ActiveWorkoutStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A start the OS refused (app was in the background) that is still owed to the user. */
    @Volatile private var startDeferred = false

    fun register() {
        scope.launch {
            activeWorkoutStore.activeSession
                .map { it != null }
                .distinctUntilChanged()
                .collect { active ->
                    startDeferred = active && !WorkoutSessionService.start(context)
                }
        }
    }

    /** Called when the UI comes to the foreground, where a foreground-service start is allowed. */
    fun onAppForegrounded() {
        if (startDeferred) startDeferred = !WorkoutSessionService.start(context)
    }
}
