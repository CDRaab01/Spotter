package com.spotter.data.remote

import com.spotter.data.model.RefreshRequest
import com.spotter.data.model.TokenResponse
import com.spotter.util.TokenStore
import dagger.Lazy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenAuthenticatorTest {

    private fun response(
        requestUrl: String = "http://10.0.2.2:8000/calendar",
        authHeader: String? = "Bearer old-access",
        priorResponse: Response? = null,
    ): Response {
        val builder = Request.Builder().url(requestUrl)
        if (authHeader != null) builder.header("Authorization", authHeader)
        return Response.Builder()
            .request(builder.build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .apply { if (priorResponse != null) priorResponse(priorResponse) }
            .build()
    }

    @Test
    fun `refreshes token and retries request with new bearer`() {
        val store = mock<TokenStore> {
            on { refreshToken } doReturn flowOf("refresh-1")
            on { accessToken } doReturn flowOf("old-access")
        }
        val api = mock<ApiService>()
        runBlocking {
            whenever(api.refresh(RefreshRequest("refresh-1")))
                .thenReturn(TokenResponse("new-access", "refresh-2"))
        }
        val authenticator = TokenAuthenticator(store, Lazy { api })

        val retried = authenticator.authenticate(null, response())

        assertEquals("Bearer new-access", retried?.header("Authorization"))
        runBlocking { verify(store).save("new-access", "refresh-2") }
    }

    @Test
    fun `gives up and clears tokens when refresh fails`() {
        val store = mock<TokenStore> {
            on { refreshToken } doReturn flowOf("refresh-1")
            on { accessToken } doReturn flowOf("old-access")
        }
        val api = mock<ApiService>()
        runBlocking { whenever(api.refresh(any())).thenThrow(RuntimeException("401")) }
        val authenticator = TokenAuthenticator(store, Lazy { api })

        val retried = authenticator.authenticate(null, response())

        assertNull(retried)
        runBlocking { verify(store).clear() }
    }

    @Test
    fun `does not attempt refresh for the refresh endpoint itself`() {
        val store = mock<TokenStore> {
            on { refreshToken } doReturn flowOf("refresh-1")
        }
        val api = mock<ApiService>()
        val authenticator = TokenAuthenticator(store, Lazy { api })

        val retried = authenticator.authenticate(
            null,
            response(requestUrl = "http://10.0.2.2:8000/auth/refresh"),
        )

        assertNull(retried)
        runBlocking { verify(api, never()).refresh(any()) }
    }

    @Test
    fun `gives up after one retry to avoid an infinite loop`() {
        val store = mock<TokenStore> {
            on { refreshToken } doReturn flowOf("refresh-1")
        }
        val api = mock<ApiService>()
        val authenticator = TokenAuthenticator(store, Lazy { api })

        // A 401 whose request already followed a prior (retried) response.
        val withPrior = response(priorResponse = response())
        val retried = authenticator.authenticate(null, withPrior)

        assertNull(retried)
        runBlocking { verify(api, never()).refresh(any()) }
    }

    @Test
    fun `reuses already-refreshed token from a concurrent request`() {
        val store = mock<TokenStore> {
            on { refreshToken } doReturn flowOf("refresh-1")
            // Store already holds a token different from the one that failed: another request
            // refreshed it first.
            on { accessToken } doReturn flowOf("already-new-access")
        }
        val api = mock<ApiService>()
        val authenticator = TokenAuthenticator(store, Lazy { api })

        val retried = authenticator.authenticate(null, response(authHeader = "Bearer old-access"))

        assertEquals("Bearer already-new-access", retried?.header("Authorization"))
        runBlocking { verify(api, never()).refresh(any()) }
    }
}
