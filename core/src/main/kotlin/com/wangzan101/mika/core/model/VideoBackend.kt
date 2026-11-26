package com.wangzan101.mika.core.model

import okhttp3.OkHttpClient

/**
 * 建一个视频模型要的连接信息:地址、Key、模型名、请求头都取自用户选的那家服务商。
 * Key 只放在这里交给适配器,不进日志、不进报错文字。
 */
class VideoEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val http: OkHttpClient,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 一种视频生成接口(协议)的声明:哪些服务商地址说这种协议、每个模型能做什么、怎么建适配器。
 *
 * 视频生成是服务商的一项能力,不是每家都有:设置页只列 [serves] 认得的服务商,任务里也只给认得的建端口。
 * 新接一家:写一个 [VideoModel] 适配器加一个 [VideoBackend],登记进 [VideoBackends.all];
 * 工具(`video_generate`)、App 侧的生成端口和任务都不用改。服务商须主流可信(见项目规则)。
 */
interface VideoBackend {
    /** 存进设置的名字;只能往后加,不改名、不删。 */
    val id: String

    /** 设置页模型输入框的示例(官方文档里的模型 ID)。 */
    val modelHint: String

    /** 服务商的这个 Base URL 说不说这种协议。只认官方文档写明的地址,自定义地址、中转一律不认。 */
    fun serves(baseUrl: String): Boolean

    /** [model] 能做什么,照官方文档逐个模型声明;认不出的模型(如推理接入点 ID)给各模型都支持的那部分。 */
    fun capabilities(model: String): VideoCapabilities

    fun create(endpoint: VideoEndpoint): VideoModel
}

/** 已接入的视频生成协议。 */
object VideoBackends {
    val all: List<VideoBackend> = listOf(ArkSeedance)

    fun of(id: String?): VideoBackend? = all.firstOrNull { it.id == id }

    /** 这个服务商地址能用哪种协议生成视频;都不认时为 null。 */
    fun forBaseUrl(baseUrl: String): VideoBackend? = all.firstOrNull { it.serves(baseUrl) }
}
