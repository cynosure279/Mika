package com.wangzan101.mika.core.cap.video

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.risk.IrrevAction
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
