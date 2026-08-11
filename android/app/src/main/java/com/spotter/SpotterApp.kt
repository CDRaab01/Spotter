package com.spotter

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.spotter.di.ApplicationScope
import com.spotter.util.ActiveWorkoutNotifier
import com.spotter.util.AppPreferences
import com.spotter.util.NetworkSyncObserver
import com.spotter.util.SuiteConfigReader
import com.spotter.util.nudge.WorkoutNudgeScheduler
import com.spotter.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SpotterApp : Application(), Configuration.Provider {

    @Inject lateinit var networkSyncObserver: NetworkSyncObserver
    @Inject lateinit var activeWorkoutNotifier: ActiveWorkoutNotifier
    @Inject lateinit var suiteConfigReader: SuiteConfigReader
    @Inject lateinit var widgetUpdater: WidgetUpdater
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var nudgeScheduler: WorkoutNudgeScheduler
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    // Custom WorkManager config so @HiltWorker workers (the morning nudge) can be constructed with
    // their injected dependencies. The default initializer is removed in the manifest.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        networkSyncObserver.register()
        activeWorkoutNotifier.register()
        // Keep the home-screen "today's workout" widget in step with the workout/session state.
        widgetUpdater.register()
        // Adopt the server URL the Dragonfly hub manages, if it's installed and same-signed.
        suiteConfigReader.sync()
        // Keep the nudges scheduled to match the opt-in preference AND the user's chosen times —
        // all three together, since moving a time has to re-enqueue the work, not just re-run it.
        appScope.launch {
            combine(
                appPreferences.workoutNudgeEnabled,
                appPreferences.morningNudgeTime,
                appPreferences.eveningNudgeTime,
            ) { enabled, morning, evening -> Triple(enabled, morning, evening) }
                .distinctUntilChanged()
                .collectLatest { (enabled, morning, evening) ->
                    nudgeScheduler.sync(enabled, morning, evening)
                }
        }
    }
}
