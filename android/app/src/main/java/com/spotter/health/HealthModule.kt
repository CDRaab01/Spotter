package com.spotter.health

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the Health Connect-backed [HealthSync]. The repositories declare it as a constructor
 * parameter defaulting to [HealthSync.NoOp] — Dagger always passes this binding, while unit tests
 * that construct the repositories directly keep their existing (health-free) call.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HealthModule {

    @Binds
    @Singleton
    abstract fun bindHealthSync(impl: HealthConnectSync): HealthSync
}
