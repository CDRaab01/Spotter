package com.spotter.ui.cardio

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.spotter.util.ForegroundServiceSupport
import com.spotter.util.NotificationNav
import com.spotter.util.WakeLockHolder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the device awake-enough to run the cardio timer while the screen is off or the app is
 * backgrounded, and surfaces an ongoing notification with the current phase + countdown. It holds
 * no timer logic itself — [CardioRunController] is the source of truth; this just mirrors its
 * state and self-stops when the run ends (state becomes null or completes).
 */
@AndroidEntryPoint
class CardioRunService : Service() {

    @Inject lateinit var controller: CardioRunController

    private val scope = CoroutineScope(Dispatchers.Default)
    private var collectJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private val wakeLock by lazy { WakeLockHolder(this, WAKE_LOCK_TAG, MAX_WAKELOCK_MS) }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ForegroundServiceSupport.ensureChannel(this, CHANNEL_ID, "Cardio", "Ongoing cardio run")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ForegroundServiceSupport.startForegroundSpecialUse(
            this, NOTIFICATION_ID, buildNotification("Cardio", "Starting…"),
        )
        wakeLock.acquire()
        collectJob?.cancel()
        collectJob = scope.launch {
            controller.state.collectLatest { state ->
                if (state == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                notificationManager.notify(NOTIFICATION_ID, buildNotification(
                    title = state.label + (state.weekDayLabel?.let { " · $it" } ?: ""),
                    text = when {
                        state.isComplete -> "Completed · ${format(state.totalElapsedSec)}"
                        state.isOpenEnded -> "Running · ${format(state.totalElapsedSec)}"
                        state.isPaused -> "Paused · ${state.phase.label}"
                        else -> "${state.phase.label} · ${format(state.intervalRemainingSec)} left"
                    },
                ))
            }
        }
        return START_NOT_STICKY
    }

    private fun format(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun buildNotification(title: String, text: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                ForegroundServiceSupport.tapIntent(this, NotificationNav.TARGET_CARDIO, controller.activeSessionId),
            )
            .build()
    }

    override fun onDestroy() {
        collectJob?.cancel()
        wakeLock.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "spotter_cardio"
        const val NOTIFICATION_ID = 2001
        private const val WAKE_LOCK_TAG = "spotter:cardio_run"
        private const val MAX_WAKELOCK_MS = 6L * 60 * 60 * 1000  // 6h backstop

        fun start(context: Context) {
            val intent = Intent(context, CardioRunService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CardioRunService::class.java))
        }
    }
}
