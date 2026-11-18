package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.model.VideoCapabilities
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

/** 视频生成的服务商协议。存名字,只能往后加。 */
enum class VideoProtocol(val wire: String) {
    /** 火山方舟 Seedance:create/poll。 */
    ArkSeedance("ark-seedance");

    companion object {
        fun of(wire: String?): VideoProtocol? = entries.firstOrNull { it.wire == wire }
    }
}

/** 用户选的视频服务商、模型和协议;Key 仍由 [ProviderStore] 按服务商保存。 */
data class VideoChoice(val providerId: String, val model: String, val protocol: VideoProtocol)

/**
 * 视频生成设置。默认关;打开并选模型后才提供 video_generate。
 * [maxPerTask] 是每任务最多生成几个(默认 1),防止模型循环烧钱。
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

    /** 按协议给能力初值;实际以服务商/模型为准。 */
    fun capabilities(): VideoCapabilities? = when (_state.value.choice?.protocol) {
        VideoProtocol.ArkSeedance -> VideoCapabilities(
            durations = 4..30,
            aspects = setOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9"),
            resolutions = setOf("480p", "720p", "1080p", "4k"),
            supportsFirstFrame = true,
            supportsAudio = true,
        )
        null -> null
    }

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
            put("protocol", c.protocol.wire)
        }
    }

    private fun decode(obj: JsonObject): VideoSettingsState {
        fun bool(key: String, fallback: Boolean) = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
        val providerId = (obj["providerId"] as? JsonPrimitive)?.contentOrNull
        val model = (obj["model"] as? JsonPrimitive)?.contentOrNull
        val protocol = VideoProtocol.of((obj["protocol"] as? JsonPrimitive)?.contentOrNull)
        val choice = if (providerId.isNullOrBlank() || model.isNullOrBlank() || protocol == null) null
        else VideoChoice(providerId, model, protocol)
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
