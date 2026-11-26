package com.wangzan101.mika.core.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 火山方舟 Seedance 视频生成的声明。依据方舟官方文档(2026-11 核对):
 * - 数据面 Base URL 只有 `https://ark.cn-beijing.volces.com/api/v3`(「Base URL 及鉴权」);Coding Plan 用的是另一个地址,不能生成视频。
 * - 各模型的取值(「创建视频生成任务」的 resolution / ratio / duration):
 *   Seedance 2.5:4–30 秒,480p/720p/1080p/4k;Seedance 2.5 flash:4–30 秒,480p/720p;
 *   Seedance 2.0:4–15 秒,480p/720p/1080p/4k;Seedance 2.0 fast / mini:4–15 秒,480p/720p。
 *   宽高比都可选 16:9、4:3、1:1、3:4、9:16、21:9;Seedance 2.5 首帧生视频只支持 adaptive(沿用首帧图的比例)。
 *   generate_audio 2.x 都支持(默认 true)。
 * flash / fast / mini 的完整模型 ID 文档里没给,按名字里的词认;认不出的模型(推理接入点 ep-…、旧版本)按各模型都支持的那部分给。
 */
object ArkSeedance : VideoBackend {
    override val id = "ark-seedance"
    override val modelHint = "doubao-seedance-2-0-260128"

    private const val HOST = "ark.cn-beijing.volces.com"
    private const val PATH = "/api/v3"

    private val ASPECTS = setOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")
    private val BASIC_RESOLUTIONS = setOf("480p", "720p")
    private val ALL_RESOLUTIONS = setOf("480p", "720p", "1080p", "4k")

    /** 认不出的模型:2.0 与 2.5 都接受的取值;带首帧时发 adaptive(两代都接受),不发 generate_audio(用服务商默认)。 */
    internal val COMMON = VideoCapabilities(
        durations = 4..15, aspects = ASPECTS, resolutions = BASIC_RESOLUTIONS,
        supportsFirstFrame = true, firstFrameKeepsAspect = true, supportsAudio = false,
    )

    override fun serves(baseUrl: String): Boolean {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host == HOST && url.port == 443 && url.encodedPath.trimEnd('/') == PATH
    }

    override fun capabilities(model: String): VideoCapabilities {
        val id = model.trim().lowercase()
        val light = listOf("flash", "fast", "mini").any { it in id }
        return when {
            "seedance-2-5" in id -> VideoCapabilities(
                durations = 4..30, aspects = ASPECTS, resolutions = if (light) BASIC_RESOLUTIONS else ALL_RESOLUTIONS,
                supportsFirstFrame = true, firstFrameKeepsAspect = true, supportsAudio = true,
            )
            "seedance-2-0" in id -> VideoCapabilities(
                durations = 4..15, aspects = ASPECTS, resolutions = if (light) BASIC_RESOLUTIONS else ALL_RESOLUTIONS,
                supportsFirstFrame = true, firstFrameKeepsAspect = false, supportsAudio = true,
            )
            else -> COMMON
        }
    }

    override fun create(endpoint: VideoEndpoint): VideoModel = ArkSeedanceModel(
        endpoint.baseUrl, endpoint.apiKey, endpoint.model, capabilities(endpoint.model), endpoint.http, endpoint.headers,
    )
}
