package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.groupUsable
import com.wangzan101.mika.settings.usableMainChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 对话页模型面板的一项；[choice] 只允许具体配置或模型组。 */
data class MainModelOption(
    val choice: ModelChoice,
    val title: String,
    val subtitle: String? = null,
    val group: Boolean = false,
    val memberCount: Int = 0,
)

/** chip 的显示状态；失效的 [override] 保留用于恢复/审计，[label] 显示实际回退。 */
data class MainModelUi(
    val available: Boolean = false,
    val override: ModelChoice? = null,
    val label: String? = null,
    val group: Boolean = false,
    val fallback: Boolean = false,
    val globalLabel: String? = null,
    val options: List<MainModelOption> = emptyList(),
)

interface MainModelCatalog {
    val changes: Flow<Unit>
    fun options(): List<MainModelOption>
    fun usable(choice: ModelChoice?): ModelChoice?
    fun label(choice: ModelChoice): String?
    fun globalLabel(): String?
    fun isGroup(choice: ModelChoice): Boolean
}

/** 全局 ProviderStore 的只读目录；任何配置、组或角色变化都通知当前对话重算。 */
class ProviderMainModelCatalog(private val store: ProviderStore) : MainModelCatalog {
    override val changes: Flow<Unit> =
        combine(store.providers, store.configs, store.groups, store.roles) { _, _, _, _ -> }

    override fun options(): List<MainModelOption> {
        val providerOrder = store.providers.value.withIndex().associate { (i, p) -> p.id to i }
        val groups = store.groups.value
            .filter { store.groupUsable(it, ModelRole.Main) }
            .sortedBy { it.name }
            .map { MainModelOption(ModelChoice.Group(it.id), it.name, group = true, memberCount = it.enabledMembers.size) }
        val models = store.configs.value
            .filter { cfg -> store.usableMainChoice(ModelChoice.Model(cfg.id)) != null }
            .sortedWith(compareBy(
                { providerOrder[it.providerId] ?: Int.MAX_VALUE },
                { if (it.capabilities.toolCalls) 0 else 1 },
                { it.model },
            ))
            .map { cfg -> MainModelOption(ModelChoice.Model(cfg.id), cfg.model, store.provider(cfg.providerId)?.name) }
        return groups + models
    }

    override fun usable(choice: ModelChoice?): ModelChoice? = store.usableMainChoice(choice)

    override fun label(choice: ModelChoice): String? = when (choice) {
        is ModelChoice.Model -> store.configs.value.firstOrNull { it.id == choice.configId }?.model
        is ModelChoice.Group -> store.groups.value.firstOrNull { it.id == choice.groupId }?.name
    }

    override fun globalLabel(): String? {
        val roles = store.roles.value
        roles.resolveGroup(ModelRole.Main)?.let { id ->
            store.groups.value.firstOrNull { it.id == id }?.name?.let { return it }
        }
        return roles.resolveConfig(ModelRole.Main)?.let { id -> store.configs.value.firstOrNull { it.id == id }?.model }
    }

    override fun isGroup(choice: ModelChoice): Boolean = choice is ModelChoice.Group
}
