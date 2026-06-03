package com.spotter.ui.workout

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.spotter.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RestTimerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var timerJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            cancelTimer()
            return START_NOT_STICKY
        }
        val seconds = intent?.getIntExtra(EXTRA_SECONDS, 0) ?: 0
        if (seconds > 0) startCountdown(seconds)
        return START_NOT_STICKY
    }

    private fun startCountdown(totalSeconds: Int) {
        timerJob?.cancel()
        timerJob = scope.launch {
            var remaining = totalSeconds
            // Post the first notification synchronously so startForeground is called
            // promptly (required within 5s of startForegroundService on API 26+).
            val initialNotification = buildNotification(remaining)
            startForeground(NOTIFICATION_ID, initialNotification)
            while (remaining > 0) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(remaining))
                delay(1000)
                remaining--
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(secondsRemaining: Int): android.app.Notification {
        val min = secondsRemaining / 60
        val sec = secondsRemaining % 60
        val timeText = "%d:%02d".format(min, sec)

        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Rest timer")
            .setContentText("$timeText remaining")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rest Timer",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Countdown between workout sets"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "spotter_rest_timer"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL = "com.spotter.REST_TIMER_CANCEL"
        const val EXTRA_SECONDS = "extra_seconds"

        fun startIntent(context: Context, seconds: Int): Intent =
            Intent(context, RestTimerService::class.java).apply {
                putExtra(EXTRA_SECONDS, seconds)
            }

        fun cancelIntent(context: Context): Intent =
            Intent(context, RestTimerService::class.java).apply {
                action = ACTION_CANCEL
            }
    }
}
