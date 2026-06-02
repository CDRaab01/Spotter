package com.spotter.data.remote

import com.spotter.util.AppPreferences
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Rewrites each outgoing request's scheme/host/port to the server URL configured at runtime
 * (Settings → Server). Retrofit's baseUrl is fixed when the singleton client is built, so this
 * interceptor is what actually lets the app be repointed at a remote server without a rebuild.
 *
 * Reads the configured URL from DataStore per request, mirroring [AuthInterceptor]. If the stored
 * value is blank or unparseable, the request passes through unchanged (Retrofit's build-time
 * baseUrl is used as-is).
 */
class HostSelectionInterceptor @Inject constructor(
    private val appPreferences: AppPreferences,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val configured = runBlocking { appPreferences.serverUrl.firstOrNull() }
            ?.toHttpUrlOrNull()
            ?: return chain.proceed(request)

        val original = request.url
        if (original.scheme == configured.scheme &&
            original.host == configured.host &&
            original.port == configured.port
        ) {
            return chain.proceed(request)
        }

        val newUrl = original.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)
            .build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
