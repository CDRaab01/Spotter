package com.spotter.data.repository

import com.spotter.data.model.AcceptProgramRequest
import com.spotter.data.model.ChatRequest
import com.spotter.data.model.ChatResponse
import com.spotter.data.model.DebriefOut
import com.spotter.data.model.InsightsOut
import com.spotter.data.model.ProgramOut
import com.spotter.data.model.WeeklyRecapOut
import com.spotter.data.remote.ApiService
import javax.inject.Inject

class AiRepository @Inject constructor(private val api: ApiService) {
    suspend fun chat(req: ChatRequest): ChatResponse = api.aiChat(req)
    suspend fun acceptProgram(req: AcceptProgramRequest): ProgramOut = api.acceptProgram(req)

    /**
     * Post-workout coach debrief for a *completed* server session. Every caller treats this as
     * best-effort — the LLM is frequently unreachable, and a missing debrief is never an error.
     */
    suspend fun debriefSession(serverSessionId: String): DebriefOut =
        api.debriefSession(serverSessionId)

    /** This week's recap. Always 200; `narrative` is null when the LLM was unavailable. */
    suspend fun weeklyRecap(): WeeklyRecapOut = api.getWeeklyRecap()

    /** Proactive coaching signals (stalled lifts, PRs this week). */
    suspend fun insights(): InsightsOut = api.getInsights()
}
