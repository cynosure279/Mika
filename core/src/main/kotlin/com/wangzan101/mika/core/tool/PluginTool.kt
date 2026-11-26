package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * 一个插件工具的描述:app 侧从 manifest 读出后交给 core。[name] 是拼好的全名(`plugin_<id去点>_<工具名>`)。
 * [scriptDir]/[scriptPath] 是脚本在插件目录里的位置;[inputSchema] 是插件给的工具参数 schema。
 */
data class PluginToolSpec(
    val name: String,
    val description: String,
    /** 脚本工具在包内的路径;Wasm 工具为空。 */
    val scriptPath: String = "",
    val scriptDir: File,
    val inputSchema: JsonObject,
    val timeoutSec: Int,
    val risk: Risk,
    /** Wasm 模块在包内的路径(`tools/…wasm`);为空表示脚本工具。 */
    val wasmPath: String? = null,
    /** 工具可用的宿主能力声明(如 `network`);Wasm 引擎按它放行。 */
    val permissions: List<String> = emptyList(),
    /** 所属插件 id;Wasm 宿主上下文与模块复核用。 */
    val pluginId: String = "",
)

/** 跑一条插件脚本(在 App 进程内跑 sh)。[env] 是额外要加进进程环境的环境变量。 */
interface ScriptRunner {
    suspend fun run(script: File, argsFile: File, env: Map<String, String>, timeoutMs: Long): ScriptResult

    /** 把 [stdin] 写进脚本的 stdin、读 stdout 当结果(提示词钩子用);默认不支持。 */
    suspend fun runStdin(script: File, stdin: String, env: Map<String, String>, timeoutMs: Long): ScriptResult =
        ScriptResult(1, "", "stdin execution not supported", timedOut = false)
}

/** 脚本跑完的样子:退出码、输出、有没有超时被掐掉。 */
data class ScriptResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

/** 一次 Wasm 工具调用:模块位置、插件声明的权限与超时;实现负责模块哈希复核与资源限额。 */
data class WasmInvocation(
    val pluginId: String,
    val modulePath: String,
    val moduleFile: File,
    val permissions: List<String>,
    val timeoutMs: Long,
)

/** Wasm 引擎入口;App 侧由独立进程的引擎实现,测试里用假实现。 */
fun interface WasmRunner {
    suspend fun call(invocation: WasmInvocation, argsJson: String): WasmCallResult
}

/** Wasm 工具结果:成功时 [output] 是一段 JSON,[error] 为空;失败时二者相反。 */
data class WasmCallResult(val output: String = "", val error: String? = null)

/**
 * 插件工具:脚本工具把参数 JSON 写进临时文件交给 [scriptRunner];Wasm 工具把参数直接交给 [wasmRunner]。
 * 脚本不退出、模块缺失或被换过、输出非 JSON、或返回 `ok:false` 时都变成干净的失败,绝不把任务搞挂。
 */
internal class PluginTool(
    private val spec: PluginToolSpec,
    private val scriptRunner: ScriptRunner?,
    private val wasmRunner: WasmRunner?,
    private val argsDir: File = File(System.getProperty("java.io.tmpdir"), "mika-plugin-args"),
) : AgentTool {
    override val def: ToolDef = spec.let { s ->
        val normalized = ToolSchema.normalizeObject(s.inputSchema)
        ToolDef(s.name, s.description, normalized.props, normalized.required)
    }
    override val verb = Verb.PluginScript
    override val risk: Risk get() = spec.risk
    override val offScreen: Boolean get() = true

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val body: String = if (spec.wasmPath != null) {
            val runner = wasmRunner ?: return fail("wasm_unavailable", "this build cannot run Wasm plugins")
            val path = spec.wasmPath
            if (!path.endsWith(".wasm") || path.startsWith("/") || ".." in path.split('/')) {
                return fail("invalid_tool_wasm", path)
            }
            val result = runner.call(
                WasmInvocation(
                    pluginId = spec.pluginId,
                    modulePath = path,
                    moduleFile = File(spec.scriptDir, path),
                    permissions = spec.permissions,
                    timeoutMs = spec.timeoutSec * 1000L,
                ),
                a.o.toString(),
            )
            result.error?.let { return fail("wasm_error", it.take(2000)) }
            result.output
        } else {
            val runner = scriptRunner ?: return fail("script_unavailable", "this build cannot run plugin scripts")
            argsDir.mkdirs()
            val argsFile = File(argsDir, "args-${System.nanoTime()}.json")
            argsFile.writeText(a.o.toString())
            val result = try {
                runner.run(File(spec.scriptDir, spec.scriptPath), argsFile, emptyMap(), spec.timeoutSec * 1000L)
            } finally {
                argsFile.delete()
            }
            if (result.timedOut) return fail("timed_out", "script did not finish within ${spec.timeoutSec}s")
            if (result.exitCode != 0) return fail("script_failed", result.stderr.ifBlank { result.stdout }.take(2000))
            result.stdout
        }
        val parsed = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return fail("bad_output", "plugin returned non-JSON output: ${body.take(200)}")
        if (parsed["ok"] == JsonPrimitive(false)) {
            val error = (parsed["error"] as? JsonPrimitive)?.contentOrNull ?: "plugin_error"
            val message = (parsed["message"] as? JsonPrimitive)?.contentOrNull ?: error
            return fail(error, message)
        }
        return ToolExecution(parsed)
    }
}

/** 插件作为一个能力:无屏、无守卫、无节奏,只把自己的工具放进任务。 */
internal class PluginCapability(private val tools: List<AgentTool>) : Capability {
    override val id = "plugin"
    override fun status() = CapabilityStatus.Available
    override fun tools() = tools
    override fun sections(env: PromptEnv) = emptyList<String>()
}
