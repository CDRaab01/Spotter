package com.spotter.data.repository

import com.spotter.data.model.AcceptProgramRequest
import com.spotter.data.model.ChatRequest
import com.spotter.data.model.ChatResponse
import com.spotter.data.model.ProgramOut
import com.spotter.data.remote.ApiService
import javax.inject.Inject

class AiRepository @Inject constructor(private val api: ApiService) {
    suspend fun chat(req: ChatRequest): ChatResponse = api.aiChat(req)
    suspend fun acceptProgram(req: AcceptProgramRequest): ProgramOut = api.acceptProgram(req)
}
