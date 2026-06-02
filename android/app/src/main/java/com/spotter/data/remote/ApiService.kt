package com.spotter.data.remote

import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.BodyMetricOut
import com.spotter.data.model.CalendarEntry
import com.spotter.data.model.ChatRequest
import com.spotter.data.model.ChatResponse
import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.ExerciseProgressPoint
import com.spotter.data.model.LoginRequest
import com.spotter.data.model.PlanCreate
import com.spotter.data.model.PlanOut
import com.spotter.data.model.PlanUpdate
import com.spotter.data.model.PlannedExercisesUpdate
import com.spotter.data.model.RefreshRequest
import com.spotter.data.model.RegisterRequest
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SessionSummary
import com.spotter.data.model.SessionUpdate
import com.spotter.data.model.SetLogCreate
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.SetLogUpdate
import com.spotter.data.model.ForgotPasswordRequest
import com.spotter.data.model.ProgramCreate
import com.spotter.data.model.ProgramDayOut
import com.spotter.data.model.ProgramDaysUpdate
import com.spotter.data.model.ProgramOut
import com.spotter.data.model.ProgramUpdate
import com.spotter.data.model.ResetPasswordRequest
import com.spotter.data.model.TokenResponse
import com.spotter.data.model.TrackedExercise
import com.spotter.data.model.UserOut
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
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

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body req: ForgotPasswordRequest)

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body req: ResetPasswordRequest)

    // Plans
    @GET("plans")
    suspend fun getPlans(): List<PlanOut>

    @POST("plans")
    suspend fun createPlan(@Body req: PlanCreate): PlanOut

    @GET("plans/{id}")
    suspend fun getPlan(@Path("id") id: String): PlanOut

    @PATCH("plans/{id}")
    suspend fun renamePlan(@Path("id") id: String, @Body req: PlanUpdate): PlanOut

    @DELETE("plans/{id}")
    suspend fun deletePlan(@Path("id") id: String)

    @PUT("plans/{id}/exercises")
    suspend fun updatePlanExercises(@Path("id") id: String, @Body req: PlannedExercisesUpdate): PlanOut

    // Sessions
    @GET("sessions")
    suspend fun listSessions(): List<SessionSummary>

    @POST("sessions")
    suspend fun createSession(@Body req: SessionCreate): SessionOut

    @GET("sessions/{id}")
    suspend fun getSession(@Path("id") id: String): SessionOut

    @PATCH("sessions/{id}")
    suspend fun updateSession(@Path("id") id: String, @Body req: SessionUpdate): SessionOut

    @POST("sessions/{id}/sets")
    suspend fun logSet(@Path("id") id: String, @Body req: SetLogCreate): SetLogOut

    @PATCH("sessions/{id}/sets/{setId}")
    suspend fun updateSet(
        @Path("id") id: String,
        @Path("setId") setId: String,
        @Body req: SetLogUpdate,
    ): SetLogOut

    @GET("sessions/{id}/prior-bests")
    suspend fun getPriorBests(@Path("id") id: String): List<ExercisePrior>

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

    // Exercises
    @GET("exercises")
    suspend fun searchExercises(@Query("search") search: String = ""): List<ExerciseOut>

    // Users
    @GET("users/me")
    suspend fun getMe(): UserOut

    /** Wipes all of the current user's server data; the account (login) is kept. */
    @POST("users/reset")
    suspend fun resetAccount()

    // Progress
    @GET("progress/exercises")
    suspend fun getTrackedExercises(): List<TrackedExercise>

    @GET("progress/exercises/{exerciseId}")
    suspend fun getExerciseProgress(@Path("exerciseId") exerciseId: String): List<ExerciseProgressPoint>

    // Programs
    @GET("programs")
    suspend fun listPrograms(): List<ProgramOut>

    @POST("programs")
    suspend fun createProgram(@Body req: ProgramCreate): ProgramOut

    @GET("programs/active/next")
    suspend fun getNextProgramDay(): ProgramDayOut?

    @GET("programs/{id}")
    suspend fun getProgram(@Path("id") id: String): ProgramOut

    @PATCH("programs/{id}")
    suspend fun updateProgram(@Path("id") id: String, @Body req: ProgramUpdate): ProgramOut

    @DELETE("programs/{id}")
    suspend fun deleteProgram(@Path("id") id: String)

    @PUT("programs/{id}/days")
    suspend fun replaceProgramDays(@Path("id") id: String, @Body req: ProgramDaysUpdate): ProgramOut
}
