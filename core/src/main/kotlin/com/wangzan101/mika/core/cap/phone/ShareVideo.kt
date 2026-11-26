package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.screen.uistate.action
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 把工作区视频分享给一个 App:ACTION_SEND + EXTRA_STREAM,只打开分享页,不发出。 */
internal object ShareVideo : PhoneTool {
    override val verb = Verb.ShareVideo
    override val label = "分享视频到 App"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_share_video", Prompts.tool("phone_share_video"),
        mapOf(
            "package" to str("Exact package name of the app to share the video to, e.g. com.example.app."),
            "path" to str("Workspace video path, e.g. /workspace/out/video/clip.mp4."),
            "text" to str("Optional caption to share with the video, at most 2000 characters."),
        ),
        listOf("package", "path"))

    override fun available(ctx: ToolContext) = ctx.phone.screen.canShareVideo
    override fun offered(ctx: ToolContext) = available(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val path = a.str("path", max = 500)!!.trim()
        val prefix = SandboxPaths.WORKSPACE + "/"
        if (!path.startsWith(prefix) || path.removePrefix(prefix).split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw ArgError("path", "must be a workspace video path under $prefix")
        }
        ctx.refusedPay = null
        val r = ctx.action(ctx.phone.screen.shareVideo(p, path, a.str("text", max = 2000)))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        return r
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["package"] as? JsonPrimitive)?.content

    override fun observations(o: JsonObject, r: ToolExecution, log: com.wangzan101.mika.core.tool.EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull((o["package"] as? JsonPrimitive)?.content?.let { p ->
            Observation(ObsKind.Fact, "$name 打开了 $p 的视频分享页(还没发出)", source = PHONE)
        })
    }
}
