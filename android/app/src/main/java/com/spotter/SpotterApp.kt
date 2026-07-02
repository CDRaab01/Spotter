package com.spotter

import android.app.Application
import com.spotter.util.ActiveWorkoutNotifier
import com.spotter.util.NetworkSyncObserver
import com.spotter.util.SuiteConfigReader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SpotterApp : Application() {

    @Inject lateinit var networkSyncObserver: NetworkSyncObserver
    @Inject lateinit var activeWorkoutNotifier: ActiveWorkoutNotifier
    @Inject lateinit var suiteConfigReader: SuiteConfigReader

    override fun onCreate() {
        super.onCreate()
        networkSyncObserver.register()
        activeWorkoutNotifier.register()
        // Adopt the server URL the Dragonfly hub manages, if it's installed and same-signed.
        suiteConfigReader.sync()
    }
}
