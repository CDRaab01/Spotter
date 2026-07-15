package com.spotter.ui.workout

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** A pending rest as persisted: its wall-clock end and the duration to display the ring against. */
data class PendingRest(val endEpochMs: Long, val durationSec: Int)

/**
 * Tiny durable anchor for the between-sets rest countdown so it survives **process death** (and
 * reboot). We persist the rest's **wall-clock** end epoch — deliberately wall clock, not
 * [android.os.SystemClock.elapsedRealtime], because elapsedRealtime resets on reboot and would then
 * read a bogus far-future remaining. The *live* countdown stays drift-free on elapsedRealtime; this
 * is only the restore seed, read once when [WorkoutTimerController] is (re)constructed in a fresh
 * process. Cleared whenever a rest ends or is skipped.
 */
interface RestTimerStore {
    /** Persist a pending rest: [endEpochMs] wall-clock end + its display [durationSec]. */
    fun save(endEpochMs: Long, durationSec: Int)

    /** The pending rest, or null if none is stored. */
    fun read(): PendingRest?

    /** Forget any pending rest (a rest ended or was skipped). */
    fun clear()
}

@Singleton
class PrefsRestTimerStore @Inject constructor(
    @ApplicationContext context: Context,
) : RestTimerStore {
    private val prefs = context.getSharedPreferences("spotter_rest_timer", Context.MODE_PRIVATE)

    override fun save(endEpochMs: Long, durationSec: Int) {
        prefs.edit().putLong(KEY_END, endEpochMs).putInt(KEY_DURATION, durationSec).apply()
    }

    override fun read(): PendingRest? {
        val end = prefs.getLong(KEY_END, 0L)
        val duration = prefs.getInt(KEY_DURATION, 0)
        return if (end > 0L && duration > 0) PendingRest(end, duration) else null
    }

    override fun clear() {
        prefs.edit().remove(KEY_END).remove(KEY_DURATION).apply()
    }

    private companion object {
        const val KEY_END = "rest_end_epoch_ms"
        const val KEY_DURATION = "rest_duration_sec"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RestTimerStoreModule {
    @Binds
    @Singleton
    abstract fun bind(impl: PrefsRestTimerStore): RestTimerStore
}
