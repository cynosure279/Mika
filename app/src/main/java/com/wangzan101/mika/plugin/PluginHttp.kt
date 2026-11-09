package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.net.LocalHosts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 插件 Wasm 工具的 HTTP 能力,在宿主进程里执行(代理、DNS、证书都走 Mika 自己那一套)。
 * 只放行 GET/POST/HEAD、http(s),不碰本机与私网地址;请求/响应正文各 1 MB,超时最多 30 秒。
 * 重定向在这里跟着走(最多 5 跳),每一跳都重新校验地址,不会跳到本机或非 http(s)。
 * 日志只记方法、域名、状态码和字节数,不记 header 与正文。
 *
 * 请求/响应 JSON:
 * `{"method":"GET","url":"https://…","headers":{"k":"v"},"body":"…","timeoutMs":5000}`
 * → `{"status":200,"headers":{"k":"v"},"body":"…"}`
 */
class PluginHttp(
    private val client: OkHttpClient,
    private val log: (String) -> Unit = {},
    /** 地址算不算本机/私网;测试里可以换掉(默认 [LocalHosts.isLocal])。 */
    private val isLocal: (String) -> Boolean = LocalHosts::isLocal,
) {
    /** 出错抛 [HttpFailure];经 Binder 到原生侧后统一映射为 -5。 */
    class HttpFailure(message: String) : RuntimeException(message)

    private data class Call(
        val method: String,
        val url: HttpUrl,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val timeoutMs: Long,
    )

    fun request(requestJson: String): String {
        val call = try {
            parse(requestJson)
        } catch (e: HttpFailure) {
            log("request rejected: ${e.message}")
            throw e
        } catch (e: Exception) {
            val failure = HttpFailure("invalid request: ${e.message ?: e.javaClass.simpleName}")
            log("request rejected: ${failure.message}")
            throw failure
        }
        var url = call.url
        var method = call.method
        var body = call.body
        var hops = 0
        // 自己跟重定向(下面逐跳校验地址);不能让 OkHttp 静默跟到本机地址
        val http = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(call.timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        while (true) {
            val response = try {
                http.newCall(buildRequest(method, url, call.headers, body)).execute()
            } catch (e: IOException) {
                log("$method ${url.host} error=${if (e is SocketTimeoutException) "timeout" else e.javaClass.simpleName}")
                throw HttpFailure("http: ${e.message ?: e.javaClass.simpleName}")
            }
            response.use {
                val location = if (it.code in REDIRECT_CODES) it.header("Location") else null
                val next = location?.let { from -> runCatching { url.resolve(from) }.getOrNull() }
                if (next != null && hops < MAX_REDIRECTS) {
                    hops++
                    url = try {
                        checkUrl(next)
                    } catch (e: HttpFailure) {
                        log("$method ${url.host} redirect rejected: ${e.message}")
                        throw e
                    }
                    // 303 一律改 GET;301/302 的 POST 按浏览器习惯也改 GET 并丢掉正文
                    if (it.code == 303 || ((it.code == 301 || it.code == 302) && method == "POST")) {
                        method = "GET"
                        body = null
                    }
                    return@use
                }
                return try {
                    readResponse(method, url, it)
                } catch (e: IOException) {
                    log("$method ${url.host} error=${if (e is SocketTimeoutException) "timeout" else e.javaClass.simpleName}")
                    throw HttpFailure("http: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun parse(json: String): Call {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BODY) throw HttpFailure("request is too large")
        val obj = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject
            ?: throw HttpFailure("request is not a JSON object")
        val method = (obj["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        if (method !in METHODS) throw HttpFailure("method not allowed: $method")
        val raw = obj["url"]?.jsonPrimitive?.contentOrNull ?: throw HttpFailure("missing url")
        val url = checkUrl(raw.toHttpUrlOrNull() ?: throw HttpFailure("invalid url"))
        val headers = parseHeaders(obj["headers"])
        val body = obj["body"]?.jsonPrimitive?.contentOrNull?.toByteArray(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
        if (body != null && body.size > MAX_BODY) throw HttpFailure("body is too large")
        if (body != null && method != "POST") throw HttpFailure("body is only allowed for POST")
        val timeout = (obj["timeoutMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_MS)
            .coerceIn(1, MAX_TIMEOUT_MS)
        return Call(method, url, headers, if (method == "POST") body ?: ByteArray(0) else null, timeout)
    }

    private fun parseHeaders(element: kotlinx.serialization.json.JsonElement?): Map<String, String> {
        if (element == null) return emptyMap()
        val obj = element as? JsonObject ?: throw HttpFailure("headers must be an object")
        if (obj.size > MAX_HEADERS) throw HttpFailure("too many headers")
        val headers = LinkedHashMap<String, String>()
        var bytes = 0
        for ((name, value) in obj) {
            val text = value.jsonPrimitive.contentOrNull ?: throw HttpFailure("header $name is not a string")
            if (!HEADER_NAME.matches(name)) throw HttpFailure("invalid header name")
            if (name.lowercase() in FORBIDDEN_HEADERS) throw HttpFailure("header not allowed: $name")
            bytes += name.length + text.length
            if (text.toByteArray(Charsets.UTF_8).size > MAX_HEADER_BYTES || bytes > MAX_HEADERS_BYTES) {
                throw HttpFailure("header is too large")
            }
            headers[name] = text
        }
        return headers
    }

    private fun checkUrl(url: HttpUrl): HttpUrl {
        if (url.scheme != "http" && url.scheme != "https") throw HttpFailure("only http(s) urls are allowed")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw HttpFailure("credentials in url are not allowed")
        if (isLocal(url.host)) throw HttpFailure("local addresses are not allowed")
        return url
    }

    private fun buildRequest(method: String, url: HttpUrl, headers: Map<String, String>, body: ByteArray?): Request =
        try {
            Request.Builder()
                .url(url)
                .method(method, if (method == "POST") (body ?: ByteArray(0)).toRequestBody(null) else null)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        } catch (e: IllegalArgumentException) {
            throw HttpFailure("invalid request: ${e.message}")
        }

    private fun readResponse(method: String, url: HttpUrl, response: Response): String {
        val body = response.body
        val bytes = ByteArrayOutputStream()
        if (body != null) {
            body.byteStream().use { stream ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    if (bytes.size() + read > MAX_BODY) {
                        log("$method ${url.host} ${response.code} body too large")
                        throw HttpFailure("response body is too large")
                    }
                    bytes.write(chunk, 0, read)
                }
            }
        }
        val charset = body?.contentType()?.charset() ?: Charsets.UTF_8
        val text = String(bytes.toByteArray(), charset)
        log("$method ${url.host} ${response.code} bytes=${bytes.size()}")
        return buildJsonObject {
            put("status", response.code)
            put("headers", buildJsonObject {
                var count = 0
                for (name in response.headers.names()) {
                    if (count++ >= MAX_RESPONSE_HEADERS) break
                    put(name, response.headers.values(name).joinToString(", ").take(MAX_RESPONSE_HEADER_CHARS))
                }
            })
            put("body", text)
        }.toString()
    }

    private companion object {
        val METHODS = setOf("GET", "POST", "HEAD")
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "upgrade", "expect", "te", "trailer", "proxy-connection")
        val HEADER_NAME = Regex("^[A-Za-z0-9!#\$%&'*+.^_`|~-]{1,128}$")
        const val MAX_BODY = 1024 * 1024
        const val MAX_HEADERS = 32
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_HEADERS_BYTES = 16 * 1024
        const val MAX_RESPONSE_HEADERS = 64
        const val MAX_RESPONSE_HEADER_CHARS = 8 * 1024
        const val MAX_REDIRECTS = 5
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val MAX_TIMEOUT_MS = 30_000L
    }
}
