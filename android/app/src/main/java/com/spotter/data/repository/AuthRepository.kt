package com.spotter.data.repository

import com.spotter.data.model.LoginRequest
import com.spotter.data.model.RegisterRequest
import com.spotter.data.model.TokenResponse
import com.spotter.data.remote.ApiService
import com.spotter.util.TokenStore
import javax.inject.Inject

class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
) {
    suspend fun register(name: String, email: String, password: String): TokenResponse {
        val response = api.register(RegisterRequest(name, email, password))
        tokenStore.save(response.accessToken, response.refreshToken)
        return response
    }

    suspend fun login(email: String, password: String): TokenResponse {
        val response = api.login(LoginRequest(email, password))
        tokenStore.save(response.accessToken, response.refreshToken)
        return response
    }

    suspend fun logout() = tokenStore.clear()
}
