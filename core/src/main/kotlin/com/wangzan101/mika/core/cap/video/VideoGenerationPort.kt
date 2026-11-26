package com.wangzan101.mika.core.cap.video

import com.wangzan101.mika.core.model.VideoCapabilities
import com.wangzan101.mika.core.model.VideoJobStage

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

/**
 * 生成能力的 App 侧实现:调 VideoModel、下载到工作区、抽一帧当封面;进度由端口自己报给任务卡片。
 * 端口不认服务商:哪家、什么协议由 [com.wangzan101.mika.core.model.VideoBackend] 决定。
 */
interface VideoGenerationPort {
    val modelName: String
    val capabilities: VideoCapabilities

    /**
     * 提交、等到可下载、存进工作区。失败抛 ModelError/VideoModelError/VideoError。
     * 任务每进一步也报给 [onStage]:工具据 [VideoJobStage.Submitted] 知道服务商已经收下(开始计费),之后失败不退额度。
     */
    suspend fun generate(request: VideoGenerationRequest, onStage: suspend (VideoJobStage) -> Unit = {}): VideoTake
}
