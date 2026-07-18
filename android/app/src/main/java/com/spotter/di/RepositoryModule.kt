package com.spotter.di

import com.spotter.data.local.SpotterDatabase
import com.spotter.data.local.dao.BodyMetricDao
import com.spotter.data.local.dao.CardioSessionDao
import com.spotter.data.local.dao.ExerciseDao
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutRoutineDao
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
    fun provideWorkoutRoutineDao(db: SpotterDatabase): WorkoutRoutineDao = db.workoutRoutineDao()

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
    fun provideRoutineExerciseDao(db: SpotterDatabase): RoutineExerciseDao = db.routineExerciseDao()

    @Provides
    @Singleton
    fun provideWorkoutProgramDao(db: SpotterDatabase): WorkoutProgramDao = db.workoutProgramDao()

    @Provides
    @Singleton
    fun provideProgramDayDao(db: SpotterDatabase): ProgramDayDao = db.programDayDao()

    @Provides
    @Singleton
    fun provideCardioSessionDao(db: SpotterDatabase): CardioSessionDao = db.cardioSessionDao()

    @Provides
    @Singleton
    fun provideExerciseDao(db: SpotterDatabase): ExerciseDao = db.exerciseDao()
}
