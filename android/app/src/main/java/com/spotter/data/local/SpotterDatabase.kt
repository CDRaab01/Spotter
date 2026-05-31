package com.spotter.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.spotter.data.local.dao.BodyMetricDao
import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.dao.WorkoutPlanDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.local.entity.SetLogEntity
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.data.local.entity.WorkoutSessionEntity

@Database(
    entities = [
        WorkoutPlanEntity::class,
        WorkoutSessionEntity::class,
        SetLogEntity::class,
        BodyMetricEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class SpotterDatabase : RoomDatabase() {
    abstract fun workoutPlanDao(): WorkoutPlanDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun setLogDao(): SetLogDao
    abstract fun bodyMetricDao(): BodyMetricDao
}
