package com.spotter.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import com.spotter.MainActivity

/**
 * Shared plumbing for Spotter's `specialUse` foreground timer services so the workout and cardio
 * services don't each re-implement channel creation, the API-34 `startForeground` type call, the
 * partial wake-lock, and the deep-link tap intent. Keeps the per-service code to just its
 * notification content.
 */
object ForegroundServiceSupport {

    /**
     * Call [Service.startForeground] with the `specialUse` type on API 34+ (required) and the
     * plain overload below it. All of Spotter's timer services are declared `specialUse`.
     */
    fun startForegroundSpecialUse(service: Service, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(id, notification)
        }
    }

    /** Create a low-importance, badge-free notification channel (idempotent). */
    fun ensureChannel(context: Context, channelId: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW).apply {
                this.description = description
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * A tap PendingIntent that re-opens [MainActivity] and deep-links to the given
     * [NotificationNav] target (+ optional session id), matching what the nav graph consumes.
     */
    fun tapIntent(context: Context, target: String, sessionId: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            target.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(NotificationNav.EXTRA_NAV_TARGET, target)
                sessionId?.let { putExtra(NotificationNav.EXTRA_SESSION_ID, it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/**
 * A non-reference-counted partial wake-lock with safe acquire/release. Keeps the CPU running so a
 * timer's loop keeps firing with the screen off; a foreground service is what keeps the process
 * alive. [backstopMs] is only a leak guard.
 */
class WakeLockHolder(
    private val context: Context,
    private val tag: String,
    private val backstopMs: Long,
) {
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
            acquire(backstopMs)
        }
    }

    @Synchronized
    fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
