package com.wangzan101.mika.core.cap.video

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Entry
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.model.VideoJobStage
import com.wangzan101.mika.core.model.VideoModelError
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val MOVIES_CONFIRM_APP = "mika:movies"

internal object VideoFrames : AgentTool {
    override val capability: String get() = VIDEO
    override val verb = Verb.VideoFrames
    override val label = "看视频"
    override val risk = Risk.Read
    override val offScreen = true
    override val concurrency = Concurrency.ParallelSafe
    override val entry = Entry.Opens
    override val def = ToolDef("video_frames",
        Prompts.tool("video_frames"),
        mapOf(
            "path" to str("Workspace video path, e.g. /workspace/inbox/clip.mp4."),
            "count" to int("How many frames to sample evenly, 1-12 (default 6).", 1, 12),
        ), listOf("path"))

    override fun available(ctx: ToolContext) = ctx[VideoCapability.KEY]?.port != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx[VideoCapability.KEY]?.port ?: return fail("video_unavailable", "Video support is not available.")
        val path = workspacePath(a.str("path", max = 500)!!)
        val count = a.int("count", 1, 12) ?: 6
        val info = try { port.info(path) } catch (e: CancellationException) { throw e } catch (e: Throwable) { return failure(e) }
        val frames = try { port.frames(path, count) } catch (e: CancellationException) { throw e } catch (e: Throwable) { return failure(e) }
        if (frames.isEmpty()) return fail("video_bad_media", "No frame could be decoded from this video.")
        return ToolExecution(okObj {
            put("path", path)
            info.durationMs?.let { put("durationMs", it) }
            put("width", info.width)
            put("height", info.height)
            put("hasAudio", info.hasAudio)
            put("vision", ctx.config.visionEnabled)
            if (!ctx.config.visionEnabled) put("note", "The current model cannot see images; the frames were not attached.")
            putJsonArray("frames") {
                frames.forEach { f ->
                    addJsonObject { put("atMs", f.atMs); put("bytes", f.jpeg.size) }
                }
            }
        }, images = if (ctx.config.visionEnabled) frames.map { it.jpeg } else emptyList())
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["path"] as? JsonPrimitive)?.content
}

internal object VideoInfoTool : AgentTool {
    override val capability: String get() = VIDEO
    override val verb = Verb.VideoInfo
    override val label = "查看视频信息"
    override val risk = Risk.Read
    override val offScreen = true
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("video_info",
        Prompts.tool("video_info"),
        mapOf("path" to str("Workspace video path, e.g. /workspace/inbox/clip.mp4.")),
        listOf("path"))

    override fun available(ctx: ToolContext) = ctx[VideoCapability.KEY]?.port != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx[VideoCapability.KEY]?.port ?: return fail("video_unavailable", "Video support is not available.")
        val path = workspacePath(a.str("path", max = 500)!!)
        val info = try { port.info(path) } catch (e: CancellationException) { throw e } catch (e: Throwable) { return failure(e) }
        return ToolExecution(okObj {
            put("path", path)
            info.durationMs?.let { put("durationMs", it) }
            put("width", info.width)
            put("height", info.height)
            put("mime", info.mime)
            put("bytes", info.bytes)
            put("hasAudio", info.hasAudio)
        })
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["path"] as? JsonPrimitive)?.content
}

internal class VideoSave(private val ask: Boolean) : AgentTool {
    override val capability: String get() = VIDEO
    override val verb = Verb.VideoSave
    override val label = "保存视频"
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val def = ToolDef("video_save",
        Prompts.tool("video_save"),
        mapOf(
            "path" to str("Workspace video path, e.g. /workspace/out/video/clip.mp4."),
            "name" to str("Optional file name to save as, without a path."),
        ), listOf("path"))

    override fun available(ctx: ToolContext) = ctx[VideoCapability.KEY]?.port != null

    override fun approvalRisk(args: JsonObject): ApprovalRisk = if (ask) {
        ApprovalRisk(ActionRisk.Change, ask = ConfirmationRequest(
            IrrevAction.other, "", "保存视频到影片库:${nameOf(args)}", null, MOVIES_CONFIRM_APP,
        ))
    } else {
        ApprovalRisk(ActionRisk.Change)
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx[VideoCapability.KEY]?.port ?: return fail("video_unavailable", "Video support is not available.")
        val path = workspacePath(a.str("path", max = 500)!!)
        return try {
            val saved = port.saveToMovies(path, a.str("name", max = 120))
            ToolExecution(okObj {
                put("path", path)
                put("savedAs", saved.savedAs)
                put("uri", saved.uri)
                put("bytes", saved.bytes)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure(e)
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["path"] as? JsonPrimitive)?.content

    private fun nameOf(args: JsonObject): String =
        ((args["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (args["path"] as? JsonPrimitive)?.content?.substringAfterLast('/')
            ?: "video").take(120)
}

private fun workspacePath(path: String): String {
    val prefix = SandboxPaths.WORKSPACE + "/"
    val p = path.trim()
    if (!p.startsWith(prefix) || p.removePrefix(prefix).split('/').any { it.isEmpty() || it == "." || it == ".." }) {
        throw ArgError("path", "must be a workspace video path under $prefix")
    }
    return p
}

private fun failure(e: Throwable): ToolExecution = when (e) {
    is VideoError.Unsupported -> fail("video_unsupported", e.message ?: "This video cannot be read.")
    is VideoError.TooLarge -> fail("video_too_large", "The video is too large; use a shorter clip or compress it first.")
    is VideoError.BadVideo -> fail("video_bad_media", e.message ?: "The file is not a video Mika can decode.")
    else -> fail("video_failed", e.message?.take(300) ?: "Video operation failed.")
}

/** 生成一段视频;默认不提供,用户配置并打开视频模型后才给。 */
internal object VideoGenerate : AgentTool {
    override val capability: String get() = VIDEO
    override val verb = Verb.VideoGenerate
    override val label = "生成视频"
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val wideTarget = true
    override val entry = Entry.Opens
    override val def = ToolDef("video_generate",
        Prompts.tool("video_generate"),
        mapOf(
            "prompt" to str("What the video should show, 1-4000 characters."),
            "durationSec" to int("Duration in seconds; the model's capability range applies.", 1, 60),
            "aspect" to str("Aspect ratio, e.g. 16:9 or 9:16.", listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")),
            "resolution" to str("Resolution, e.g. 480p, 720p or 1080p.", listOf("480p", "720p", "1080p", "4k")),
            "first_frame_path" to str("Optional workspace image path to use as the first frame."),
            "name" to str("Optional file name to suggest, without a path."),
        ), listOf("prompt"))

    override fun available(ctx: ToolContext) = ctx[VideoCapability.KEY]?.generation != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val cap = ctx[VideoCapability.KEY] ?: return fail("video_unavailable", "Video generation is not configured.")
        val port = cap.generation ?: return fail("video_unavailable", "Video generation is not configured.")
        val prompt = a.str("prompt", max = 4000)!!.trim()
        val caps = port.capabilities
        // 没指定时用设置里的默认值;设置里的值这个模型不支持时退回它支持的,免得每次都因为默认值报错
        val duration = a.int("durationSec", 1, 60) ?: cap.defaultDuration.coerceIn(caps.durations.first, caps.durations.last)
        val aspect = a.str("aspect") ?: cap.defaultAspect.takeIf { it in caps.aspects } ?: caps.aspects.first()
        val resolution = a.str("resolution") ?: cap.defaultResolution.takeIf { it in caps.resolutions } ?: caps.resolutions.first()
        if (duration !in port.capabilities.durations) throw ArgError("durationSec", "this model supports ${port.capabilities.durations.first}-${port.capabilities.durations.last}s")
        if (aspect !in port.capabilities.aspects) throw ArgError("aspect", "this model does not support $aspect")
        if (resolution !in port.capabilities.resolutions) throw ArgError("resolution", "this model does not support $resolution")
        if (!cap.reserve()) {
            return fail("task_video_limit_reached", "This task already used its video allowance (${cap.used()}/${cap.maxPerTask}).")
        }
        val first = a.str("first_frame_path", max = 500)?.let { workspacePath(it) }
        // 额度只在确定没花钱时退:服务商还没收下任务,或明确报告任务失败(没有产出)。
        // 收下之后的超时、断网、下载失败,以及提交后不知道建没建成的,都不退,免得模型重试又建一个付费任务。
        var submitted = false
        val generated = try {
            port.generate(VideoGenerationRequest(prompt, duration, aspect, resolution, first)) { stage ->
                if (stage == VideoJobStage.Submitted) submitted = true
            }
        } catch (e: CancellationException) {
            if (!submitted) cap.refund()
            throw e
        } catch (e: Throwable) {
            if ((!submitted && e !is VideoModelError.Unconfirmed) || e is VideoModelError.Failed) cap.refund()
            return videoFailure(e)
        }
        return ToolExecution(okObj {
            put("model", port.modelName)
            put("path", generated.path)
            generated.durationMs?.let { put("durationMs", it) }
            put("width", generated.width)
            put("height", generated.height)
        }, images = if (ctx.config.visionEnabled) listOfNotNull(generated.poster) else emptyList())
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["prompt"] as? JsonPrimitive)?.content?.take(200)
}

private fun videoFailure(e: Throwable): ToolExecution = when (e) {
    is VideoModelError.Unsupported -> fail("video_unsupported", e.message ?: "This video model does not support that request.")
    is VideoModelError.BadResponse -> fail("video_bad_response", e.message ?: "The video service returned something unreadable.")
    is VideoModelError.Timeout -> fail("video_timeout", e.message ?: "The video job timed out.")
    is VideoModelError.Failed -> fail("video_generation_failed", e.message ?: "The video job failed.")
    is VideoModelError.Unconfirmed -> fail(
        "video_submit_unconfirmed",
        "The connection dropped after the request was sent, so the job may exist and be billed. Do not retry; tell the user to check the service console.",
        recoverable = false,
    )
    is com.wangzan101.mika.core.model.ModelError.Auth -> fail("video_auth_failed", "The video service rejected the API key.", recoverable = false)
    is com.wangzan101.mika.core.model.ModelError.QuotaExceeded -> fail("video_quota_exceeded", "The video service quota is used up.", recoverable = false)
    is com.wangzan101.mika.core.model.ModelError.RateLimited -> fail("video_rate_limited", "The video service is rate limiting. Wait, then try once more.")
    is com.wangzan101.mika.core.model.ModelError.ContentRefused -> fail("video_content_filtered", "The video service refused this content.")
    is com.wangzan101.mika.core.model.ModelError.Network, is com.wangzan101.mika.core.model.ModelError.Proxy ->
        fail("video_network_failed", "Could not reach the video service.")
    is com.wangzan101.mika.core.model.ModelError.Server ->
        if (e.status in 400..499) fail("video_request_rejected", e.message?.take(300) ?: "The video service rejected the request.")
        else fail("video_server_error", "The video service had a server error.")
    is com.wangzan101.mika.core.model.ModelError.ModelNotFound, is com.wangzan101.mika.core.model.ModelError.ModelNotAvailable ->
        fail("video_model_unavailable", e.message ?: "This account cannot use the video model.", recoverable = false)
    else -> fail("video_failed", e.message?.take(300) ?: "Video generation failed.")
}
