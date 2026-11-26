package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 用户选的视频服务商、模型和协议;Key 仍由 [ProviderStore] 按服务商保存。
 * [backendId] 是 [com.wangzan101.mika.core.model.VideoBackend.id]:服务商地址后来改了、不再说这种协议时,
 * [VideoProviders.resolve] 认出来,不把请求发错地方。
 */
data class VideoChoice(val providerId: String, val model: String, val backendId: String)

/**
 * 视频生成设置。默认关;打开并选了能生成视频的服务商和模型后才提供 video_generate。
 * [maxPerTask] 是每任务最多生成几个(默认 1),防止模型循环烧钱。
 * 时长、宽高比、清晰度是用户偏好;模型不支持时由工具退回模型支持的值(能力见 [VideoProviders.resolve])。
 */
data class VideoSettingsState(
    val enabled: Boolean = false,
    val choice: VideoChoice? = null,
    val maxPerTask: Int = 1,
    val durationSec: Int = 5,
    val aspect: String = "16:9",
    val resolution: String = "720p",
) {
    companion object {
        const val DEFAULT_MAX_PER_TASK = 1
        const val MAX_PER_TASK_LIMIT = 4
    }
}

class VideoSettings(private val prefs: SharedPreferences) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<VideoSettingsState> = _state.asStateFlow()

    fun setEnabled(on: Boolean) = update { copy(enabled = on) }
    fun setChoice(choice: VideoChoice?) = update { copy(choice = choice) }
    fun setMaxPerTask(n: Int) = update { copy(maxPerTask = n.coerceIn(1, VideoSettingsState.MAX_PER_TASK_LIMIT)) }
    fun setDurationSec(n: Int) = update { copy(durationSec = n.coerceIn(1, 60)) }
    fun setAspect(a: String) = update { copy(aspect = a) }
    fun setResolution(r: String) = update { copy(resolution = r) }

    private fun update(block: VideoSettingsState.() -> VideoSettingsState) {
        val next = _state.value.block()
        prefs.edit().putString(KEY, encode(next).toString()).apply()
        _state.value = next
    }

    private fun read(): VideoSettingsState {
        val raw = prefs.getString(KEY, null) ?: return VideoSettingsState()
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: VideoSettingsState()
    }

    private fun encode(state: VideoSettingsState): JsonObject = buildJsonObject {
        put("enabled", state.enabled)
        put("maxPerTask", state.maxPerTask)
        put("durationSec", state.durationSec)
        put("aspect", state.aspect)
        put("resolution", state.resolution)
        state.choice?.let { c ->
            put("providerId", c.providerId)
            put("model", c.model)
            put("protocol", c.backendId)
        }
    }

    private fun decode(obj: JsonObject): VideoSettingsState {
        fun bool(key: String, fallback: Boolean) = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
        val providerId = (obj["providerId"] as? JsonPrimitive)?.contentOrNull
        val model = (obj["model"] as? JsonPrimitive)?.contentOrNull
        // 认不认得这种协议、服务商还对不对得上,交给 [VideoProviders.resolve];这里原样读回来
        val backendId = (obj["protocol"] as? JsonPrimitive)?.contentOrNull
        val choice = if (providerId.isNullOrBlank() || backendId.isNullOrBlank()) null
        else VideoChoice(providerId, model.orEmpty(), backendId)
        return VideoSettingsState(
            enabled = bool("enabled", false),
            choice = choice,
            maxPerTask = ((obj["maxPerTask"] as? JsonPrimitive)?.intOrNull ?: VideoSettingsState.DEFAULT_MAX_PER_TASK)
                .coerceIn(1, VideoSettingsState.MAX_PER_TASK_LIMIT),
            durationSec = ((obj["durationSec"] as? JsonPrimitive)?.intOrNull ?: 5).coerceIn(1, 60),
            aspect = (obj["aspect"] as? JsonPrimitive)?.contentOrNull ?: "16:9",
            resolution = (obj["resolution"] as? JsonPrimitive)?.contentOrNull ?: "720p",
        )
    }

    companion object {
        private const val KEY = "video_settings"

        fun get(context: Context) = VideoSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE),
        )
    }
}
