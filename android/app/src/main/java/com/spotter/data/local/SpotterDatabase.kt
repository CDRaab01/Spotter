package com.spotter.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.spotter.data.local.dao.BodyMetricDao
import com.spotter.data.local.dao.CardioSessionDao
import com.spotter.data.local.dao.ChatMessageDao
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutRoutineDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.local.entity.ChatMessageEntity
import com.spotter.data.local.entity.ProgramDayEntity
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.SetLogEntity
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.data.local.entity.WorkoutSessionEntity

@Database(
    entities = [
        WorkoutRoutineEntity::class,
        WorkoutSessionEntity::class,
        SetLogEntity::class,
        BodyMetricEntity::class,
        ChatMessageEntity::class,
        RoutineExerciseEntity::class,
        WorkoutProgramEntity::class,
        ProgramDayEntity::class,
        CardioSessionEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class SpotterDatabase : RoomDatabase() {
    abstract fun workoutRoutineDao(): WorkoutRoutineDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun setLogDao(): SetLogDao
    abstract fun bodyMetricDao(): BodyMetricDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun routineExerciseDao(): RoutineExerciseDao
    abstract fun workoutProgramDao(): WorkoutProgramDao
    abstract fun programDayDao(): ProgramDayDao
    abstract fun cardioSessionDao(): CardioSessionDao

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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rename workout_plans → workout_routines
                db.execSQL("ALTER TABLE workout_plans RENAME TO workout_routines")

                // Recreate workout_sessions to rename planId → routineId
                db.execSQL("""
                    CREATE TABLE workout_sessions_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        routineId TEXT,
                        date TEXT NOT NULL,
                        status TEXT NOT NULL,
                        durationSeconds INTEGER,
                        note TEXT,
                        exerciseNotes TEXT,
                        serverId TEXT,
                        syncPending INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL(
                    "INSERT INTO workout_sessions_new SELECT id,userId,planId,date,status," +
                    "durationSeconds,note,exerciseNotes,serverId,syncPending FROM workout_sessions"
                )
                db.execSQL("DROP TABLE workout_sessions")
                db.execSQL("ALTER TABLE workout_sessions_new RENAME TO workout_sessions")

                // Recreate planned_exercises with routineId column (SQLite can't rename columns directly)
                db.execSQL("""
                    CREATE TABLE routine_exercises (
                        routineId TEXT NOT NULL,
                        exerciseId TEXT NOT NULL,
                        exerciseName TEXT,
                        targetSets INTEGER NOT NULL,
                        targetReps INTEGER NOT NULL,
                        targetWeight REAL,
                        isBodyweight INTEGER NOT NULL,
                        `order` INTEGER NOT NULL,
                        supersetGroup INTEGER,
                        PRIMARY KEY(routineId, exerciseId)
                    )
                """.trimIndent())
                db.execSQL(
                    "INSERT INTO routine_exercises SELECT planId,exerciseId,exerciseName," +
                    "targetSets,targetReps,targetWeight,isBodyweight,`order`,supersetGroup " +
                    "FROM planned_exercises"
                )
                db.execSQL("DROP TABLE planned_exercises")

                // Recreate program_days with routineId/routineName columns
                db.execSQL("""
                    CREATE TABLE program_days_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        programId TEXT NOT NULL,
                        routineId TEXT,
                        label TEXT NOT NULL,
                        `order` INTEGER NOT NULL DEFAULT 0,
                        routineName TEXT
                    )
                """.trimIndent())
                db.execSQL(
                    "INSERT INTO program_days_new SELECT id,programId,planId,label,`order`,planName " +
                    "FROM program_days"
                )
                db.execSQL("DROP TABLE program_days")
                db.execSQL("ALTER TABLE program_days_new RENAME TO program_days")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cardio_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        serverId TEXT,
                        programId TEXT NOT NULL,
                        weekNumber INTEGER,
                        dayNumber INTEGER,
                        startedAt TEXT NOT NULL,
                        completedAt TEXT,
                        status TEXT NOT NULL,
                        totalElapsedSec INTEGER NOT NULL DEFAULT 0,
                        syncPending INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Local-only start timestamp for the in-progress banner's live elapsed clock.
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN startedAtMs INTEGER")
            }
        }
    }
}
