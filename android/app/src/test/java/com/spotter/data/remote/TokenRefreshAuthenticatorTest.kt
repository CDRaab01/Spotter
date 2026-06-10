package com.spotter.data.remote

import com.spotter.util.AppPreferences
import com.spotter.util.AuthEventBus
import com.spotter.util.TokenStore
import kotlinx.coroutines.flow.flowOf
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenRefreshAuthenticatorTest {

    private val tokenStore = mock<TokenStore>()
    private val appPreferences = mock<AppPreferences>()
    private val authEventBus = mock<AuthEventBus>()

    private fun authenticator() =
        TokenRefreshAuthenticator(tokenStore, appPreferences, authEventBus)

    private fun response(url: String, code: Int = 401): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Unauthorized")
            .build()

    @Test
    fun `401 from login does not refresh or sign out`() {
        val result = authenticator().authenticate(null, response("https://spotter.example.com/auth/login"))

        // No retry request, and crucially no sign-out side effects (which would wipe tokens +
        // server URL and bounce the user back to a fresh login screen).
        assertNull(result)
        verifyBlocking(tokenStore, never()) { clear() }
        verify(authEventBus, never()).emitLogout()
    }

    @Test
    fun `401 from a protected endpoint with no refresh token signs out`() {
        whenever(tokenStore.accessToken).thenReturn(flowOf(null))
        whenever(tokenStore.refreshToken).thenReturn(flowOf(null))

        val result = authenticator().authenticate(null, response("https://spotter.example.com/routines"))

        assertNull(result)
        verifyBlocking(tokenStore) { clear() }
        verify(authEventBus).emitLogout()
    }

    @Test
    fun `retries with the stored token when another request already refreshed`() {
        // The failed request carried "old", but the store now holds "new" (a concurrent
        // request refreshed first) — retry with it instead of refreshing again.
        whenever(tokenStore.accessToken).thenReturn(flowOf("new"))

        val failed = Response.Builder()
            .request(
                Request.Builder()
                    .url("https://spotter.example.com/routines")
                    .header("Authorization", "Bearer old")
                    .build()
            )
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

        val result = authenticator().authenticate(null, failed)

        assertEquals("Bearer new", result?.header("Authorization"))
        verifyBlocking(tokenStore, never()) { clear() }
        verify(authEventBus, never()).emitLogout()
    }

    @Test
    fun `network failure during refresh fails the request without signing out`() {
        whenever(tokenStore.accessToken).thenReturn(flowOf("expired"))
        whenever(tokenStore.refreshToken).thenReturn(flowOf("refresh-token"))
        // Nothing listens on port 1 — the refresh call throws IOException immediately.
        whenever(appPreferences.serverUrl).thenReturn(flowOf("http://127.0.0.1:1"))

        val failed = Response.Builder()
            .request(
                Request.Builder()
                    .url("https://spotter.example.com/routines")
                    .header("Authorization", "Bearer expired")
                    .build()
            )
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

        val result = authenticator().authenticate(null, failed)

        // Request fails, but the session survives — tokens kept, no logout event.
        assertNull(result)
        verifyBlocking(tokenStore, never()) { clear() }
        verify(authEventBus, never()).emitLogout()
    }
}
