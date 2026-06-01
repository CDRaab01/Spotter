package com.spotter.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.spotter.data.local.dao.BodyMetricDao
import com.spotter.data.local.dao.ChatMessageDao
import com.spotter.data.local.dao.PlannedExerciseDao
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.dao.WorkoutPlanDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.local.entity.ChatMessageEntity
import com.spotter.data.local.entity.PlannedExerciseEntity
import com.spotter.data.local.entity.ProgramDayEntity
import com.spotter.data.local.entity.SetLogEntity
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.local.entity.WorkoutSessionEntity

@Database(
    entities = [
        WorkoutPlanEntity::class,
        WorkoutSessionEntity::class,
        SetLogEntity::class,
        BodyMetricEntity::class,
        ChatMessageEntity::class,
        PlannedExerciseEntity::class,
        WorkoutProgramEntity::class,
        ProgramDayEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class SpotterDatabase : RoomDatabase() {
    abstract fun workoutPlanDao(): WorkoutPlanDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun setLogDao(): SetLogDao
    abstract fun bodyMetricDao(): BodyMetricDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun plannedExerciseDao(): PlannedExerciseDao
    abstract fun workoutProgramDao(): WorkoutProgramDao
    abstract fun programDayDao(): ProgramDayDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN exerciseNotes TEXT")
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN serverId TEXT")
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN syncPending INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE set_logs ADD COLUMN exerciseName TEXT")
                db.execSQL("ALTER TABLE set_logs ADD COLUMN targetSets INTEGER")
                db.execSQL("ALTER TABLE set_logs ADD COLUMN targetReps INTEGER")
                db.execSQL("ALTER TABLE set_logs ADD COLUMN targetWeight REAL")
                db.execSQL("ALTER TABLE set_logs ADD COLUMN serverId TEXT")
                db.execSQL("ALTER TABLE set_logs ADD COLUMN syncPending INTEGER NOT NULL DEFAULT 0")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS planned_exercises (
                        planId TEXT NOT NULL,
                        exerciseId TEXT NOT NULL,
                        exerciseName TEXT,
                        targetSets INTEGER NOT NULL,
                        targetReps INTEGER NOT NULL,
                        targetWeight REAL,
                        isBodyweight INTEGER NOT NULL,
                        `order` INTEGER NOT NULL,
                        PRIMARY KEY(planId, exerciseId)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE planned_exercises ADD COLUMN supersetGroup INTEGER")
                db.execSQL("ALTER TABLE set_logs ADD COLUMN supersetGroup INTEGER")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS workout_programs (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS program_days (
                        id TEXT NOT NULL PRIMARY KEY,
                        programId TEXT NOT NULL,
                        planId TEXT,
                        label TEXT NOT NULL,
                        `order` INTEGER NOT NULL DEFAULT 0,
                        planName TEXT
                    )
                """.trimIndent())
            }
        }
    }
}
