package com.spotter.di

import com.spotter.data.local.SpotterDatabase
import com.spotter.data.local.dao.BodyMetricDao
import com.spotter.data.local.dao.PlannedExerciseDao
import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.dao.WorkoutPlanDao
import com.spotter.data.local.dao.WorkoutSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideWorkoutPlanDao(db: SpotterDatabase): WorkoutPlanDao = db.workoutPlanDao()

    @Provides
    @Singleton
    fun provideWorkoutSessionDao(db: SpotterDatabase): WorkoutSessionDao = db.workoutSessionDao()

    @Provides
    @Singleton
    fun provideSetLogDao(db: SpotterDatabase): SetLogDao = db.setLogDao()

    @Provides
    @Singleton
    fun provideBodyMetricDao(db: SpotterDatabase): BodyMetricDao = db.bodyMetricDao()

    @Provides
    @Singleton
    fun providePlannedExerciseDao(db: SpotterDatabase): PlannedExerciseDao = db.plannedExerciseDao()
}
