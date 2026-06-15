package com.spotter

import android.app.Application
import com.spotter.util.ActiveWorkoutNotifier
import com.spotter.util.NetworkSyncObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SpotterApp : Application() {

    @Inject lateinit var networkSyncObserver: NetworkSyncObserver
    @Inject lateinit var activeWorkoutNotifier: ActiveWorkoutNotifier

    override fun onCreate() {
        super.onCreate()
        networkSyncObserver.register()
        activeWorkoutNotifier.register()
    }
}
