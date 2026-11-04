package com.wangzan101.mika.core.permission

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin

/** 一次评估能看到的授权,按作用域分开;Run 的授权不落盘。 */
data class PermissionGrants(
    val global: List<Grant> = emptyList(),
    val conversation: List<Grant> = emptyList(),
    val run: List<Grant> = emptyList(),
) {
    fun all(): List<Grant> = global + conversation + run

    companion object {
        val NONE = PermissionGrants()
    }
}

/**
 * 权限评估:纯函数,不读存储、不弹窗。显式 deny 优先于任何 allow;否则取最具体的作用域和资源;
 * 同一层里只要有一条 ask 就要问。问不到人(无人值守、非手机发起、子任务、自主模式)时 ask 降级为 deny。
 */
object PermissionPolicy {
    private const val CODE_DENIED = "permission_denied"
    private const val CODE_UNATTENDED = "permission_unattended"
    private const val CODE_UNKNOWN = "permission_unknown"

    /**
     * [once] 是用户刚对这次询问点的「仅这次」,只在调用方确认动作成功后消费;这里只判放行。
     * [autonomous] 为 true 时 ask 不弹窗,直接 deny(自主模式不自动产生新授权,见 PERMISSION-PLAN §4.3)。
     * [downgradeAsk] 为 false 时 Ask 保持 Ask:Phase 2a 还没有询问卡片,闸门用它避免把普通任务误拒。
     */
    fun evaluate(
        request: PermissionRequest,
        grants: PermissionGrants = PermissionGrants.NONE,
        once: Grant? = null,
        nowMs: Long = System.currentTimeMillis(),
        autonomous: Boolean = false,
        downgradeAsk: Boolean = true,
    ): PermissionVerdict {
        val spec = PermissionCatalog.spec(request.permission)
            ?: return PermissionVerdict.Deny(CODE_UNKNOWN, "Unknown permission ${request.permission.value}; refuse it.")
        val matching = grants.all().filter { it.matches(request) && !it.expired(nowMs) }

        // 用户明确拒绝过的能力,任何 allow 都不能覆盖。
        if (matching.any { it.access == Access.Deny }) return denied(request)

        // 一次性的同意已经核对过这次请求:它是最具体的授权。
        if (once != null && once.access == Access.Allow && once.matches(request) && !once.expired(nowMs)) {
            return PermissionVerdict.Allow
        }

        val chosen = matching.maxWithOrNull(compareBy<Grant> { scopeRank(it.scope) }.thenBy { resourceRank(it.resource) })
        val access = when {
            chosen == null -> spec.defaultAccess
            else -> {
                // 同一层里只要有一条 ask 就要问;否则用这一层的结论。
                val sameLayer = matching.filter {
                    scopeRank(it.scope) == scopeRank(chosen.scope) && resourceRank(it.resource) == resourceRank(chosen.resource)
                }
                if (sameLayer.any { it.access == Access.Ask }) Access.Ask else chosen.access
            }
        }
        return when (access) {
            Access.Allow -> PermissionVerdict.Allow
            Access.Deny -> denied(request)
            Access.Ask -> ask(request, spec.description, autonomous, downgradeAsk)
        }
    }

    private fun ask(request: PermissionRequest, description: String, autonomous: Boolean, downgradeAsk: Boolean): PermissionVerdict =
        if (downgradeAsk && !canAsk(request, autonomous)) {
            PermissionVerdict.Deny(CODE_UNATTENDED,
                "This needs the user's approval, but this task cannot ask for it (unattended, non-UI origin, child task, or autonomous mode). " +
                    "Do not retry; continue without this capability or stop and tell the user.")
        } else {
            PermissionVerdict.Ask(request.reason ?: description)
        }

    private fun denied(request: PermissionRequest): PermissionVerdict = PermissionVerdict.Deny(CODE_DENIED,
        "The user turned off ${request.permission.value} for this resource. Do not retry or try to bypass it; " +
            "continue without this capability or tell the user where to change it.")

    /** 只有手机上有人看着的任务才弹确认。 */
    private fun canAsk(request: PermissionRequest, autonomous: Boolean): Boolean {
        val provenance = request.context.provenance
        return !autonomous && provenance.origin == RunOrigin.Ui && provenance.attendance != Attendance.Unattended
    }

    private fun Grant.matches(request: PermissionRequest): Boolean =
        permission == request.permission && (resource == PermissionResource.Global || resource == request.resource)

    private fun Grant.expired(nowMs: Long): Boolean = expiresAtMs?.let { nowMs >= it } ?: false

    private fun scopeRank(scope: GrantScope): Int = when (scope) {
        GrantScope.Once -> 4
        GrantScope.Run -> 3
        GrantScope.Conversation -> 2
        GrantScope.Global -> 1
    }

    private fun resourceRank(resource: PermissionResource): Int =
        if (resource == PermissionResource.Global) 0 else 1
}
