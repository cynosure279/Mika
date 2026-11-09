package com.wangzan101.mika.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class PluginHttpTest {
    private val server = MockWebServer()

    /** 测试服务器在本机;把"本机"判断换掉,别的校验照旧。 */
    private fun http(isLocal: (String) -> Boolean = { false }) = PluginHttp(OkHttpClient(), isLocal = isLocal)

    private fun url(path: String) = server.url(path).toString()

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    private fun failure(block: () -> Unit): String = try {
        block()
        fail("expected HttpFailure")
        ""
    } catch (e: PluginHttp.HttpFailure) {
        e.message.orEmpty()
    }

    private fun bodyOf(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test fun getReturnsStatusHeadersAndBody() {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("X-Test", "yes").setBody("hello"))
        val response = bodyOf(http().request("""{"url":"${url("/x")}"}"""))
        assertEquals(200, response["status"]!!.jsonPrimitive.content.toInt())
        assertEquals("hello", response["body"]!!.jsonPrimitive.content)
        assertTrue(response["headers"].toString().contains("X-Test"))
    }

    @Test fun postCarriesBody() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("ok"))
        http().request("""{"method":"post","url":"${url("/p")}","headers":{"X-A":"b"},"body":"payload"}""")
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("payload", recorded.body.readUtf8())
        assertEquals("b", recorded.getHeader("X-A"))
    }

    @Test fun followsRedirects() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("done"))
        val response = bodyOf(http().request("""{"url":"${url("/start")}"}"""))
        assertEquals("done", response["body"]!!.jsonPrimitive.content)
        assertEquals(2, server.requestCount)
    }

    @Test fun rejectsRedirectToLocalAddress() {
        val guarded = http(isLocal = { it == "192.168.1.1" })
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://192.168.1.1/"))
        val message = failure { guarded.request("""{"url":"${url("/start")}"}""") }
        assertTrue(message, message.contains("local"))
    }

    @Test fun rejectsUnsupportedMethodAndSchemes() {
        assertTrue(failure { http().request("""{"method":"DELETE","url":"${url("/x")}"}""") }.contains("method"))
        assertTrue(failure { http().request("""{"url":"file:///etc/passwd"}""") }.contains("invalid url"))
        assertTrue(failure { http().request("""{"url":"http://user:pass@example.com/"}""") }.contains("credentials"))
    }

    @Test fun rejectsLocalAddressByDefault() {
        val message = failure { http(isLocal = { host: String -> host == "127.0.0.1" }).request("""{"url":"http://127.0.0.1:9/x"}""") }
        assertTrue(message, message.contains("local"))
    }

    @Test fun responseBodyIsCappedAtOneMegabyte() {
        server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
        val message = failure { http().request("""{"url":"${url("/big")}"}""") }
        assertTrue(message, message.contains("too large"))
    }

    @Test fun requestBodyAndHeadersAreBounded() {
        val big = "x".repeat(1024 * 1024 + 1)
        assertTrue(failure { http().request("""{"method":"POST","url":"${url("/x")}","body":"$big"}""") }.contains("too large"))
        val headers = (0..33).joinToString(",") { "\"h$it\":\"v\"" }
        assertTrue(failure { http().request("""{"url":"${url("/x")}","headers":{$headers}}""") }.contains("headers"))
        assertTrue(failure { http().request("""{"url":"${url("/x")}","headers":{"Content-Length":"1"}}""") }.contains("not allowed"))
    }

    @Test fun timeoutIsHonored() {
        server.enqueue(MockResponse().setBody("late").setBodyDelay(2, TimeUnit.SECONDS))
        val started = System.currentTimeMillis()
        val message = failure { http().request("""{"url":"${url("/slow")}","timeoutMs":100}""") }
        assertTrue(message, message.contains("http"))
        assertTrue("returned in ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 1_800)
    }
}
