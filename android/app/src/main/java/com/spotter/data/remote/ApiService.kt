package com.spotter.data.remote

import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.BodyMetricOut
import com.spotter.data.model.CalendarEntry
import com.spotter.data.model.ChatRequest
import com.spotter.data.model.ChatResponse
import com.spotter.data.model.LoginRequest
import com.spotter.data.model.PlanCreate
import com.spotter.data.model.PlanOut
import com.spotter.data.model.RefreshRequest
import com.spotter.data.model.RegisterRequest
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SetLogCreate
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.TokenResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    // Auth
    @POST("auth/register")
    suspend fun register(@Body req: RegisterRequest): TokenResponse

    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): TokenResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body req: RefreshRequest): TokenResponse

    // Plans
    @GET("plans")
    suspend fun getPlans(): List<PlanOut>

    @POST("plans")
    suspend fun createPlan(@Body req: PlanCreate): PlanOut

    @GET("plans/{id}")
    suspend fun getPlan(@Path("id") id: String): PlanOut

    // Sessions
    @POST("sessions")
    suspend fun createSession(@Body req: SessionCreate): SessionOut

    @GET("sessions/{id}")
    suspend fun getSession(@Path("id") id: String): SessionOut

    @POST("sessions/{id}/sets")
    suspend fun logSet(@Path("id") id: String, @Body req: SetLogCreate): SetLogOut

    // Metrics
    @GET("metrics/weight")
    suspend fun getWeightMetrics(): List<BodyMetricOut>

    @POST("metrics/weight")
    suspend fun addWeightMetric(@Body req: BodyMetricCreate): BodyMetricOut

    // AI
    @POST("ai/chat")
    suspend fun aiChat(@Body req: ChatRequest): ChatResponse

    // Calendar
    @GET("calendar")
    suspend fun getCalendar(
        @Query("from") from: String,
        @Query("to") to: String,
    ): List<CalendarEntry>
}
