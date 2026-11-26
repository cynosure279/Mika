package com.wangzan101.mika.core.model

/** 视频生成模型:提交一个异步任务,轮询到结果,返回可下载的视频。 */
interface VideoModel {
    val name: String
    val capabilities: VideoCapabilities

    /**
     * 提交并等待到可下载;任务每进一步调一次 [onProgress](App 侧换成用户语言的短句显示在任务卡片上)。
     * 失败抛 [ModelError](服务商 HTTP)或 [VideoModelError]。等待中被取消时尽量把还没开始的服务商任务也取消掉。
     */
    suspend fun generate(request: VideoRequest, onProgress: suspend (VideoJobStage) -> Unit = {}): VideoOutput
}

/** 异步视频任务走到了哪一步。 */
enum class VideoJobStage { Submitted, Queued, Running }

/**
 * 一个视频模型能做什么。由各家的 [VideoBackend.capabilities] 按服务商文档逐个模型声明;工具和设置页都只看它,不认服务商。
 * [firstFrameKeepsAspect]:带首帧时输出沿用首帧图的宽高比,请求里的宽高比不生效(适配器不发)。
 * [supportsAudio]:能生成与画面同步的声音(适配器据此要声音);不确定的模型为 false,交给服务商默认。
 */
data class VideoCapabilities(
    val durations: IntRange = 4..15,
    val aspects: Set<String> = setOf("16:9", "9:16", "1:1"),
    val resolutions: Set<String> = setOf("480p", "720p"),
    val supportsFirstFrame: Boolean = true,
    val firstFrameKeepsAspect: Boolean = false,
    val supportsAudio: Boolean = false,
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

/**
 * 视频模型自己的错误;HTTP/服务商错误仍用 [ModelError]。
 * [Failed] 是服务商明确报告任务失败或已取消(没有产出);[Timeout] 是本地等太久或服务商报任务过期;
 * [Unconfirmed] 是提交请求发出后连接断了,不知道服务商有没有建任务(可能仍在计费):调用方不能当作没提交而重试。
 */
sealed class VideoModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unsupported(message: String) : VideoModelError(message)
    class BadResponse(message: String) : VideoModelError(message)
    class Timeout(message: String) : VideoModelError(message)
    class Failed(message: String) : VideoModelError(message)
    class Unconfirmed(cause: Throwable) : VideoModelError("could not confirm whether the video job was created", cause)
}
