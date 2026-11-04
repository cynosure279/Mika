package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.ReasoningLevels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 对话页模型面板里的一项;[choice] 是写进对话设置的覆盖。 */
data class MainModelOption(
    val choice: ModelChoice,
    /** 组名或模型名。 */
    val title: String,
    /** 模型的服务商名;组为成员数。 */
    val subtitle: String? = null,
    val group: Boolean = false,
    val memberCount: Int = 0,
)

/**
 * 对话页「这个对话用哪个主模型」的资料:可选项、显示名、现在还能不能用。
 * 设置里的模型/组增删改后 [changes] 会发一次,对话页据此重算。
 */
interface MainModelCatalog {
    val changes: Flow<Unit>

    /** 可以覆盖的模型组(满足主模型要求)在前,再按服务商顺序列具体模型。 */
    fun options(): List<MainModelOption>

    /** 这个覆盖此刻能不能用;删了、组不满足主模型要求时为 null。 */
    fun usable(choice: ModelChoice?): ModelChoice?

    /** 覆盖在选项里的名字;找不到为 null。 */
    fun label(choice: ModelChoice): String?

    /** 跟随设置时主模型的名字(主模型是组时为组名);没选为 null。 */
    fun globalLabel(): String?

    fun isGroup(choice: ModelChoice): Boolean

    /** 跟随设置时主模型是不是组(档位说明用)。 */
    fun globalGroup(): Boolean

    /** 这个覆盖(或全局主模型)能选的思考档位;空表示不提供。 */
    fun reasoningLevels(choice: ModelChoice?): List<String>

    /** 同 [reasoningLevels],另带回档位是不是服务商明确给的。 */
    fun reasoningLevelsInfo(choice: ModelChoice?): ReasoningLevels
}

/** 设置里的模型配置/组;显示名就是组名、模型名和服务商名,不走界面文字。 */
class ProviderMainModelCatalog(private val store: ProviderStore) : MainModelCatalog {
    override val changes: Flow<Unit> =
        combine(store.providers, store.configs, store.groups, store.roles) { _, _, _, _ -> }

    override fun options(): List<MainModelOption> {
        val providerOrder = store.providers.value.withIndex().associate { (i, p) -> p.id to i }
        val groups = store.groups.value
            .filter { store.groupUsable(it, ModelRole.Main) }
            .sortedBy { it.name }
            .map {
                MainModelOption(
                    ModelChoice.Group(it.id), it.name, group = true, memberCount = it.enabledMembers.size,
                )
            }
        val models = store.configs.value
            // 能当主模型的(支持工具)排前面,和设置里的模型列表一致;不支持工具的仍可选,但排后面
            .sortedWith(compareBy(
                { providerOrder[it.providerId] ?: Int.MAX_VALUE },
                { if (it.capabilities.toolCalls) 0 else 1 },
                { it.model },
            ))
            .map { cfg ->
                MainModelOption(ModelChoice.Model(cfg.id), cfg.model, store.provider(cfg.providerId)?.name)
            }
        return groups + models
    }

    override fun usable(choice: ModelChoice?): ModelChoice? = store.usableMainChoice(choice)

    override fun label(choice: ModelChoice): String? = when (choice) {
        is ModelChoice.Model -> store.configs.value.firstOrNull { it.id == choice.configId }?.model
        is ModelChoice.Group -> store.group(choice.groupId)?.name
    }

    override fun globalLabel(): String? {
        val roles = store.roles.value
        roles.resolveGroup(ModelRole.Main)?.let { id -> store.group(id)?.name?.let { return it } }
        return roles.resolveConfig(ModelRole.Main)?.let { id -> store.configs.value.firstOrNull { it.id == id }?.model }
    }

    override fun isGroup(choice: ModelChoice): Boolean = choice is ModelChoice.Group

    override fun globalGroup(): Boolean = store.roles.value.mainGroup != null

    override fun reasoningLevels(choice: ModelChoice?): List<String> = store.reasoningLevels(choice)

    override fun reasoningLevelsInfo(choice: ModelChoice?): ReasoningLevels = store.reasoningLevelsInfo(choice)
}
