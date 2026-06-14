package com.spotter.ui.cardio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.spotter.MainActivity
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

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification("Cardio", "Starting…"))
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

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun format(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun buildNotification(title: String, text: String): android.app.Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cardio",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing cardio run"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "spotter_cardio"
        const val NOTIFICATION_ID = 2001

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
