package com.spotter.data.repository

import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.SetLogEntity
import com.spotter.data.local.entity.WorkoutSessionEntity
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SessionUpdate
import com.spotter.data.model.SetLogCreate
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.SetLogUpdate
import com.spotter.data.remote.ApiService
import javax.inject.Inject

class SessionRepository @Inject constructor(
    private val api: ApiService,
    private val sessionDao: WorkoutSessionDao,
    private val setLogDao: SetLogDao,
) {
    suspend fun createSession(req: SessionCreate): SessionOut {
        val result = api.createSession(req)
        sessionDao.upsert(result.toEntity())
        return result
    }

    suspend fun getSession(id: String): SessionOut {
        val result = api.getSession(id)
        sessionDao.upsert(result.toEntity())
        setLogDao.upsertAll(result.setLogs.map { it.toEntity() })
        return result
    }

    suspend fun updateSession(sessionId: String, req: SessionUpdate): SessionOut {
        val result = api.updateSession(sessionId, req)
        sessionDao.upsert(result.toEntity())
        return result
    }

    suspend fun logSet(sessionId: String, req: SetLogCreate): SetLogOut {
        val result = api.logSet(sessionId, req)
        setLogDao.upsert(result.toEntity())
        return result
    }

    suspend fun updateSet(sessionId: String, setId: String, req: SetLogUpdate): SetLogOut {
        val result = api.updateSet(sessionId, setId, req)
        setLogDao.upsert(result.toEntity())
        return result
    }

    private fun SessionOut.toEntity() = WorkoutSessionEntity(
        id = id, userId = userId, planId = planId, date = date,
        status = status, durationSeconds = durationSeconds, note = note,
    )

    private fun SetLogOut.toEntity() = SetLogEntity(
        id = id, sessionId = sessionId, exerciseId = exerciseId,
        setNumber = setNumber, reps = reps, weight = weight,
        completed = completed, completedAt = completedAt,
    )
}
