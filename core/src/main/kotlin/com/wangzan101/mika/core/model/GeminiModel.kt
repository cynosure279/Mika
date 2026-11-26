package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Gemini generateContent 原生接口。
 * - 角色只有 user / model;相邻同角色合并。历史里的 System 消息并入 systemInstruction。
 * - Gemini 不给调用编号:合成 "gemini-<序号>-<名称>"(响应带 id 则用之);
 *   回传 functionResponse 时按 callId 到历史里的 ToolCall 找函数名。
 * - thought 部分解析为 Thinking;选了思考强度时用 `generationConfig.thinkingConfig.thinkingLevel` 控制深度。
 */
/** Google 给的占位签名:调用不是 Gemini 自己产生的(带不出原签名)时用它跳过签名校验。 */
internal const val SKIP_SIGNATURE = "skip_thought_signature_validator"

class GeminiModel(
    baseUrl: String = "https://generativelanguage.googleapis.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    /** 每个请求都带的头(见 [ClientHeaders]);Key 的头由适配器自己加。 */
    private val headers: Map<String, String> = emptyMap(),
    /** 回复最多多少 token(`generationConfig.maxOutputTokens`);null 不限,按服务端默认。 */
    private val maxOutputTokens: Int? = null,
    /** 用户选的思考档位;null 不发,`none` 尽量关掉思考,其余作为 `thinkingConfig.thinkingLevel` 发出。 */
    private val reasoningEffort: String? = null,
    private val keySupplier: (() -> String?)? = null,
    /** 密钥被上游拒绝(429/401/403)时带上 HTTP 状态码回调;接线处按状态决定冷却(429)还是标耗尽(401/403)。 */
    private val onKeyRejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:generateContent"
    private val streamEndpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:streamGenerateContent?alt=sse"
    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveKey(): String = keySupplier?.invoke()?.takeIf { it.isNotBlank() } ?: apiKey

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = requestWithKeyRotation(::resolveKey, onKeyRejected) { key ->
            HttpSupport.execute(http, request(system, messages, tools, stream = false, key = key.orEmpty()))
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var stream = GeminiStream(onText, ::parse, ::parseUsage)
        val (status, retryAfter, text) = requestWithKeyRotation(::resolveKey, onKeyRejected) { key ->
            stream = GeminiStream(onText, ::parse, ::parseUsage)
            HttpSupport.stream(http, request(system, messages, tools, stream = true, key = key.orEmpty()), stream::accept)
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean, key: String = resolveKey()): Request {
        val systemText = (listOf(system) + messages.filter { it.role == Role.System }
            .map { m -> m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text } })
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val payload = buildJsonObject {
            if (systemText.isNotBlank()) putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemText) }) }
            }
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray { tools.forEach { add(toolJson(it)) } })
                })
            })
            put("contents", buildContents(messages))
            if (maxOutputTokens != null || reasoningEffort != null) putJsonObject("generationConfig") {
                maxOutputTokens?.let { put("maxOutputTokens", it) }
                reasoningEffort?.let { effort ->
                    putJsonObject("thinkingConfig") {
                        if (effort == "none") {
                            put("thinkingBudget", 0)
                        } else {
                            put("includeThoughts", true)
                            put("thinkingLevel", effort.uppercase())
                        }
                    }
                }
            }
        }
        return Request.Builder().url(if (stream) streamEndpoint else endpoint)
            .withHeaders(headers)
            .headerOrReject("x-goog-api-key", key)
            .postInference(InferenceProtocol.Gemini, payload)
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelHttp.geminiError(status, retryAfter, body, model)

    // Gemini 不接受 additionalProperties / $schema / $id / allOf 等 OpenAPI 子集之外的键;
    // title、minLength、maxLength、pattern、default 在它的 Schema 里,原样保留
    private val rejectedKeys = setOf(
        "additionalProperties", "\$schema", "\$id", "\$defs", "definitions", "\$ref",
        "allOf", "not", "const", "examples", "\$comment",
        "\$vocabulary", "\$anchor", "\$dynamicRef", "\$dynamicAnchor",
        "dependentSchemas", "dependentRequired", "dependencies",
        "patternProperties", "propertyNames", "if", "then", "else",
        "unevaluatedProperties", "unevaluatedItems", "exclusiveMinimum", "exclusiveMaximum",
        "multipleOf", "contentEncoding", "contentMediaType",
        "readOnly", "writeOnly", "deprecated",
    )

    // 只在 schema 位置剔除并降级 OpenAPI 子集之外的结构;properties 是 名称->schema 的映射,名称本身不动
    private fun sanitize(el: JsonElement, isRoot: Boolean = false): JsonElement = when (el) {
        is JsonObject -> {
            val out = linkedMapOf<String, JsonElement>()
            val rawType = el["type"]
            val constVal = el["const"]

            el.forEach { (k, v) ->
                when {
                    k in rejectedKeys -> Unit
                    k == "properties" && v is JsonObject ->
                        out[k] = JsonObject(v.mapValues { sanitize(it.value, isRoot = false) })
                    k == "default" -> out[k] = v
                    k == "enum" && v is JsonArray -> {
                        val strings = v.filter { it is JsonPrimitive && it.isString }
                        if (strings.isNotEmpty()) out[k] = JsonArray(strings)
                    }
                    k == "type" && v is JsonArray -> {
                        val types = v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        val nonNull = types.filter { it != "null" && it.isNotBlank() }
                        out["type"] = JsonPrimitive(nonNull.firstOrNull() ?: "string")
                        if ("null" in types && "nullable" !in el) out["nullable"] = JsonPrimitive(true)
                    }
                    k == "type" && v is JsonPrimitive && v.isString && v.content == "null" -> {
                        out["type"] = JsonPrimitive("string")
                        if ("nullable" !in el) out["nullable"] = JsonPrimitive(true)
                    }
                    k == "items" && v is JsonArray -> {
                        val first = v.firstOrNull() as? JsonObject
                        out["items"] = first?.let { sanitize(it, isRoot = false) }
                            ?: buildJsonObject { put("type", "string") }
                    }
                    // oneOf 在 Gemini 里没有,按 anyOf 发(只是放宽了「恰好一个」)
                    (k == "anyOf" || k == "oneOf") && v is JsonArray -> {
                        val nonNull = v.filterNot { branch ->
                            val obj = branch as? JsonObject ?: return@filterNot false
                            val t = obj["type"]
                            (t is JsonPrimitive && t.isString && t.content == "null") ||
                                (t is JsonArray && t.all { it is JsonPrimitive && it.isString && it.content == "null" })
                        }
                        if (nonNull.size < v.size && "nullable" !in el) out["nullable"] = JsonPrimitive(true)
                        val cleanBranches = nonNull.map { sanitize(it, isRoot = false) }
                        if (cleanBranches.size == 1 && cleanBranches[0] is JsonObject) {
                            (cleanBranches[0] as JsonObject).forEach { (bk, bv) -> if (bk !in out) out[bk] = bv }
                        } else if (cleanBranches.isNotEmpty() && "anyOf" !in out) {
                            out["anyOf"] = JsonArray(cleanBranches)
                        }
                    }
                    else -> out[k] = sanitize(v, isRoot = false)
                }
            }

            if (constVal != null && constVal !is kotlinx.serialization.json.JsonNull) {
                if (constVal is JsonPrimitive && constVal.isString && "enum" !in out) {
                    out["enum"] = JsonArray(listOf(constVal))
                }
                if ("type" !in out && constVal is JsonPrimitive) {
                    val inferred = when {
                        constVal.isString -> "string"
                        constVal.content == "true" || constVal.content == "false" -> "boolean"
                        constVal.content.toLongOrNull() != null -> "integer"
                        constVal.content.toDoubleOrNull() != null -> "number"
                        else -> null
                    }
                    if (inferred != null) out["type"] = JsonPrimitive(inferred)
                }
            }

            if (rawType !is JsonArray && "type" !in out && "items" in out) {
                out["type"] = JsonPrimitive("array")
            }

            val effType = (out["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (effType == "array" && out["items"] !is JsonObject) {
                out["items"] = buildJsonObject { put("type", "string") }
            }

            if (effType != null && effType != "object") {
                out.remove("properties")
                out.remove("required")
            } else if (!isRoot && effType == "object") {
                // 没写 properties 的 object 是「任意键值」(内置 do_steps 的 args、read_script 的 args 都是),照原样发;
                // 降成 string 会让模型传字符串、工具按「必须是对象」拒掉
                val propsObj = (out["properties"] as? JsonObject)?.takeIf { it.isNotEmpty() }
                if (propsObj == null) {
                    out.remove("properties")
                    out.remove("required")
                } else if (out["required"] is JsonArray) {
                    val keptReq = (out["required"] as JsonArray).filter {
                        it is JsonPrimitive && it.isString && it.content in propsObj
                    }
                    if (keptReq.isEmpty()) out.remove("required") else out["required"] = JsonArray(keptReq)
                }
            }

            // 只有原本写了东西、却被剥得一个键不剩的节点(比如只有 $ref)兜底成 string;没写 type 的节点原样发
            if (!isRoot && out.isEmpty() && el.isNotEmpty()) {
                out["type"] = JsonPrimitive("string")
            }
            JsonObject(out)
        }
        is JsonArray -> JsonArray(el.map { sanitize(it, isRoot = false) })
        else -> el
    }

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        val schema = try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("工具 ${t.name} 的参数 schema 不是合法 JSON", e)
        }
        // 无参数工具:Gemini 拒绝没有 properties 的 object schema,省略 parameters
        val clean = sanitize(schema, isRoot = true)
        val hasProps = (clean as? JsonObject)?.get("properties")?.let { it is JsonObject && it.isNotEmpty() } == true
        if (hasProps) put("parameters", clean)
    }

    private fun buildContents(messages: List<Message>): JsonArray {
        val nameById = HashMap<String, String>()
        messages.forEach { m -> m.parts.filterIsInstance<Part.ToolCall>().forEach { nameById[it.id] = it.name } }
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (m in messages) {
            if (m.role == Role.System) continue
            val role = if (m.role == Role.Assistant) "model" else "user"
            val parts = if (role == "model") modelParts(m) else userParts(m, nameById)
            if (parts.isEmpty()) continue
            if (merged.lastOrNull()?.first == role) merged.last().second += parts else merged += role to parts.toMutableList()
        }
        return buildJsonArray {
            merged.forEach { (role, parts) ->
                add(buildJsonObject { put("role", role); put("parts", JsonArray(parts)) })
            }
        }
    }

    private fun userParts(m: Message, nameById: Map<String, String>): List<JsonObject> {
        // functionResponse 紧跟在 functionCall 之后,排在其它部分之前
        val results = m.parts.filterIsInstance<Part.ToolResult>().map { r ->
            buildJsonObject {
                putJsonObject("functionResponse") {
                    put("name", nameById[r.callId] ?: r.callId)
                    putJsonObject("response") {
                        put("content", r.content)
                        if (r.isError) put("is_error", true)
                    }
                }
            }
        }
        val rest = m.parts.mapNotNull { p ->
            when {
                p is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("text", p.text) }
                p is Part.Image && capabilities.vision -> buildJsonObject {
                    putJsonObject("inlineData") { put("mimeType", p.mime); put("data", p.pngBase64) }
                }
                p is Part.Image -> buildJsonObject { put("text", "[screenshot omitted: the current model cannot see images]") }
                else -> null
            }
        }
        return results + rest
    }

    private fun modelParts(m: Message): List<JsonObject> {
        // 一轮里只有第一个调用带签名(并行调用的其余几个本来就没有);第一个没有签名说明调用不是本适配器产生的
        // (换了模型、或回放补的调用),按 Google 的说明发跳过校验的占位签名
        val firstCall = m.parts.firstOrNull { it is Part.ToolCall }
        return m.parts.mapNotNull { p -> modelPart(p, first = p === firstCall) }
    }

    private fun modelPart(p: Part, first: Boolean): JsonObject? =
        when (p) {
            is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("text", p.text) }
            is Part.ToolCall -> buildJsonObject {
                (p.opaque ?: SKIP_SIGNATURE.takeIf { first })?.let { put("thoughtSignature", it) }
                putJsonObject("functionCall") {
                    put("name", p.name)
                    put("args", try { json.parseToJsonElement(p.argsJson).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) })
                }
            }
            else -> null
        }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val blockReason = ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
            if (blockReason != null) throw ModelError.ContentRefused()
            val cand = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ModelError.BadResponse("缺少 candidates")
            val parts = mutableListOf<Part>()
            var calls = 0
            (cand["content"] as? JsonObject)?.get("parts")?.jsonArray?.forEach { el ->
                val p = el.jsonObject
                val fc = p["functionCall"] as? JsonObject
                val t = (p["text"] as? JsonPrimitive)?.contentOrNull
                when {
                    fc != null -> {
                        val name = (fc["name"] as? JsonPrimitive)?.contentOrNull
                            ?: throw ModelError.BadResponse("functionCall 缺少 name")
                        val id = (fc["id"] as? JsonPrimitive)?.contentOrNull ?: "gemini-$calls-$name"
                        val sig = (p["thoughtSignature"] as? JsonPrimitive)?.contentOrNull
                        parts += Part.ToolCall(id, name, (fc["args"] ?: JsonObject(emptyMap())).toString(), sig)
                        calls++
                    }
                    t.isNullOrEmpty() -> {}
                    (p["thought"] as? JsonPrimitive)?.booleanOrNull == true -> parts += Part.Thinking(t)
                    else -> parts += Part.Text(t)
                }
            }
            val usable = parts.any { it !is Part.Thinking }
            val stop = when (val fr = (cand["finishReason"] as? JsonPrimitive)?.contentOrNull) {
                null, "STOP" -> if (calls > 0) StopReason.ToolUse else StopReason.EndTurn
                "MAX_TOKENS" -> StopReason.MaxTokens
                "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                    if (usable) StopReason.Other else throw ModelError.ContentRefused()
                else -> if (calls > 0 && fr.isNotEmpty()) StopReason.ToolUse else StopReason.Other
            }
            return ModelReply(parts, stop, parseUsage(root["usageMetadata"]))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull
        val inT = n("promptTokenCount") ?: return null
        // promptTokenCount 已含缓存命中部分,与统一口径一致
        val thoughts = n("thoughtsTokenCount") ?: 0
        val cached = n("cachedContentTokenCount")
        return Usage(inT, (n("candidatesTokenCount") ?: 0) + thoughts, cached ?: 0, thoughts, cacheReported = cached != null)
    }
}

private class GeminiStream(
    private val onText: (String) -> Unit,
    private val parseChunk: (String) -> ModelReply,
    private val usageOf: (JsonElement?) -> Usage?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val thinking = StringBuilder()
    private val calls = mutableListOf<Part.ToolCall>()
    private var usage: Usage? = null
    private var finishReason: String? = null

    fun accept(data: String): Boolean {
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("流式响应不是合法 JSON", e) }
        var usageInEvent = false
        usageOf(root["usageMetadata"])?.let { usage = it; usageInEvent = true }
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (candidate == null) {
            // finishReason 后可能另有一帧只带用量;收到它才算完整的终态,不能提前关流把它丢掉
            return finishReason != null && usageInEvent
        }
        (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let { finishReason = it }
        val reply = parseChunk(data)
        var chunkCalls = 0
        reply.parts.forEach { part ->
            when (part) {
                is Part.Text -> { visible.append(part.text); onText(visible.toString()) }
                is Part.Thinking -> thinking.append(part.text)
                is Part.ToolCall -> {
                    calls += if (part.id == "gemini-$chunkCalls-${part.name}") part.copy(id = "gemini-${calls.size}-${part.name}") else part
                    chunkCalls++
                }
                else -> Unit
            }
        }
        // 当前帧收到用量且已见 finishReason 时可立即收尾;否则继续等可能的 usage-only 帧或 EOF
        return finishReason != null && usageInEvent
    }

    fun finish(): ModelReply {
        // Gemini 的流没有单独的结束标记,带 finishReason 的那一帧就是收尾;没等到它是连接中途被断开
        if (finishReason == null) throw ModelError.StreamCut()
        val parts = buildList<Part> {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking.toString()))
            if (visible.isNotEmpty()) add(Part.Text(visible.toString()))
            addAll(calls)
        }
        val stop = when (finishReason) {
            "STOP" -> if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.EndTurn
            "MAX_TOKENS" -> StopReason.MaxTokens
            else -> StopReason.Other
        }
        return ModelReply(parts, stop, usage)
    }
}
