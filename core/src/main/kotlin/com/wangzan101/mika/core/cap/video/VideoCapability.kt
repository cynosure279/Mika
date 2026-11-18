package com.wangzan101.mika.core.cap.video

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ToolContext

/** 视频能力:看视频(元数据 + 抽帧)、把视频存进影片库。没有端口时不装。 */
const val VIDEO = "video"

internal class VideoCapability(
    val port: VideoPort?,
    val generation: VideoGenerationPort? = null,
    val maxPerTask: Int = 1,
    val defaultDuration: Int = 5,
    val defaultAspect: String = "16:9",
    val defaultResolution: String = "720p",
) : Capability {
    private var used = 0

    @Synchronized fun reserve(): Boolean {
        if (used + 1 > maxPerTask) return false
        used += 1
        return true
    }

    @Synchronized fun refund() { used = (used - 1).coerceAtLeast(0) }
    @Synchronized fun used() = used

    override val id = VIDEO
    override val folds = true

    override fun status(): CapabilityStatus =
        if (port != null) CapabilityStatus.Available else CapabilityStatus.Unavailable("no video support")

    override fun tools(): List<AgentTool> = VideoGroup.tools

    override fun sections(env: PromptEnv): List<String> =
        if (env.isOpen(id) && tools().any { it.name in env.offered }) listOf(env.gate(Prompts.capability(VIDEO))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    companion object {
        val KEY = CapabilityKey<VideoCapability>(VIDEO)
    }
}
