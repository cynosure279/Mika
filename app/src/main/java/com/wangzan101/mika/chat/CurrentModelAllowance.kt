package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.settings.effectiveRoles

/**
 * 用量面板「当前模型」区要显示的内容:主模型的显示名,和它相关的服务商(余额按服务商查)。
 */
data class CurrentModelAllowance(
    val modelLabel: String,
    val providers: List<Provider>,
)

/**
 * 主模型是具体模型时显示它的模型标识（和设置里模型行的口径一致，见 [com.wangzan101.mika.settings.ui.models.ModelsCopy.modelLine]；
 * `ProviderConfig.name` 在 `use()` 建的配置里就是服务商名，不能当模型名用），余额只查它服务商一家;
 * 是模型组时显示组名,余额按启用成员的服务商去重显示。没选主模型、引用的配置或服务商
 * 已不在(删了 Key、prune 了配置)时返回 null,面板不显示这个区。
 */
fun currentModelAllowance(
    roles: RoleChoices, configs: List<ProviderConfig>, groups: List<ModelGroup>, providers: List<Provider>,
): CurrentModelAllowance? {
    val configId = roles.resolveConfig(ModelRole.Main)
    if (configId != null) {
        val config = configs.firstOrNull { it.id == configId } ?: return null
        val provider = providers.firstOrNull { it.id == config.providerId } ?: return null
        return CurrentModelAllowance(config.model, listOf(provider))
    }
    val groupId = roles.resolveGroup(ModelRole.Main) ?: return null
    val group = groups.firstOrNull { it.id == groupId } ?: return null
    val used = group.enabledMembers
        .mapNotNull { member -> configs.firstOrNull { it.id == member.configId } }
        .mapNotNull { config -> providers.firstOrNull { it.id == config.providerId } }
        .distinctBy { it.id }
    if (group.name.isBlank() && used.isEmpty()) return null
    return CurrentModelAllowance(group.name, used)
}

/**
 * 本对话此刻的主模型:[mainOverride] 是对话自己存的选择(输入框上方的主模型入口),跟任务启动时
 * 走同一个解析入口(`ProviderStore.effectiveRoles`)——面板显示的模型必须和这个对话下一次任务
 * 真正用的一致;选择已失效(配置被删、组不可用)时由它退回全局主模型。
 */
fun currentModelAllowance(store: ProviderStore, mainOverride: ModelChoice? = null): CurrentModelAllowance? =
    currentModelAllowance(store.effectiveRoles(mainOverride), store.configs.value, store.groups.value, store.providers.value)
