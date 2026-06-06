package com.spotter.data.repository

import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.WorkoutRoutineDao
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.data.model.RoutineCreate
import com.spotter.data.model.RoutineExerciseIn
import com.spotter.data.model.RoutineExerciseOut
import com.spotter.data.model.RoutineExercisesUpdate
import com.spotter.data.model.RoutineOut
import com.spotter.data.model.RoutineUpdate
import com.spotter.data.remote.ApiService
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class RoutineRepository @Inject constructor(
    private val api: ApiService,
    private val dao: WorkoutRoutineDao,
    private val exerciseDao: RoutineExerciseDao,
) {
    val routines: Flow<List<WorkoutRoutineEntity>> = dao.observeAll()

    suspend fun sync() {
        val remote = api.getRoutines()
        dao.upsertAll(remote.map { it.toEntity() })
        remote.forEach { routine ->
            exerciseDao.deleteByRoutineId(routine.id)
            exerciseDao.upsertAll(routine.exercises.map { it.toEntity(routine.id) })
        }
    }

    suspend fun createRoutine(req: RoutineCreate): RoutineOut {
        val result = api.createRoutine(req)
        dao.upsert(result.toEntity())
        exerciseDao.deleteByRoutineId(result.id)
        exerciseDao.upsertAll(result.exercises.map { it.toEntity(result.id) })
        return result
    }

    suspend fun getRoutine(id: String): RoutineOut = api.getRoutine(id)

    suspend fun renameRoutine(id: String, req: RoutineUpdate): RoutineOut {
        val result = api.renameRoutine(id, req)
        dao.upsert(result.toEntity())
        return result
    }

    suspend fun deleteRoutine(id: String) {
        api.deleteRoutine(id)
        dao.deleteById(id)
        exerciseDao.deleteByRoutineId(id)
    }

    suspend fun updateExercises(routineId: String, exercises: List<RoutineExerciseIn>): RoutineOut {
        val result = api.updateRoutineExercises(routineId, RoutineExercisesUpdate(exercises))
        exerciseDao.deleteByRoutineId(routineId)
        exerciseDao.upsertAll(result.exercises.map { it.toEntity(routineId) })
        return result
    }

    private fun RoutineOut.toEntity() = WorkoutRoutineEntity(
        id = id,
        userId = userId,
        name = name,
        source = source,
        createdAt = createdAt,
    )

    private fun RoutineExerciseOut.toEntity(routineId: String) = RoutineExerciseEntity(
        routineId = routineId,
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        targetSets = targetSets,
        targetReps = targetReps,
        targetWeight = targetWeight,
        isBodyweight = isBodyweight,
        order = order,
        supersetGroup = supersetGroup,
    )
}
