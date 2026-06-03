package com.spotter.data.remote

import com.spotter.BuildConfig
import com.spotter.data.model.RefreshRequest
import com.spotter.data.model.TokenResponse
import com.spotter.util.AppPreferences
import com.spotter.util.AuthEventBus
import com.spotter.util.TokenStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenRefreshAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
    private val appPreferences: AppPreferences,
    private val authEventBus: AuthEventBus,
) : okhttp3.Authenticator {

    private val json = Json { ignoreUnknownKeys = true }
    private val refreshClient = OkHttpClient()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Avoid infinite loops: if we already retried after a refresh, give up
        if (responseCount(response) >= 2) return signOut()

        val refreshToken = runBlocking { tokenStore.refreshToken.firstOrNull() }
            ?: return signOut()

        val serverUrl = runBlocking { appPreferences.serverUrl.firstOrNull() }
            ?: BuildConfig.SERVER_URL
        val refreshUrl = serverUrl.trimEnd('/') + "/auth/refresh"

        val body = json.encodeToString(RefreshRequest(refreshToken))
            .toRequestBody("application/json".toMediaType())
        val refreshRequest = Request.Builder().url(refreshUrl).post(body).build()

        val refreshResponse = try {
            refreshClient.newCall(refreshRequest).execute()
        } catch (_: Exception) {
            return signOut()
        }

        if (!refreshResponse.isSuccessful) return signOut()

        val tokenResponse = try {
            val bodyStr = refreshResponse.body?.string() ?: return signOut()
            json.decodeFromString<TokenResponse>(bodyStr)
        } catch (_: Exception) {
            return signOut()
        }

        runBlocking { tokenStore.save(tokenResponse.accessToken, tokenResponse.refreshToken) }

        return response.request.newBuilder()
            .header("Authorization", "Bearer ${tokenResponse.accessToken}")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) { count++; prior = prior.priorResponse }
        return count
    }

    private fun signOut(): Request? {
        runBlocking { tokenStore.clear() }
        authEventBus.emitLogout()
        return null
    }
}
