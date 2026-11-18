package com.wangzan101.mika.core.model

/** 视频生成模型:提交一个异步任务,轮询到结果,返回可下载的视频。 */
interface VideoModel {
    val name: String
    val capabilities: VideoCapabilities

    /**
     * 提交并等待到可下载;每有进展调一次 [onProgress](给任务卡片看的短句)。
     * 失败抛 [ModelError](服务商 HTTP)或 [VideoModelError]。
     */
    suspend fun generate(request: VideoRequest, onProgress: suspend (String) -> Unit = {}): VideoOutput
}

/** 一个视频模型能做什么。 */
data class VideoCapabilities(
    val durations: IntRange = 4..30,
    val aspects: Set<String> = setOf("16:9", "9:16", "1:1"),
    val resolutions: Set<String> = setOf("480p", "720p", "1080p"),
    val supportsFirstFrame: Boolean = true,
    val supportsAudio: Boolean = true,
)

/** 一次生成请求;参考图由 App 侧从工作区读成字节,核心只负责发。 */
data class VideoRequest(
    val prompt: String,
    val durationSec: Int,
    val aspect: String,
    val resolution: String,
    val firstFrame: ByteArray? = null,
    val firstFrameMime: String? = null,
)

/** 生成结果:要么是内联字节,要么是一个会过期的下载地址(调用方必须立即下载)。 */
class VideoOutput(
    val bytes: ByteArray? = null,
    val url: String? = null,
    val mime: String = "video/mp4",
    val jobId: String? = null,
    val durationMs: Long? = null,
)

/** 视频模型自己的错误;HTTP/服务商错误仍用 [ModelError]。 */
sealed class VideoModelError(message: String) : Exception(message) {
    class Unsupported(message: String) : VideoModelError(message)
    class BadResponse(message: String) : VideoModelError(message)
    class Timeout(message: String) : VideoModelError(message)
    class Failed(message: String) : VideoModelError(message)
}
