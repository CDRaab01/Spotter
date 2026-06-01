package com.spotter.data.repository

import com.spotter.data.local.dao.PlannedExerciseDao
import com.spotter.data.local.dao.WorkoutPlanDao
import com.spotter.data.local.entity.PlannedExerciseEntity
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.data.model.PlanCreate
import com.spotter.data.model.PlanOut
import com.spotter.data.model.PlanUpdate
import com.spotter.data.model.PlannedExerciseIn
import com.spotter.data.model.PlannedExerciseOut
import com.spotter.data.model.PlannedExercisesUpdate
import com.spotter.data.remote.ApiService
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class PlanRepository @Inject constructor(
    private val api: ApiService,
    private val dao: WorkoutPlanDao,
    private val exerciseDao: PlannedExerciseDao,
) {
    val plans: Flow<List<WorkoutPlanEntity>> = dao.observeAll()

    suspend fun sync() {
        val remote = api.getPlans()
        dao.upsertAll(remote.map { it.toEntity() })
        remote.forEach { plan ->
            exerciseDao.deleteByPlanId(plan.id)
            exerciseDao.upsertAll(plan.exercises.map { it.toEntity(plan.id) })
        }
    }

    suspend fun createPlan(req: PlanCreate): PlanOut {
        val result = api.createPlan(req)
        dao.upsert(result.toEntity())
        exerciseDao.deleteByPlanId(result.id)
        exerciseDao.upsertAll(result.exercises.map { it.toEntity(result.id) })
        return result
    }

    suspend fun getPlan(id: String): PlanOut = api.getPlan(id)

    suspend fun renamePlan(id: String, req: PlanUpdate): PlanOut {
        val result = api.renamePlan(id, req)
        dao.upsert(result.toEntity())
        return result
    }

    suspend fun deletePlan(id: String) {
        api.deletePlan(id)
        dao.deleteById(id)
        exerciseDao.deleteByPlanId(id)
    }

    suspend fun updateExercises(planId: String, exercises: List<PlannedExerciseIn>): PlanOut {
        val result = api.updatePlanExercises(planId, PlannedExercisesUpdate(exercises))
        exerciseDao.deleteByPlanId(planId)
        exerciseDao.upsertAll(result.exercises.map { it.toEntity(planId) })
        return result
    }

    private fun PlanOut.toEntity() = WorkoutPlanEntity(
        id = id,
        userId = userId,
        name = name,
        source = source,
        createdAt = createdAt,
    )

    private fun PlannedExerciseOut.toEntity(planId: String) = PlannedExerciseEntity(
        planId = planId,
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
