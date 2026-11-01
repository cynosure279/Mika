package com.wangzan101.mika.core.settings

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 模型改 Mika 自己设置的唯一入口(App 实现,核心只认这几个键)。
 *
 * 能改什么由 App 定:**模型与 Key、删除数据一律不给**——前者等于让模型换掉自己的脑子、花用户的钱,
 * 后者不可逆。给的都是可逆的偏好项。
 *
 * 用户没开「允许 Mika 改自己的设置」时这个工具根本不提供,所以模型碰不到它。
 */
interface SelfConfig {
    /** 现在能改哪些项、各自是什么值。 */
    fun options(): List<Option>

    /** 改一项;[value] 必须在 [Option.choices] 里。成功返回 null,否则是一句原因。 */
    fun set(key: String, value: String): String?

    /** 一项设置;[label] 是给人看的名字,[value] 是现在的值,[choices] 是可选的几个值(空的表示自由文本)。 */
    data class Option(val key: String, val label: String, val value: String, val choices: List<String> = emptyList())
}

const val MIKA_CONFIG = "mika_config"

private fun port(ctx: ToolContext): SelfConfig =
    ctx.config.selfConfig ?: error("mika_config is only offered when the user allows it")

/** 看和改 Mika 自己的设置:`action=list` 列出能改的项,`action=set` 配 key、value 改一项。 */
internal object MikaConfigTool : AgentTool {
    override val verb = Verb.MikaConfig
    override val risk = Risk.Write
    /** 不动屏幕:改的是 Mika 自己的设置,和当前任务的屏无关。 */
    override val offScreen = true

    override fun available(ctx: ToolContext) = ctx.config.selfConfig != null

    override val def = ToolDef(MIKA_CONFIG, Prompts.tool("mika_config"), mapOf(
        "action" to str("list to read the settings and their choices; set to change one.", listOf("list", "set")),
        "key" to str("For set: the setting key from list."),
        "value" to str("For set: the new value, one of that setting's choices."),
    ), listOf("action"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        return when (a.str("action")) {
        "set" -> {
            val key = a.str("key") ?: return fail("missing_key", "Give the setting key from list.")
            val value = a.str("value") ?: return fail("missing_value", "Give the new value.")
            val problem = port(ctx).set(key, value)
            if (problem != null) return fail("bad_value", problem)
            ok {
                put("key", key)
                put("value", port(ctx).options().firstOrNull { it.key == key }?.value ?: value)
            }
        }
        else -> ok {
            putJsonArray("settings") {
                port(ctx).options().forEach { o ->
                    addJsonObject {
                        put("key", o.key); put("label", o.label); put("value", o.value)
                        if (o.choices.isNotEmpty()) putJsonArray("choices") { o.choices.forEach { add(it) } }
                    }
                }
            }
        }
        }
    }
}
