package com.wangzan101.mika.core.chat

/**
 * 一轮对话里 Mika 生成并留在工作区的一段视频。路径是沙箱路径 `/workspace/out/video/…`；
 * [durationMs]/[width]/[height] 从成品里读，读不出来时为 null / 0；[prompt] 是当时的描述（只给界面看）；
 * [atMs] 是生成时刻。只记路径，不把视频字节存进对话文件。
 */
data class GeneratedVideo(
    val path: String,
    val durationMs: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val prompt: String? = null,
    val atMs: Long? = null,
)