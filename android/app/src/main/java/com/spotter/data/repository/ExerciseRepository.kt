package com.spotter.data.repository

import com.spotter.data.model.ExerciseOut
import com.spotter.data.remote.ApiService
import javax.inject.Inject

class ExerciseRepository @Inject constructor(private val api: ApiService) {
    suspend fun search(query: String): List<ExerciseOut> = api.searchExercises(query)
}
