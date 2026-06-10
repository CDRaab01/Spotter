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
import java.io.IOException
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

    /** Serializes refreshes so N concurrent 401s trigger one refresh, not N. */
    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // A 401 from the auth endpoints themselves (login/register/refresh/forgot/reset) means
        // bad credentials or an invalid refresh token — NOT an expired access token. Don't try to
        // refresh, and never sign out here: that would clear tokens and emit a logout event,
        // bouncing the user off a failed login back to a fresh login screen. Just surface the 401
        // so the caller (e.g. the login VM) can show the error.
        if (response.request.url.encodedPath.contains("/auth/")) return null

        // Avoid infinite loops: if we already retried after a refresh, give up
        if (responseCount(response) >= 2) return signOut()

        synchronized(refreshLock) {
            // Another request holding the lock may have refreshed while we waited. If the
            // stored token differs from the one this request failed with, retry with it
            // instead of refreshing again.
            val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            val storedToken = runBlocking { tokenStore.accessToken.firstOrNull() }
            if (storedToken != null && storedToken != failedToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $storedToken")
                    .build()
            }

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
            } catch (_: IOException) {
                // Transient network failure mid-refresh: fail this request but keep the
                // tokens — the refresh token is still valid and the next request retries.
                return null
            }

            // Only an explicit auth rejection invalidates the refresh token; a 5xx or
            // other server hiccup shouldn't wipe the session.
            if (refreshResponse.code == 401 || refreshResponse.code == 403) return signOut()
            if (!refreshResponse.isSuccessful) {
                refreshResponse.close()
                return null
            }

            val tokenResponse = try {
                val bodyStr = refreshResponse.body?.string() ?: return null
                json.decodeFromString<TokenResponse>(bodyStr)
            } catch (_: Exception) {
                return null
            }

            runBlocking { tokenStore.save(tokenResponse.accessToken, tokenResponse.refreshToken) }

            return response.request.newBuilder()
                .header("Authorization", "Bearer ${tokenResponse.accessToken}")
                .build()
        }
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
