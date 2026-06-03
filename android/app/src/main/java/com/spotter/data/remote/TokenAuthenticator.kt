package com.spotter.data.remote

import com.spotter.data.model.RefreshRequest
import com.spotter.util.TokenStore
import dagger.Lazy
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transparently refreshes an expired access token when the server replies 401, then retries the
 * original request once with the new token.
 *
 * Access tokens are short-lived (the server default is 30 minutes) while the app keeps the user
 * "logged in" via the stored token. Without this, the app keeps rendering cached data but every
 * live network call (AI chat, calendar, session/workout detail, progress) fails with 401 once the
 * access token expires, until the user manually logs out and back in. The stored refresh token is
 * exchanged for a fresh pair via [ApiService.refresh].
 *
 * [ApiService] is injected lazily to break the construction cycle
 * (Authenticator -> ApiService -> Retrofit -> OkHttpClient -> Authenticator).
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenStore: TokenStore,
    private val api: Lazy<ApiService>,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Never attempt to refresh the refresh call itself, and give up after a single retry so a
        // persistently-rejected token can't spin into an infinite refresh loop.
        if (response.request.url.encodedPath.endsWith("auth/refresh")) return null
        if (responseCount(response) >= 2) return null

        val refreshToken = runBlocking { tokenStore.refreshToken.firstOrNull() } ?: return null
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        val newAccessToken = synchronized(lock) {
            // A concurrent request may have already refreshed the token. If the stored access token
            // is no longer the one that just failed, reuse it instead of burning the refresh token
            // (the server rotates it on every refresh).
            val current = runBlocking { tokenStore.accessToken.firstOrNull() }
            if (current != null && current != failedToken) {
                current
            } else {
                try {
                    val tokens = runBlocking { api.get().refresh(RefreshRequest(refreshToken)) }
                    runBlocking { tokenStore.save(tokens.accessToken, tokens.refreshToken) }
                    tokens.accessToken
                } catch (_: Exception) {
                    // Refresh token is invalid/expired -> clear credentials so the UI routes to login.
                    runBlocking { tokenStore.clear() }
                    null
                }
            }
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newAccessToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
