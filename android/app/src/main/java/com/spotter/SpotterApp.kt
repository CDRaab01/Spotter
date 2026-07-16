package com.spotter

import android.app.Application
import com.spotter.util.ActiveWorkoutNotifier
import com.spotter.util.NetworkSyncObserver
import com.spotter.util.SuiteConfigReader
import com.spotter.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SpotterApp : Application() {

    @Inject lateinit var networkSyncObserver: NetworkSyncObserver
    @Inject lateinit var activeWorkoutNotifier: ActiveWorkoutNotifier
    @Inject lateinit var suiteConfigReader: SuiteConfigReader
    @Inject lateinit var widgetUpdater: WidgetUpdater

    override fun onCreate() {
        super.onCreate()
        networkSyncObserver.register()
        activeWorkoutNotifier.register()
        // Keep the home-screen "today's workout" widget in step with the workout/session state.
        widgetUpdater.register()
        // Adopt the server URL the Dragonfly hub manages, if it's installed and same-signed.
        suiteConfigReader.sync()
    }
}
