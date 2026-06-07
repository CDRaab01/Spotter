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
        whenever(tokenStore.refreshToken).thenReturn(flowOf(null))

        val result = authenticator().authenticate(null, response("https://spotter.example.com/routines"))

        assertNull(result)
        verifyBlocking(tokenStore) { clear() }
        verify(authEventBus).emitLogout()
    }
}
