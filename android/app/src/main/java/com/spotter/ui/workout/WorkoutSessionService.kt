package com.spotter.ui.workout

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.spotter.data.local.dao.SetLogDao
import com.spotter.util.ActiveWorkoutStore
import com.spotter.util.ForegroundServiceSupport
import com.spotter.util.NotificationNav
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single foreground notification for a live strength session. It merges what used to be two
 * notifications: the "workout in progress" elapsed clock (a native chronometer anchored to the
 * session's `startedAtMs`, set progress mirrored from Room) and the between-sets rest countdown
 * (read from [WorkoutTimerController], the source of truth). When resting the content line shows the
 * countdown; otherwise it shows set progress — the elapsed chronometer is always present.
 *
 * It holds no timer and no wake-lock: the elapsed chronometer self-ticks in the system UI, and the
 * rest wake-lock + drift-free countdown + end cue all live in [WorkoutTimerController]. It self-stops
 * when no workout is in progress (finished/deleted). Started by [com.spotter.util.ActiveWorkoutNotifier]
 * on the in-progress edge — already running before any rest can begin, so it protects the process
 * for the whole rest.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class WorkoutSessionService : Service() {

    @Inject lateinit var activeWorkoutStore: ActiveWorkoutStore
    @Inject lateinit var setLogDao: SetLogDao
    @Inject lateinit var workoutTimer: WorkoutTimerController

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
        ForegroundServiceSupport.ensureChannel(this, CHANNEL_ID, "Workout", "Ongoing workout session")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ForegroundServiceSupport.startForegroundSpecialUse(
            this, NOTIFICATION_ID, buildNotification(null, "Workout in progress", "Tap to resume"),
        )
        collectJob?.cancel()
        collectJob = scope.launch {
            val snapshots = activeWorkoutStore.activeSession.flatMapLatest { session ->
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
            // Re-notify whenever set progress OR the rest countdown changes. The rest state only
            // changes once per whole second (the controller dedups), so this stays well under the
            // notification rate limit; the chronometer self-ticks regardless.
            combine(snapshots, workoutTimer.restState) { snap, rest -> snap to rest }
                .collectLatest { (snap, rest) ->
                    if (snap == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    val text = if (rest != null) {
                        "Resting · ${format(rest.remainingSec)}"
                    } else {
                        "${snap.doneSets}/${snap.totalSets} sets"
                    }
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildNotification(snap = snap, title = "Workout in progress", text = text),
                    )
                }
        }
        return START_NOT_STICKY
    }

    private fun format(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun buildNotification(snap: Snapshot?, title: String, text: String): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                ForegroundServiceSupport.tapIntent(this, NotificationNav.TARGET_WORKOUT, snap?.sessionId),
            )
        // Native, self-ticking elapsed clock anchored to the session start — no manual updates.
        val started = snap?.startedAtMs
        if (started != null) {
            builder.setWhen(started).setUsesChronometer(true).setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
