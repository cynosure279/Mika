package com.wangzan101.mika.core.cap.video

import com.wangzan101.mika.core.model.VideoCapabilities

/** 一段生成好的视频:工作区路径、尺寸/时长、封面(JPEG,可为空)。 */
class VideoTake(
    val path: String,
    val durationMs: Long?,
    val width: Int,
    val height: Int,
    val poster: ByteArray? = null,
)

/** 一次生成请求;firstFramePath 是工作区路径,由 App 侧读成字节后交给模型。 */
class VideoGenerationRequest(
    val prompt: String,
    val durationSec: Int,
    val aspect: String,
    val resolution: String,
    val firstFramePath: String? = null,
)

/** 生成能力的 App 侧实现:调 VideoModel、下载到工作区、抽一帧当封面。 */
interface VideoGenerationPort {
    val modelName: String
    val capabilities: VideoCapabilities

    /** 提交并等到可下载;每有进展调一次 [onProgress]。失败抛 ModelError/VideoModelError/VideoError。 */
    suspend fun generate(request: VideoGenerationRequest, onProgress: suspend (String) -> Unit): VideoTake
}
