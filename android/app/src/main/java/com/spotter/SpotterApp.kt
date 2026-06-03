package com.spotter

import android.app.Application
import com.spotter.util.NetworkSyncObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SpotterApp : Application() {

    @Inject lateinit var networkSyncObserver: NetworkSyncObserver

    override fun onCreate() {
        super.onCreate()
        networkSyncObserver.register()
    }
}
