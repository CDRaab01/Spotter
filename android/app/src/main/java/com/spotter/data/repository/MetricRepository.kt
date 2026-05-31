package com.spotter.data.repository

import com.spotter.data.local.dao.BodyMetricDao
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.BodyMetricOut
import com.spotter.data.remote.ApiService
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class MetricRepository @Inject constructor(
    private val api: ApiService,
    private val dao: BodyMetricDao,
) {
    val metrics: Flow<List<BodyMetricEntity>> = dao.observeAll()

    suspend fun sync() {
        val remote = api.getWeightMetrics()
        dao.upsertAll(remote.map { it.toEntity() })
    }

    suspend fun addMetric(req: BodyMetricCreate): BodyMetricOut {
        val result = api.addWeightMetric(req)
        dao.upsert(result.toEntity())
        return result
    }

    private fun BodyMetricOut.toEntity() = BodyMetricEntity(
        id = id, userId = userId, date = date, weight = weight, bodyfat = bodyfat,
    )
}
