package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.VideoBackend
import com.wangzan101.mika.core.model.VideoBackends
import com.wangzan101.mika.core.model.VideoCapabilities

/** 一家能生成视频的服务商,和它说的视频协议。 */
data class VideoProvider(val provider: Provider, val backend: VideoBackend)

/**
 * 存着的视频选择和现在的服务商对不对得上。
 * 对不上([Stale])时不清掉选择(用户把地址改回来就能接着用),但任务里不建生成端口、设置页说明原因,绝不把请求发给不说这种协议的地址。
 */
sealed interface VideoSelection {
    /** 还没选服务商。 */
    data object None : VideoSelection

    /** 选了能用的服务商,但还没填模型。 */
    data class NoModel(val target: VideoProvider) : VideoSelection

    data class Ready(val target: VideoProvider, val model: String, val capabilities: VideoCapabilities) : VideoSelection

    data class Stale(val reason: Reason) : VideoSelection

    enum class Reason {
        /** 选的服务商删掉了。 */
        ProviderRemoved,

        /** 服务商还在,但地址改了,已经不说当初选的那种视频协议(或这个版本不认那种协议)。 */
        NoLongerCompatible,
    }
}

/** 按 [VideoBackends] 的声明筛服务商、核对存着的选择;纯函数,不碰存储。 */
object VideoProviders {
    fun compatible(providers: List<Provider>): List<VideoProvider> =
        providers.mapNotNull { p -> VideoBackends.forBaseUrl(p.baseUrl)?.let { VideoProvider(p, it) } }

    /** 能生成视频的服务商模板的名字(添加服务商时选它们):设置页在一家都没有时提示用户。 */
    fun presetLabels(): List<String> =
        ProviderPresets.all.filter { VideoBackends.forBaseUrl(it.baseUrl) != null }.map { it.label }.distinct()

    fun resolve(choice: VideoChoice?, providers: List<Provider>): VideoSelection {
        choice ?: return VideoSelection.None
        val provider = providers.firstOrNull { it.id == choice.providerId }
            ?: return VideoSelection.Stale(VideoSelection.Reason.ProviderRemoved)
        val backend = VideoBackends.of(choice.backendId)?.takeIf { it.serves(provider.baseUrl) }
            ?: return VideoSelection.Stale(VideoSelection.Reason.NoLongerCompatible)
        val target = VideoProvider(provider, backend)
        val model = choice.model.trim()
        if (model.isEmpty()) return VideoSelection.NoModel(target)
        return VideoSelection.Ready(target, model, backend.capabilities(model))
    }
}
