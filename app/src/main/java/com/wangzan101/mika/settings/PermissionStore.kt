package com.wangzan101.mika.settings

import com.wangzan101.mika.core.permission.Access
import com.wangzan101.mika.core.permission.Grant
import com.wangzan101.mika.core.permission.GrantScope
import com.wangzan101.mika.core.permission.GrantSource
import com.wangzan101.mika.core.permission.PermissionCatalog
import com.wangzan101.mika.core.permission.PermissionContext
import com.wangzan101.mika.core.permission.PermissionGrants
import com.wangzan101.mika.core.permission.PermissionId
import com.wangzan101.mika.core.permission.PermissionPolicy
import com.wangzan101.mika.core.permission.PermissionResource
import com.wangzan101.mika.core.permission.PermissionVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 落盘的授权 + 进程内的运行授权;读不懂按空处理,不回退到放宽。 */
data class PermissionState(
    val global: List<Grant> = emptyList(),
    val conversations: Map<String, List<Grant>> = emptyMap(),
    val available: Boolean = true,
    val revision: Long = 0,
)

@Serializable
private data class StoredPermissions(
    val version: Int = 1,
    val grants: List<StoredGrant> = emptyList(),
    val conversations: Map<String, List<StoredGrant>> = emptyMap(),
)

@Serializable
private data class StoredGrant(
    val permission: String,
    val resource: String = "global",
    val access: String,
    /** 文件里用枚举名;旧文件缺省时按 Conversation / User。 */
    val scope: String = "Conversation",
    val expiresAtMs: Long? = null,
    val source: String = "User",
    val label: String? = null,
)

/** 资源在文件里的写法;只含标识,不含用户内容。不认识的写法返回 null(不能悄悄放宽成 Global)。 */
fun permissionResourceOf(label: String): PermissionResource? = when {
    label == "global" -> PermissionResource.Global
    label.startsWith("app:") -> PermissionResource.AppPackage(label.removePrefix("app:"))
    label.startsWith("dir:") -> PermissionResource.Directory(label.removePrefix("dir:"))
    label.startsWith("mcp:") -> PermissionResource.McpServer(label.removePrefix("mcp:"))
    label.startsWith("plugin:") -> PermissionResource.Plugin(label.removePrefix("plugin:"))
    label.startsWith("remote:") -> PermissionResource.RemoteEnv(label.removePrefix("remote:"))
    else -> null
}

/**
 * 授权存储:全局和对话的授权存 `files/settings/permissions.json`,运行的授权只在内存里。
 * 原子写;条目数有上限;文件损坏时 [PermissionState.available] 为 false,评估仍按清单默认值走,不会放宽。
 */
class PermissionStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<PermissionState> = mutable.asStateFlow()
    private val runGrants = mutableMapOf<String, List<Grant>>()

    @Synchronized fun grantsFor(context: PermissionContext): PermissionGrants = PermissionGrants(
        global = mutable.value.global,
        conversation = mutable.value.conversations[context.conversationId].orEmpty(),
        run = runGrants[context.runId.value].orEmpty(),
    )

    /** 用当前授权评估一次;Phase 1 只供设置页预览和测试,执行层尚未接。 */
    fun evaluate(
        request: com.wangzan101.mika.core.permission.PermissionRequest,
        once: Grant? = null,
        nowMs: Long = System.currentTimeMillis(),
        autonomous: Boolean = false,
    ): PermissionVerdict = PermissionPolicy.evaluate(request, grantsFor(request.context), once, nowMs, autonomous)

    /**
     * 执行层的闸门。Phase 2a 只有明确拒绝会拦;Ask 仍按允许执行,等询问卡片接入后再变成确认。
     * 未知工具 fail closed。
     */
    fun gateFor(context: PermissionContext): com.wangzan101.mika.core.permission.PermissionGate =
        com.wangzan101.mika.core.permission.PermissionGate { tool ->
            val permission = PermissionCatalog.permissionFor(tool)
                ?: return@PermissionGate PermissionVerdict.Deny("permission_unknown", "Unknown tool ${tool.name}; refuse it.")
            val spec = PermissionCatalog.spec(permission)
            PermissionPolicy.evaluate(
                com.wangzan101.mika.core.permission.PermissionRequest(
                    permission, PermissionCatalog.resourceFor(tool), context, spec?.description,
                ),
                grantsFor(context),
                // Phase 2a 还没有询问卡片:Ask 保持 Ask,执行层只拦明确拒绝
                downgradeAsk = false,
            )
        }

    @Synchronized fun addGlobal(grant: Grant) = update {
        it.copy(global = replaceGrant(it.global, grant))
    }

    @Synchronized fun removeGlobal(permission: PermissionId, resource: PermissionResource) = update {
        it.copy(global = it.global.filterNot { g -> g.permission == permission && g.resource == resource })
    }

    @Synchronized fun addConversation(conversationId: String, grant: Grant) = update { state ->
        val current = state.conversations[conversationId].orEmpty()
        state.copy(conversations = state.conversations + (conversationId to replaceGrant(current, grant, MAX_CONVERSATION_GRANTS)))
    }

    @Synchronized fun removeConversation(conversationId: String, permission: PermissionId, resource: PermissionResource) = update { state ->
        val current = state.conversations[conversationId].orEmpty()
        val next = current.filterNot { g -> g.permission == permission && g.resource == resource }
        state.copy(conversations = if (next.isEmpty()) state.conversations - conversationId else state.conversations + (conversationId to next))
    }

    @Synchronized fun setRun(runId: String, grants: List<Grant>) {
        runGrants[runId] = grants
    }

    @Synchronized fun clearRun(runId: String) {
        runGrants.remove(runId)
    }

    /**
     * 把旧开关映射成 `MigratedLegacy` 授权。每次启动都可以调:先清掉上次迁移的授权,再按当前开关重建,
     * 所以用户后来改了旧开关(解除屏蔽、打开联网、改 SelfConfig)不会留下过期的授权。用户自己改的授权保留。
     * 只写新文件,不改旧配置。
     */
    @Synchronized fun syncLegacy(legacy: PermissionMigration.Legacy): Boolean {
        val current = mutable.value
        val userGrants = current.global.filter { it.source != GrantSource.MigratedLegacy }
        // 用户已经对同一权限、同一资源表过态时,迁移值不再插进来。
        val migrated = PermissionMigration.grants(legacy).filter { m ->
            userGrants.none { g -> g.permission == m.permission && g.resource == m.resource }
        }
        val next = userGrants + migrated
        if (next == current.global) return false
        persist(current.copy(global = next, revision = current.revision + 1))
        return true
    }

    /** 测试和设置页用:重新从磁盘读。 */
    @Synchronized fun reload() {
        mutable.value = load()
    }

    private fun update(change: (PermissionState) -> PermissionState) {
        val next = change(mutable.value)
        persist(next.copy(revision = mutable.value.revision + 1))
    }

    private fun replaceGrant(grants: List<Grant>, grant: Grant, max: Int = MAX_GRANTS): List<Grant> {
        val next = grants.filterNot { it.permission == grant.permission && it.resource == grant.resource && it.scope == grant.scope } + grant
        return next.takeLast(max)
    }

    private fun persist(next: PermissionState) {
        val stored = StoredPermissions(
            grants = next.global.take(MAX_GRANTS).map(::toStored),
            conversations = next.conversations.mapValues { (_, grants) -> grants.take(MAX_CONVERSATION_GRANTS).map(::toStored) },
        )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(json.encodeToString(stored))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            // 写成功了就把文件修好了;之前读不懂的状态不再保留。
            mutable.value = next.copy(available = true)
        } finally {
            tmp.delete()
        }
    }

    private fun load(): PermissionState {
        if (!file.exists()) return PermissionState()
        return runCatching {
            val stored = json.decodeFromString<StoredPermissions>(file.readText())
            require(stored.version == 1)
            PermissionState(
                // 任何一条读不懂都按整个文件不可用处理:不能悄悄丢掉一条 Deny 后回到更宽的默认值。
                global = stored.grants.map { fromStored(it) ?: error("unreadable grant") },
                conversations = stored.conversations.mapValues { (_, grants) -> grants.map { fromStored(it) ?: error("unreadable grant") } },
            )
        }.getOrElse { PermissionState(available = false) }
    }

    private fun toStored(grant: Grant) = StoredGrant(
        permission = grant.permission.value,
        resource = grant.resource.label(),
        access = grant.access.name,
        scope = grant.scope.name,
        expiresAtMs = grant.expiresAtMs,
        source = grant.source.name,
        label = grant.label,
    )

    private fun fromStored(stored: StoredGrant): Grant? = runCatching {
        Grant(
            permission = PermissionId(stored.permission),
            resource = permissionResourceOf(stored.resource) ?: error("unknown resource"),
            access = Access.valueOf(stored.access),
            scope = GrantScope.valueOf(stored.scope),
            expiresAtMs = stored.expiresAtMs,
            source = GrantSource.valueOf(stored.source),
            label = stored.label,
        )
    }.getOrNull()

    private companion object {
        const val MAX_GRANTS = 500
        const val MAX_CONVERSATION_GRANTS = 200
    }
}

/** 旧开关到新授权的映射;每次启动同步,旧开关变了就跟着变。 */
object PermissionMigration {
    data class Legacy(
        val blockedApps: Set<String> = emptySet(),
        val shareAllowedApps: Set<String> = emptySet(),
        val sandboxNetwork: Boolean? = null,
        val selfConfig: Boolean? = null,
        val enabledMcpServers: List<String> = emptyList(),
    )

    fun grants(legacy: Legacy): List<Grant> = buildList {
        for (pkg in legacy.blockedApps) add(migrated(PermissionCatalog.PHONE_APP, PermissionResource.AppPackage(pkg), Access.Deny))
        for (pkg in legacy.shareAllowedApps) add(migrated(PermissionCatalog.PHONE_SHARE, PermissionResource.AppPackage(pkg), Access.Allow))
        legacy.sandboxNetwork?.let { on ->
            // 关着时记 Ask 而不是 Deny:以后用户可以在某个对话里临时允许,不会被全局 deny 挡住。
            add(migrated(PermissionCatalog.SANDBOX_NETWORK, PermissionResource.Global, if (on) Access.Allow else Access.Ask))
        }
        legacy.selfConfig?.let { on ->
            add(migrated(PermissionCatalog.SETTINGS_SELF, PermissionResource.Global, if (on) Access.Allow else Access.Ask))
        }
        for (id in legacy.enabledMcpServers) add(migrated(PermissionCatalog.MCP, PermissionResource.McpServer(id), Access.Allow))
    }

    private fun migrated(permission: PermissionId, resource: PermissionResource, access: Access) =
        Grant(permission, resource, access, GrantScope.Global, source = GrantSource.MigratedLegacy)
}
