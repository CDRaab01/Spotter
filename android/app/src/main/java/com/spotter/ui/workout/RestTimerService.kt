package com.spotter.ui.workout

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.spotter.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the between-sets rest countdown. It is the *authoritative* timer:
 * the countdown is measured from [SystemClock.elapsedRealtime] (drift-free, not a tick counter),
 * a partial wake lock keeps the CPU running so it fires on time with the screen off, and it
 * vibrates at completion itself — so the "rest's up" cue lands even when the app is backgrounded
 * or the screen is locked (the on-screen ring is just a mirror; the cue no longer depends on the
 * Compose screen being in the foreground).
 */
class RestTimerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var timerJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

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
        acquireWakeLock()
        timerJob = scope.launch {
            // Drift-free: anchor to a monotonic clock instead of decrementing a counter, so a
            // coalesced tick (e.g. screen-off Doze) never makes the rest end late.
            val endRealtime = SystemClock.elapsedRealtime() + totalSeconds * 1000L
            // Post the first notification synchronously so startForeground happens promptly
            // (required within 5s of startForegroundService on API 26+).
            startForeground(NOTIFICATION_ID, buildNotification(totalSeconds))
            while (true) {
                val remainingMs = endRealtime - SystemClock.elapsedRealtime()
                if (remainingMs <= 0) break
                val remainingSec = ((remainingMs + 999) / 1000).toInt()
                notificationManager.notify(NOTIFICATION_ID, buildNotification(remainingSec))
                delay(500)
            }
            vibrateDone()
            stopForeground(STOP_FOREGROUND_REMOVE)
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        stopSelf()
    }

    /** Buzz at the end of rest — the single authoritative cue, fired even when backgrounded. */
    private fun vibrateDone() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
            }
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Rests are short; the timeout is only a leak backstop.
            acquire(MAX_WAKELOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "spotter_rest_timer"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL = "com.spotter.REST_TIMER_CANCEL"
        const val EXTRA_SECONDS = "extra_seconds"
        private const val WAKE_LOCK_TAG = "spotter:rest_timer"
        private const val MAX_WAKELOCK_MS = 30L * 60 * 1000  // 30min backstop

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
