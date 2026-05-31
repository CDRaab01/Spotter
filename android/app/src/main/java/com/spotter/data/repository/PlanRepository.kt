package com.spotter.data.repository

import com.spotter.data.local.dao.WorkoutPlanDao
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.data.model.PlanCreate
import com.spotter.data.model.PlanOut
import com.spotter.data.remote.ApiService
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class PlanRepository @Inject constructor(
    private val api: ApiService,
    private val dao: WorkoutPlanDao,
) {
    val plans: Flow<List<WorkoutPlanEntity>> = dao.observeAll()

    suspend fun sync() {
        val remote = api.getPlans()
        dao.upsertAll(remote.map { it.toEntity() })
    }

    suspend fun createPlan(req: PlanCreate): PlanOut {
        val result = api.createPlan(req)
        dao.upsert(result.toEntity())
        return result
    }

    suspend fun getPlan(id: String): PlanOut = api.getPlan(id)

    private fun PlanOut.toEntity() = WorkoutPlanEntity(
        id = id,
        userId = userId,
        name = name,
        source = source,
        createdAt = createdAt,
    )
}
