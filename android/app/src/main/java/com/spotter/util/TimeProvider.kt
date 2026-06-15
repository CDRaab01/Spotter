package com.spotter.util

import android.os.SystemClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable time source so every timer reads the same two clocks and tests can drive them
 * deterministically.
 *
 * - [nowMs] is wall-clock epoch millis (for elapsed values anchored to a persisted
 *   `startedAtMs`, consistent with the notification chronometer).
 * - [elapsedRealtimeMs] is the monotonic [SystemClock.elapsedRealtime] (for drift-free
 *   countdowns/count-ups that must be immune to wall-clock changes).
 */
interface TimeProvider {
    fun nowMs(): Long
    fun elapsedRealtimeMs(): Long
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TimeModule {
    @Binds
    @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider
}
