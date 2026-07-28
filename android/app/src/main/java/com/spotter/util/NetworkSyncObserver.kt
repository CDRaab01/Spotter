package com.spotter.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.MetricRepository
import com.spotter.data.repository.ProfileRepository
import com.spotter.data.repository.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers a ConnectivityManager callback and triggers a sync whenever the
 * device re-gains internet access. This ensures that any offline-queued workout
 * edits reach the server as soon as connectivity is restored — without requiring
 * the user to open the Home screen first.
 */
@Singleton
class NetworkSyncObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val metricRepository: MetricRepository,
    private val exerciseRepository: ExerciseRepository,
    private val profileRepository: ProfileRepository,
    private val appPreferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register() {
        // Reconnect-sync is a convenience (Home-screen sync still runs), so a failure
        // here must never crash app startup — e.g. a missing permission or an OEM that
        // restricts the API. Swallow and log instead.
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        try { sessionRepository.syncPending() } catch (_: Exception) {}
                        // Drain offline-logged weigh-ins too, so a bodyweight entry made offline
                        // reaches the server on reconnect without waiting for a screen open.
                        try { metricRepository.sync() } catch (_: Exception) {}
                        // Push a training-profile edit made offline, then re-pull the server copy
                        // (the coach's memory of the user's equipment lives there now).
                        try { profileRepository.refresh() } catch (_: Exception) {}
                        // Best-effort exercise-catalog seed (offline search / preset resolution /
                        // offline muscle-group summary). Reaching the server here also means the
                        // queues above just drained, so stamp the stale-banner freshness marker.
                        if (exerciseRepository.refreshCatalog()) {
                            runCatching {
                                appPreferences.setLastSuccessfulSyncMs(System.currentTimeMillis())
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.w("NetworkSyncObserver", "Could not register network callback: ${e.message}")
        }
    }
}
