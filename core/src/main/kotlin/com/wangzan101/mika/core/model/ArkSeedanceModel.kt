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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.Base64

/**
 * 火山方舟 Seedance 适配器(接口见方舟文档「创建 / 查询 / 取消或删除视频生成任务」):
 * POST `/contents/generations/tasks` 提交(参数放在 body 里,强校验);GET `.../tasks/{id}` 轮询,
 * 状态 queued / running / succeeded / failed / cancelled / expired;成功后 `content.video_url` 是 24 小时内有效的下载地址,交给调用方立即下载。
 *
 * 计费安全:
 * - 提交不自动重发(关掉 OkHttp 的连接失败重试);请求发出后连接断了抛 [VideoModelError.Unconfirmed],不让上层当作没提交。
 * - 请求里带 `execution_expires_after`(文档允许的最小值 1 小时):本地放弃了的任务,排队太久会被服务商自己终止,不会隔几天才跑。
 * - 轮询有总时限 [timeoutMs];偶发的网络 / 5xx / 限流容忍 [MAX_POLL_ERRORS] 次,不因一次抖动丢下已在计费的任务。
 * - 提交之后因取消、超时或出错不再等时,尽力发一次 DELETE:文档写明只有 queued 的任务能取消(不再计费),
 *   running 的取消不了、服务商那边会跑完;终态任务的 DELETE 只删记录。DELETE 失败不影响原来的结果。
 */
class ArkSeedanceModel(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    override val capabilities: VideoCapabilities,
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
    private val pollMs: Long = 5_000,
    private val timeoutMs: Long = 30 * 60_000,
) : VideoModel {
    override val name: String get() = model
    private val root = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    /** 提交用的客户端:连接失败不由 OkHttp 悄悄重发,免得一次生成建出两个付费任务。 */
    private val submitHttp = http.newBuilder().retryOnConnectionFailure(false).build()

    override suspend fun generate(request: VideoRequest, onProgress: suspend (VideoJobStage) -> Unit): VideoOutput {
        check(request)
        val jobId = submit(request)
        val outcome = try {
            onProgress(VideoJobStage.Submitted)
            withTimeoutOrNull(timeoutMs) { poll(jobId, onProgress) }
        } catch (e: CancellationException) {
            abandon(jobId)
            throw e
        } catch (e: Exception) {
            if (!e.isTerminal()) abandon(jobId)
            throw e
        }
        if (outcome == null) {
            abandon(jobId)
            throw VideoModelError.Timeout("video job did not finish within ${timeoutMs / 60_000} minutes")
        }
        return VideoOutput(
            url = outcome.url, jobId = jobId,
            durationMs = (outcome.durationSec ?: request.durationSec).toLong() * 1000L,
        )
    }

    /** 按文档的 body 形状建提交请求;单测直接核对它。 */
    internal fun submitBody(request: VideoRequest): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("content") {
            add(buildJsonObject { put("type", "text"); put("text", request.prompt) })
            request.firstFrame?.let { bytes ->
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        val mime = (request.firstFrameMime ?: "image/jpeg").lowercase()
                        put("url", "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}")
                    }
                    put("role", "first_frame")
                })
            }
        }
        put("ratio", if (request.firstFrame != null && capabilities.firstFrameKeepsAspect) ADAPTIVE else request.aspect)
        put("duration", request.durationSec)
        put("resolution", request.resolution)
        if (capabilities.supportsAudio) put("generate_audio", true)
        put("execution_expires_after", EXPIRES_AFTER_SEC)
    }

    private suspend fun submit(request: VideoRequest): String {
        val req = Request.Builder().url("$root/contents/generations/tasks")
            .withHeaders(headers)
            .headerOrReject("Authorization", "Bearer $apiKey")
            .post(submitBody(request).toString().toRequestBody(jsonType))
            .build()
        val submitted = try {
            HttpSupport.execute(submitHttp, req)
        } catch (e: ModelError.Network) {
            // 连不上(域名、拒绝连接、没有路由)说明请求没发出去;别的断开(超时、读到一半)不知道服务商建没建任务
            if (e.cause.isNotSent()) throw e
            throw VideoModelError.Unconfirmed(e)
        }
        if (submitted.status !in 200..299) throw ModelHttp.openAiError(submitted.status, submitted.retryAfter, submitted.body, model)
        return parseJobId(submitted.body)
    }

    private class Done(val url: String, val durationSec: Int?)

    private suspend fun poll(jobId: String, onProgress: suspend (VideoJobStage) -> Unit): Done {
        var last: VideoJobStage = VideoJobStage.Submitted
        var errors = 0
        while (true) {
            delay(pollMs)
            val r = try {
                HttpSupport.execute(http, task(jobId).get().build())
            } catch (e: ModelError) {
                if (e.isTransient() && ++errors <= MAX_POLL_ERRORS) continue
                throw e
            }
            if (r.status !in 200..299) {
                val error = ModelHttp.openAiError(r.status, r.retryAfter, r.body, model)
                if (error.isTransient() && ++errors <= MAX_POLL_ERRORS) continue
                throw error
            }
            errors = 0
            val task = parseTask(r.body)
            when (val status = (task["status"] as? JsonPrimitive)?.contentOrNull) {
                "succeeded" -> {
                    val url = ((task["content"] as? JsonObject)?.get("video_url") as? JsonPrimitive)?.contentOrNull
                        ?: throw VideoModelError.BadResponse("no content.video_url")
                    return Done(url, (task["duration"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 })
                }
                "failed" -> throw VideoModelError.Failed(parseError(task))
                "cancelled" -> throw VideoModelError.Failed("video task was cancelled")
                "expired" -> throw VideoModelError.Timeout("video task expired")
                else -> {
                    val stage = status?.let(::stageOf)
                    if (stage != null && stage != last) {
                        last = stage
                        onProgress(stage)
                    }
                }
            }
        }
    }

    /** 尽力取消:不等太久、不被取消打断、失败也不抛。 */
    private suspend fun abandon(jobId: String) {
        withContext(NonCancellable) {
            withTimeoutOrNull(CANCEL_TIMEOUT_MS) { runCatching { HttpSupport.execute(http, task(jobId).delete().build()) } }
        }
    }

    private fun task(jobId: String): Request.Builder = Request.Builder().url("$root/contents/generations/tasks/$jobId")
        .withHeaders(headers)
        .headerOrReject("Authorization", "Bearer $apiKey")

    private fun check(request: VideoRequest) {
        if (request.prompt.isBlank()) throw VideoModelError.Unsupported("prompt is empty")
        if (request.durationSec !in capabilities.durations) throw VideoModelError.Unsupported("duration ${request.durationSec}s is not supported")
        if (request.aspect !in capabilities.aspects) throw VideoModelError.Unsupported("aspect ${request.aspect} is not supported")
        if (request.resolution !in capabilities.resolutions) throw VideoModelError.Unsupported("resolution ${request.resolution} is not supported")
        if (request.firstFrame != null && !capabilities.supportsFirstFrame) throw VideoModelError.Unsupported("this model does not support a first frame")
    }

    private fun parseJobId(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw VideoModelError.BadResponse("bad submit response") }
        return (root["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw VideoModelError.BadResponse("no task id")
    }

    private fun parseTask(body: String): JsonObject =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw VideoModelError.BadResponse("bad poll response") }

    private fun parseError(task: JsonObject): String {
        val error = task["error"] as? JsonObject
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
        val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
        return listOfNotNull(code, message).joinToString(": ").take(300).ifBlank { "video task failed" }
    }

    companion object {
        private const val CANCEL_TIMEOUT_MS = 5_000L
        private const val MAX_POLL_ERRORS = 5
        private const val ADAPTIVE = "adaptive"

        /** `execution_expires_after` 的取值范围是 [3600, 259200] 秒(默认 48 小时);取最小值。 */
        internal const val EXPIRES_AFTER_SEC = 3_600

        /** 方舟任务状态 → 进度;终态和不认识的状态为 null。 */
        internal fun stageOf(status: String): VideoJobStage? = when (status) {
            "queued" -> VideoJobStage.Queued
            "running" -> VideoJobStage.Running
            else -> null
        }

        private fun Throwable?.isNotSent(): Boolean =
            this is UnknownHostException || this is ConnectException || this is NoRouteToHostException

        /** 服务商已给出终态:任务不会再跑,不用取消。 */
        private fun Exception.isTerminal(): Boolean = this is VideoModelError.Failed || this is VideoModelError.Timeout

        private fun ModelError.isTransient(): Boolean =
            this is ModelError.Network || this is ModelError.RateLimited || (this is ModelError.Server && status >= 500)
    }
}
