package com.wangzan101.mika.core.cap.video

/** 工作区视频的元数据。读不出来时为 0/null。 */
class VideoInfo(
    val path: String,
    val durationMs: Long?,
    val width: Int,
    val height: Int,
    val mime: String,
    val bytes: Long,
    val hasAudio: Boolean,
)

/** 从视频里取出的一帧;JPEG 已经按模型能看的尺寸缩过。 */
class VideoFrame(val atMs: Long, val jpeg: ByteArray)

/** 存进影片库的一段视频。 */
class SavedVideo(val savedAs: String, val uri: String, val bytes: Long)

/**
 * 视频能力的 App 侧实现:读工作区视频的元数据、抽帧给模型看、把视频存进影片库。
 * 路径规范化、符号链接检查、媒体解码都由 App 侧做;核心只认 `/workspace/...`。
 */
interface VideoPort {
    suspend fun info(path: String): VideoInfo

    /** 均匀取 [count] 帧(1-12);取不到的帧跳过。 */
    suspend fun frames(path: String, count: Int): List<VideoFrame>

    suspend fun saveToMovies(path: String, name: String?): SavedVideo
}

/** 视频自己的错误;读不到/解不开/太大。 */
sealed class VideoError(message: String) : Exception(message) {
    class Unsupported(message: String) : VideoError(message)
    class TooLarge(val bytes: Long) : VideoError("video is too large")
    class BadVideo(message: String) : VideoError(message)
}
