package com.wangzan101.mika.plugin

import kotlinx.serialization.json.JsonObject

/** 一次插件脚本运行的结果;[stdout] 是合并后的标准输出与标准错误。 */
data class ScriptResult(
    val stdout: String,
    val stderr: String = "",
    val exitCode: Int,
    val timedOut: Boolean = false,
)

/**
 * 插件工具的脚本执行入口。Android 实现用沙箱;JVM 测试用 fake。
 * 脚本路径、超时和是否联网由调用方(PluginEndpoint)按 manifest 与权限决定。
 */
interface PluginScriptRunner {
    suspend fun run(
        pluginId: String,
        script: String,
        args: JsonObject,
        timeoutSec: Int,
        network: Boolean,
    ): ScriptResult
}
