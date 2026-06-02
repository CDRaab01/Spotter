package com.spotter.data.remote

import com.spotter.util.AppPreferences
import kotlinx.coroutines.flow.flowOf
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class HostSelectionInterceptorTest {

    private fun interceptorWith(url: String): HostSelectionInterceptor {
        val prefs = mock<AppPreferences>()
        whenever(prefs.serverUrl).thenReturn(flowOf(url))
        return HostSelectionInterceptor(prefs)
    }

    /** Runs the interceptor against [requestUrl] and returns the request it forwarded. */
    private fun proceedWith(interceptor: HostSelectionInterceptor, requestUrl: String): Request {
        val request = Request.Builder().url(requestUrl).build()
        val chain = mock<Interceptor.Chain>()
        whenever(chain.request()).thenReturn(request)
        whenever(chain.proceed(any())).thenAnswer {
            Response.Builder()
                .request(it.getArgument<Request>(0))
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }
        interceptor.intercept(chain)
        val captor = argumentCaptor<Request>()
        verify(chain).proceed(captor.capture())
        return captor.lastValue
    }

    @Test
    fun `rewrites scheme host and port, preserving path and query`() {
        val sent = proceedWith(
            interceptorWith("https://spotter.example.com/"),
            "http://10.0.2.2:8000/plans?x=1",
        )
        assertEquals("https", sent.url.scheme)
        assertEquals("spotter.example.com", sent.url.host)
        assertEquals(443, sent.url.port)
        assertEquals("/plans", sent.url.encodedPath)
        assertEquals("1", sent.url.queryParameter("x"))
    }

    @Test
    fun `passes through unchanged when configured url is blank`() {
        val sent = proceedWith(interceptorWith(""), "http://10.0.2.2:8000/plans")
        assertEquals("10.0.2.2", sent.url.host)
        assertEquals(8000, sent.url.port)
    }

    @Test
    fun `passes through unchanged when configured url is unparseable`() {
        val sent = proceedWith(interceptorWith("not a url"), "http://10.0.2.2:8000/plans")
        assertEquals("10.0.2.2", sent.url.host)
    }
}
