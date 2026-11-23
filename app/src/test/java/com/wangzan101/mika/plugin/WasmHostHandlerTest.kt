package com.wangzan101.mika.plugin

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WasmHostHandlerTest {
    private val server = MockWebServer()

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test fun httpGoesThroughPolicyWrapper() {
        server.enqueue(MockResponse().setBody("hi"))
        server.start()
        val handler = WasmHostHandler(PluginHttp(OkHttpClient(), isLocal = { false }))
        val response = handler.http("""{"url":"${server.url("/x")}"}""")
        assertTrue(response.contains("\"body\":\"hi\""))
        assertEquals("/x", server.takeRequest().path)
    }

    @Test fun missingNetworkFailsClearly() {
        val failure = runCatching { WasmHostHandler().http("{}") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("network"))
    }

    @Test fun settingsAreExplicitlyUnsupported() {
        val failure = runCatching { WasmHostHandler().settingsSet("""{"key":"x","value":"y"}""") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("settings"))
    }
}
