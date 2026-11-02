package com.wangzan101.mika.plugin

import android.content.Context
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/**
 * 在沙箱里跑插件脚本:工作区是插件自己的目录(沙箱里是 /workspace),脚本只从
 * `/opt/mika/plugins/<id>/scripts/…` 读;参数经临时 JSON 文件和 MIKA_PLUGIN_ARGS 传,
 * 不提供桥接令牌,不把其他插件或对话工作区暴露给它。
 */
class AndroidPluginScriptRunner(
    private val context: Context,
    private val sandbox: AndroidSandbox,
) : PluginScriptRunner {
    private val dirs by lazy { SandboxDirs(context) }

    override suspend fun run(
        pluginId: String,
        script: String,
        args: JsonObject,
        timeoutSec: Int,
        network: Boolean,
    ): ScriptResult {
        val packageDir = File(context.filesDir, "plugins/$pluginId")
        if (!PluginManifest.ID.matches(pluginId) || !packageDir.isDirectory) {
            return ScriptResult("plugin_error: plugin is not installed", exitCode = -1)
        }
        PluginScripts.verified(packageDir, script)
            ?: return ScriptResult("plugin_error: plugin script is missing or changed; reinstall the plugin", exitCode = -1)
        val interpreter = try {
            interpreterOf(script)
        } catch (e: PluginScriptException) {
            return ScriptResult("plugin_error: ${e.message}", exitCode = -1)
        }

        val workspace = dirs.pluginWorkspace(pluginId)
        val argsFile = File(workspace, ".mika/args-${UUID.randomUUID()}.json")
        return try {
            val result = sandbox.runPlugin(
                pluginId = pluginId,
                argv = listOf(interpreter, "/opt/mika/plugins/$pluginId/$script"),
                timeoutMs = timeoutSec.coerceIn(1, 600) * 1000L,
                network = network,
                extraEnv = mapOf(
                    "MIKA_PLUGIN_ID" to pluginId,
                    "MIKA_PLUGIN_ARGS" to "/workspace/.mika/${argsFile.name}",
                    "MIKA_PLUGIN_NETWORK" to if (network) "on" else "off",
                ),
                beforeRun = {
                    argsFile.parentFile?.mkdirs()
                    argsFile.writeText(args.toString())
                    PluginScriptsSync.ensure(dirs, pluginId, packageDir)
                },
            )
            val text = if (result.droppedBytes > 0) "${result.output}\n…(output truncated)" else result.output
            ScriptResult(stdout = text, exitCode = result.exitCode, timedOut = result.timedOut)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScriptResult("plugin_error: ${e.message ?: e.javaClass.simpleName}", exitCode = -1)
        } finally {
            argsFile.delete()
        }
    }

    private fun interpreterOf(script: String): String =
        when (script.substringAfterLast('.', "").lowercase()) {
            "py" -> "python3"
            "sh" -> "/bin/sh"
            "js", "mjs" -> "node"
            else -> throw PluginScriptException(PluginScriptErrors.UNSAFE, "unsupported script type: $script")
        }
}
