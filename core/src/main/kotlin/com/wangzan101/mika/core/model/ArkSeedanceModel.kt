package com.wangzan101.mika.core.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * 火山方舟 Seedance:POST /contents/generations/tasks 提交,GET .../tasks/{id} 轮询,
 * 成功后 content.video_url 是一个会过期的下载地址。结果 URL 交给调用方立即下载。
 * 等待中被取消时发 DELETE .../tasks/{id}:排队中的任务会被取消、不再计费;已在生成的服务商那边会跑完。
 */
class ArkSeedanceModel(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    override val capabilities: VideoCapabilities,
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
    private val pollMs: Long = 5_000,
    private val timeoutMs: Long = 10 * 60_000,
) : VideoModel {
    override val name: String get() = model
    private val root = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    override suspend fun generate(request: VideoRequest, onProgress: suspend (VideoJobStage) -> Unit): VideoOutput {
        check(request)
        val submit = buildJsonObject {
            put("model", model)
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", request.prompt) })
                request.firstFrame?.let { bytes ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:${request.firstFrameMime ?: "image/jpeg"};base64,${Base64.getEncoder().encodeToString(bytes)}")
                        }
                        put("role", "first_frame")
                    })
                }
            }
            put("ratio", request.aspect)
            put("duration", request.durationSec)
            put("resolution", request.resolution)
            put("generate_audio", capabilities.supportsAudio)
        }
        val req = Request.Builder().url("$root/contents/generations/tasks")
            .withHeaders(headers)
            .header("Authorization", "Bearer $apiKey")
            .post(submit.toString().toRequestBody(jsonType))
            .build()
        val submitted = HttpSupport.execute(http, req)
        if (submitted.status !in 200..299) throw ModelHttp.openAiError(submitted.status, submitted.retryAfter, submitted.body, model)
        val jobId = parseJobId(submitted.body)
        onProgress(VideoJobStage.Submitted)
        try {
            return poll(jobId, request, onProgress)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { withTimeoutOrNull(CANCEL_TIMEOUT_MS) { runCatching { cancelJob(jobId) } } }
            throw e
        }
    }

    private suspend fun poll(jobId: String, request: VideoRequest, onProgress: suspend (VideoJobStage) -> Unit): VideoOutput {
        val start = System.currentTimeMillis()
        var last: VideoJobStage? = VideoJobStage.Submitted
        while (true) {
            if (System.currentTimeMillis() - start > timeoutMs) throw VideoModelError.Timeout("video job did not finish in time")
            delay(pollMs)
            val r = HttpSupport.execute(http, task(jobId).get().build())
            if (r.status !in 200..299) throw ModelHttp.openAiError(r.status, r.retryAfter, r.body, model)
            when (val status = parseStatus(r.body)) {
                "succeeded" -> {
                    val url = parseVideoUrl(r.body) ?: throw VideoModelError.BadResponse("no content.video_url")
                    return VideoOutput(url = url, jobId = jobId, durationMs = request.durationSec * 1000L)
                }
                "failed" -> throw VideoModelError.Failed(parseError(r.body))
                "cancelled" -> throw VideoModelError.Failed("video task was cancelled")
                "expired" -> throw VideoModelError.Timeout("video task expired")
                else -> {
                    val stage = stageOf(status)
                    if (stage != null && stage != last) {
                        last = stage
                        onProgress(stage)
                    }
                }
            }
        }
    }

    private suspend fun cancelJob(jobId: String) {
        HttpSupport.execute(http, task(jobId).delete().build())
    }

    private fun task(jobId: String): Request.Builder = Request.Builder().url("$root/contents/generations/tasks/$jobId")
        .withHeaders(headers)
        .header("Authorization", "Bearer $apiKey")

    private fun check(request: VideoRequest) {
        if (request.prompt.isBlank()) throw VideoModelError.Unsupported("prompt is empty")
        if (request.durationSec !in capabilities.durations) throw VideoModelError.Unsupported("duration ${request.durationSec}s is not supported")
        if (request.aspect !in capabilities.aspects) throw VideoModelError.Unsupported("aspect ${request.aspect} is not supported")
        if (request.resolution !in capabilities.resolutions) throw VideoModelError.Unsupported("resolution ${request.resolution} is not supported")
        if (request.firstFrame != null && !capabilities.supportsFirstFrame) throw VideoModelError.Unsupported("this model does not support a first frame")
    }

    private fun parseJobId(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw VideoModelError.BadResponse("bad submit response") }
        return (root["id"] as? JsonPrimitive)?.contentOrNull ?: throw VideoModelError.BadResponse("no task id")
    }

    private fun parseStatus(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw VideoModelError.BadResponse("bad poll response") }
        return (root["status"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
    }

    private fun parseVideoUrl(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return ((root["content"] as? JsonObject)?.get("video_url") as? JsonPrimitive)?.contentOrNull
    }

    private fun parseError(body: String): String =
        (runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()?.get("error") as? JsonObject)
            ?.get("message")?.jsonPrimitive?.contentOrNull ?: "video task failed"

    companion object {
        private const val CANCEL_TIMEOUT_MS = 5_000L

        /** 方舟任务状态 → 进度;终态和不认识的状态为 null。 */
        internal fun stageOf(status: String): VideoJobStage? = when (status) {
            "queued" -> VideoJobStage.Queued
            "running" -> VideoJobStage.Running
            else -> null
        }
    }
}
