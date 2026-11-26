package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.settings.connect
import com.wangzan101.mika.settings.ensureConfig
import com.wangzan101.mika.settings.saveGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/** 用量面板「当前模型」区的解析:具体模型、模型组与没选主模型各走哪条路;本对话单独指定主模型时跟着对话走。 */
class CurrentModelAllowanceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun caps(tools: Boolean = true) = Capabilities(toolCalls = tools, vision = false, contextTokens = 64_000, reasoning = false)
    private fun provider(id: String, name: String = id) = Provider(id = id, name = name, kind = ProviderConfig.Kind.OpenAiCompat, baseUrl = "https://$id.example.com")
    private fun config(id: String, providerId: String, name: String = "", model: String = "$id-model") =
        ProviderConfig(id = id, kind = ProviderConfig.Kind.OpenAiCompat, baseUrl = "https://$providerId.example.com", model = model, capabilities = caps(), name = name, providerId = providerId)

    private val providers = listOf(provider("p1", "DeepSeek"), provider("p2", "Kimi"), provider("p3", "智谱"))

    @Test fun concreteModelShowsItsModelIdAndOnlyItsProvider() {
        // 显示口径跟设置里模型行一致:用模型标识;name 不看(use() 建的配置 name 就是服务商名)
        val info = currentModelAllowance(
            RoleChoices(main = "c1"), listOf(config("c1", "p1", name = "服务商起的名字")), emptyList(), providers,
        )
        assertEquals(CurrentModelAllowance("c1-model", listOf(providers[0])), info)
    }

    @Test fun groupShowsItsNameAndDistinctProvidersOfEnabledMembers() {
        val group = ModelGroup(
            id = "g1", name = "备份组", strategy = com.wangzan101.mika.core.model.GroupStrategy.Failover,
            members = listOf(
                GroupMember("c1", enabled = true),
                GroupMember("c2", enabled = true),
                GroupMember("c3", enabled = true),
                GroupMember("c4", enabled = false),
                GroupMember("missing", enabled = true),
            ),
        )
        // c1、c2 同属 p1,去重成一家;c3 是 p2;禁用的 c4(p3)与找不到配置的成员不算
        val info = currentModelAllowance(
            RoleChoices(mainGroup = "g1"),
            listOf(config("c1", "p1"), config("c2", "p1"), config("c3", "p2"), config("c4", "p3")),
            listOf(group), providers,
        )
        assertEquals(CurrentModelAllowance("备份组", listOf(providers[0], providers[1])), info)
    }

    @Test fun noMainModelHidesTheSection() {
        assertNull(currentModelAllowance(RoleChoices(), emptyList(), emptyList(), providers))
        assertNull(currentModelAllowance(RoleChoices(), listOf(config("c1", "p1")), emptyList(), providers))
    }

    @Test fun staleReferencesFallBackToNothing() {
        // 配置已被删(不在 configs)或服务商已不存在(prune)都不显示,不拿旧余额误导
        assertNull(currentModelAllowance(RoleChoices(main = "gone"), emptyList(), emptyList(), providers))
        val info = currentModelAllowance(
            RoleChoices(main = "c1"), listOf(config("c1", "gone-provider")), emptyList(), providers,
        )
        assertNull(info)
        assertNull(currentModelAllowance(RoleChoices(mainGroup = "gone"), emptyList(), emptyList(), providers))
    }

    // ---- 本对话的主模型选择(输入框上方的主模型入口) ----

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key })

    /** 连上一家服务商并列出它的模型,不选任何用途。 */
    private fun connected(s: ProviderStore, name: String, host: String, vararg models: String): Provider =
        s.connect(name, ProviderConfig.Kind.OpenAiCompat, "https://$host.test/v1", "key", models.map { ListedModel(it) })

    /** 一家只挂一个模型的服务商,顺手把模型配置建好;返回 (服务商, 配置 id)。 */
    private fun ownModel(s: ProviderStore, name: String, host: String, model: String, tools: Boolean = true): Pair<Provider, String> {
        val p = connected(s, name, host, model)
        return p to s.ensureConfig(p.id, model, caps(tools))!!
    }

    @Test fun conversationOverrideReplacesTheGlobalMainModel() {
        val s = store()
        val global = connected(s, "全局服务商", "global", "m-a")
        assertTrue(s.use(ModelRole.Main, global.id, "m-a", caps()))
        val (own, ownConfig) = ownModel(s, "本对话服务商", "own", "m-b")

        // 跟随全局:照旧显示全局主模型和它的服务商
        assertEquals(CurrentModelAllowance("m-a", listOf(global)), currentModelAllowance(s))
        assertEquals(CurrentModelAllowance("m-a", listOf(global)), currentModelAllowance(s, null))
        // 本对话单独指定了别的模型:显示它和它的服务商,全局那家不出现
        assertEquals(CurrentModelAllowance("m-b", listOf(own)), currentModelAllowance(s, ModelChoice.Model(ownConfig)))
    }

    @Test fun conversationGroupOverrideShowsTheGroupAndItsProviders() {
        val s = store()
        val global = connected(s, "全局服务商", "global", "m-a")
        assertTrue(s.use(ModelRole.Main, global.id, "m-a", caps()))
        val kimi = connected(s, "Kimi", "kimi", "k1", "k2")
        val deepseek = connected(s, "DeepSeek", "deepseek", "d1")
        val k1 = s.ensureConfig(kimi.id, "k1", caps())!!
        val k2 = s.ensureConfig(kimi.id, "k2", caps())!!
        val d1 = s.ensureConfig(deepseek.id, "d1", caps())!!
        assertTrue(s.saveGroup(ModelGroup("g", "本对话的组", members = listOf(GroupMember(k1), GroupMember(k2), GroupMember(d1)))))

        // 组名 + 启用成员的服务商去重:Kimi 的两个成员算一家
        assertEquals(CurrentModelAllowance("本对话的组", listOf(kimi, deepseek)), currentModelAllowance(s, ModelChoice.Group("g")))
    }

    @Test fun invalidConversationOverrideFallsBackToTheGlobalMainModel() {
        val s = store()
        val global = connected(s, "全局服务商", "global", "m-a")
        assertTrue(s.use(ModelRole.Main, global.id, "m-a", caps()))
        val (_, noTools) = ownModel(s, "不支持工具的", "notools", "m-c", tools = false)

        // 指向的配置已经不在了(删了 Key、清掉了配置)、组已不存在、或那个模型当不了主模型:
        // 这个对话下一次任务实际会用全局主模型,面板显示的也必须是它
        val expected = CurrentModelAllowance("m-a", listOf(global))
        assertEquals(expected, currentModelAllowance(s, ModelChoice.Model("gone")))
        assertEquals(expected, currentModelAllowance(s, ModelChoice.Group("gone")))
        assertEquals(expected, currentModelAllowance(s, ModelChoice.Model(noTools)))
    }

    @Test fun conversationOverrideCountsEvenWithoutAGlobalMainModel() {
        val s = store()
        val (own, ownConfig) = ownModel(s, "本对话服务商", "own", "m-b")

        // 全局没选主模型时本来不显示这个区;本对话指定了一个能用的,它就是这个对话要用的模型
        assertNull(currentModelAllowance(s))
        assertEquals(CurrentModelAllowance("m-b", listOf(own)), currentModelAllowance(s, ModelChoice.Model(ownConfig)))
    }
}
