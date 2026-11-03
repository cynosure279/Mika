package com.wangzan101.mika.core.permission

import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.ToolInfo

/** 一个权限的稳定名字。不跟着工具改名走;发布后不再改。 */
@JvmInline
value class PermissionId(val value: String)

/** 权限层自己的风险等级;具体动作的风险仍由 [com.wangzan101.mika.core.action.ClassifiedRisk] 判。 */
enum class PermissionRisk { Read, Change, External, Irreversible }

/** 授权对象能管到多细。v1 只实现前四类,后两类留接口给 v2。 */
enum class ResourceKind { Global, AppPackage, Directory, McpServer, Plugin, RemoteEnv }

/** 一条授权的结论。 */
enum class Access { Allow, Ask, Deny }

/** 授权在哪个范围有效。 */
enum class GrantScope { Once, Run, Conversation, Global }

/** 授权从哪来:用户点的、默认值、从旧配置迁移的、插件清单声明的。 */
enum class GrantSource { User, Default, MigratedLegacy, PluginManifest, Policy }

/** 权限作用的资源;v1 只用到 Global / AppPackage / McpServer / Plugin。 */
sealed interface PermissionResource {
    data object Global : PermissionResource
    data class AppPackage(val packageName: String) : PermissionResource
    data class Directory(val path: String) : PermissionResource
    data class McpServer(val id: String) : PermissionResource
    data class Plugin(val id: String) : PermissionResource
    data class RemoteEnv(val id: String) : PermissionResource

    /** 运行记录里的写法;只含标识,不含用户内容。 */
    fun label(): String = when (this) {
        Global -> "global"
        is AppPackage -> "app:$packageName"
        is Directory -> "dir:$path"
        is McpServer -> "mcp:$id"
        is Plugin -> "plugin:$id"
        is RemoteEnv -> "remote:$id"
    }
}

/** 授权作用在谁身上。Persona / Plugin 是 v2 的扩展位,现在只用到前三个。 */
sealed interface PermissionSubject {
    data object Global : PermissionSubject
    data class Run(val runId: RunId) : PermissionSubject
    data class Conversation(val conversationId: String) : PermissionSubject
    data class Persona(val personaId: String) : PermissionSubject
    data class Plugin(val pluginId: String) : PermissionSubject
}

/** 一条授权。Once 不落盘,任务结束或超过步数上限自动作废。 */
data class Grant(
    val permission: PermissionId,
    val resource: PermissionResource = PermissionResource.Global,
    val access: Access,
    val scope: GrantScope = GrantScope.Conversation,
    val expiresAtMs: Long? = null,
    val source: GrantSource = GrantSource.User,
    /** 用户在同一次询问里选的按钮,用于审计和界面回显。 */
    val label: String? = null,
)

/** 一次权限评估的上下文。 */
data class PermissionContext(
    val runId: RunId,
    val conversationId: String,
    val provenance: RunProvenance = RunProvenance(),
    val personaId: String? = null,
    val pluginId: String? = null,
    val step: Int = 0,
)

/** 一次权限请求。 */
data class PermissionRequest(
    val permission: PermissionId,
    val resource: PermissionResource = PermissionResource.Global,
    val context: PermissionContext,
    /** 给用户看的一句话,例如「读写相册」「向飞书发送消息」。 */
    val reason: String? = null,
)

/** 权限评估的结论。 */
sealed interface PermissionVerdict {
    data object Allow : PermissionVerdict
    data class Ask(val reason: String) : PermissionVerdict
    data class Deny(val code: String, val message: String) : PermissionVerdict
}

/**
 * 一份权限清单条目:权限名、风险、默认结论、资源粒度、给人看的说明。
 * [defaultAccess] 是正式执行阶段的默认值;Phase 0 只按它记「本会怎么判」,不改变行为。
 */
data class PermissionSpec(
    val id: PermissionId,
    val risk: PermissionRisk,
    val defaultAccess: Access,
    val resourceKind: ResourceKind = ResourceKind.Global,
    val description: String,
)

/** 一次工具调用在观察模式下的结论,写进运行记录。 */
data class PermissionObservation(
    val permission: PermissionId,
    val resource: PermissionResource,
    val decision: String,
    val reason: String?,
)

/**
 * 执行层用的权限闸门:按工具给出结论。Phase 2a 只拦 [PermissionVerdict.Deny];`Ask` 仍按允许执行,
 * 等询问卡片接入后再变成真正的确认。
 */
fun interface PermissionGate {
    fun verdict(tool: ToolInfo): PermissionVerdict
}
