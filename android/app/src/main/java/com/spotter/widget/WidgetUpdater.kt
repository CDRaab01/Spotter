package com.spotter.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.spotter.data.local.WidgetSnapshotStore
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutRoutineDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.util.ActiveWorkoutStore
import com.spotter.util.AppPreferences
import com.spotter.util.ProjectionDay
import com.spotter.util.SessionAnchor
import com.spotter.util.WorkoutProjection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the home-screen "today's workout" widget in step with the app. Observes the workout/session
 * state straight from Room (sessions, program, program days, and the active session's set logs) and,
 * on any change, re-derives the [WidgetData] snapshot — the same "next up" logic Home uses — persists
 * it via [WidgetSnapshotStore], and redraws any placed widget. Cheap no-op when no widget is placed.
 *
 * Being Room-derived, it self-corrects across process death and needs no manual pokes: starting,
 * progressing, finishing, or deleting a session all flow through here. Registered once from
 * [com.spotter.SpotterApp] (the [ActiveWorkoutNotifier] precedent).
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshots: WidgetSnapshotStore,
    private val sessionDao: WorkoutSessionDao,
    private val setLogDao: SetLogDao,
    private val routineDao: WorkoutRoutineDao,
    private val programDao: WorkoutProgramDao,
    private val programDayDao: ProgramDayDao,
    private val appPreferences: AppPreferences,
    private val activeWorkoutStore: ActiveWorkoutStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun register() {
        // The active session's set logs live under a session id that changes over time, so resolve
        // them through the active-session flow; then any of these signals changing triggers a redraw.
        val activeSetLogs = activeWorkoutStore.activeSession.flatMapLatest { session ->
            if (session == null) flowOf(emptyList()) else setLogDao.observeBySession(session.id)
        }
        scope.launch {
            combine(
                sessionDao.observeAll(),
                programDao.getAll(),
                programDayDao.observeAll(),
                activeSetLogs,
            ) { _, _, _, _ -> Unit }
                .collectLatest { refreshOnce() }
        }
    }

    /** Recompute and redraw once, off the observer (e.g. app start). Best-effort; never throws. */
    fun refresh() {
        scope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        val data = runCatching { buildData() }.getOrNull() ?: return
        runCatching { snapshots.save(WidgetSnapshotStore.TODAY, json.encodeToString(data)) }
        runCatching { SpotterWidget().updateAll(context) }
    }

    /** Assembles today's-workout data from Room; pure decisions live in [WidgetContent]. */
    private suspend fun buildData(): WidgetData {
        val today = LocalDate.now()
        val todayStr = today.toString()
        val sessions = sessionDao.getAll()

        // 1) A live session today wins — show its set progress.
        val active = sessions.firstOrNull { it.status == "in_progress" && it.date == todayStr }
        if (active != null) {
            val logs = setLogDao.getBySession(active.id)
            val name = active.routineId?.let { runCatching { routineDao.getById(it)?.name }.getOrNull() }
            return WidgetContent.inProgress(name, logs.count { it.completed }, logs.size)
        }

        // 2) Otherwise project the active program's soonest day (mirrors HomeViewModel.loadUpcoming).
        val program = programDao.getActive()
            ?: return WidgetContent.scheduled(today, null, null, null, isRestDay = false, hasActiveProgram = false)
        val days = programDayDao.getByProgram(program.id)
            .map { ProjectionDay(it.routineId, it.label, it.routineName) }
        if (days.isEmpty()) {
            return WidgetContent.scheduled(today, null, null, null, isRestDay = false, hasActiveProgram = false)
        }
        val cadence = appPreferences.workoutCadenceDays.first()
        val anchor = sessions
            .filter { it.status == "completed" || it.status == "in_progress" }
            .mapNotNull { s ->
                runCatching { LocalDate.parse(s.date) }.getOrNull()
                    ?.let { SessionAnchor(it, s.routineId, s.status) }
            }
            .maxByOrNull { it.date }
        val slot = WorkoutProjection.project(today, cadence, anchor, days, count = 1).firstOrNull()
            ?: return WidgetContent.scheduled(today, null, null, null, isRestDay = false, hasActiveProgram = true)
        return WidgetContent.scheduled(
            today = today,
            slotDate = slot.date,
            routineName = slot.routineName,
            label = slot.label,
            isRestDay = slot.routineId == null,
            hasActiveProgram = true,
        )
    }
}
