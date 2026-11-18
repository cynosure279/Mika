package com.wangzan101.mika.core.cap.video

import com.wangzan101.mika.core.tool.AgentTool

internal object VideoGroup {
    val tools: List<AgentTool> = listOf(VideoFrames, VideoInfoTool, VideoSave(ask = true), VideoGenerate)
}
