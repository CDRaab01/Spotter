package com.spotter.ui.workout

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
import com.spotter.data.local.dao.SetLogDao
import com.spotter.util.ActiveWorkoutStore
import com.spotter.util.NotificationNav
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Surfaces an ongoing "workout in progress" notification while a strength session is live — the
 * counterpart to [com.spotter.ui.cardio.CardioRunService]. It holds no timer: the notification's
 * elapsed clock is a native chronometer anchored to the session's start time, and set progress is
 * mirrored straight from Room. It self-stops when no workout is in progress (finished/deleted).
 *
 * Unlike the cardio/rest services it takes no wake lock — there is no background timer to keep
 * firing; the chronometer ticks in the system UI on its own.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class WorkoutSessionService : Service() {

    @Inject lateinit var activeWorkoutStore: ActiveWorkoutStore
    @Inject lateinit var setLogDao: SetLogDao

    private val scope = CoroutineScope(Dispatchers.Default)
    private var collectJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    private data class Snapshot(
        val sessionId: String,
        val startedAtMs: Long?,
        val doneSets: Int,
        val totalSets: Int,
    )

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(null, "Workout in progress", "Tap to resume"))
        collectJob?.cancel()
        collectJob = scope.launch {
            activeWorkoutStore.activeSession
                .flatMapLatest { session ->
                    if (session == null) {
                        flowOf(null)
                    } else {
                        setLogDao.observeBySession(session.id).map { logs ->
                            Snapshot(
                                sessionId = session.id,
                                startedAtMs = session.startedAtMs,
                                doneSets = logs.count { it.completed },
                                totalSets = logs.size,
                            )
                        }
                    }
                }
                .collectLatest { snap ->
                    if (snap == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            snap = snap,
                            title = "Workout in progress",
                            text = "${snap.doneSets}/${snap.totalSets} sets",
                        ),
                    )
                }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(snap: Snapshot?, title: String, text: String): android.app.Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(NotificationNav.EXTRA_NAV_TARGET, NotificationNav.TARGET_WORKOUT)
                snap?.sessionId?.let { putExtra(NotificationNav.EXTRA_SESSION_ID, it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tapIntent)
        // Native, self-ticking elapsed clock anchored to the session start — no manual updates.
        val started = snap?.startedAtMs
        if (started != null) {
            builder.setWhen(started).setUsesChronometer(true).setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Workout",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing workout session"
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
        const val CHANNEL_ID = "spotter_workout"
        const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, WorkoutSessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkoutSessionService::class.java))
        }
    }
}
