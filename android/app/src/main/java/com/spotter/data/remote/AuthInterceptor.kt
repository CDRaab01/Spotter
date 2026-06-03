package com.spotter.data.remote

import com.spotter.util.TokenStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenStore.accessToken.firstOrNull() }
        val request = if (token != null) {
            // Use header() (replace), not addHeader() (append): when TokenAuthenticator retries a
            // request after refreshing, this interceptor runs again, and appending would leave the
            // request with two Authorization headers.
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
