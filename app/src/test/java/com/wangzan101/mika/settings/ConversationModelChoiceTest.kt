package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.GroupPool
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/** 按对话覆盖主模型:具体模型、组、回退,以及轻量/看图的「用主模型」跟着走。 */
class ConversationModelChoiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/test") { cfg, _, _, setup ->
        setups[cfg.model] = setup
        FakeModel(cfg.model, cfg.capabilities)
    }
    private val setups = mutableMapOf<String, ClientSetup>()

    private fun ProviderStore.connectOne(name: String = "P", vararg models: String): Provider =
        connect(name, ProviderConfig.Kind.OpenAiCompat, "https://${name.lowercase()}.test/v1", "k", models.map { ListedModel(it) })

    private fun caps(tools: Boolean = true, vision: Boolean = false) =
        Capabilities(toolCalls = tools, vision = vision, contextTokens = 64_000, reasoning = false)

    private fun ProviderStore.member(p: Provider, model: String, tools: Boolean = true, vision: Boolean = false): String =
        ensureConfig(p.id, model, caps(tools, vision))!!

    private val session = ModelSession.of("conv")

    @Test fun modelOverrideAppliesToOneTaskWithoutChangingSettings() {
        val s = store()
        val p = s.connectOne(models = arrayOf("m1", "m2"))
        val a = s.member(p, "m1")
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(a)))
        // 选完主模型后没被任何用途/组引用的配置会被清掉,覆盖用的配置在之后建
        val b = s.member(p, "m2")

        assertEquals("m2", s.modelFor(ModelRole.Main, session, ModelChoice.Model(b))!!.name)
        // 设置里的用途选择没被改,下一次不带覆盖的任务还是 m1
        assertEquals("m1", s.modelFor(ModelRole.Main, session)!!.name)
        assertEquals(a, s.roles.value.main)
    }

    @Test fun groupOverrideBuildsTheGroupPool() {
        val s = store()
        val p = s.connectOne(models = arrayOf("m1", "m2"))
        val a = s.member(p, "m1")
        val b = s.member(p, "m2")
        assertTrue(s.saveGroup(ModelGroup("g", "主力兜底", members = listOf(GroupMember(a, 0), GroupMember(b, 1)))))
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(a)))

        val pool = s.modelFor(ModelRole.Main, session, ModelChoice.Group("g")) as GroupPool
        assertEquals("主力兜底", pool.groupName)
        assertEquals(listOf("m1", "m2"), pool.members.map { it.model.name })
    }

    @Test fun lightFollowingMainUsesTheOverride() {
        val s = store()
        val p = s.connectOne(models = arrayOf("m1", "m2"))
        val a = s.member(p, "m1")
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(a)))
        val b = s.member(p, "m2")
        assertEquals(RoleChoice.SameAsMain, s.roles.value.light)

        assertEquals("m2", s.modelFor(ModelRole.Light, session, ModelChoice.Model(b))!!.name)
    }

    @Test fun visionFollowingMainGroupWithoutEyesHasNoModel() {
        val s = store()
        val p = s.connectOne(models = arrayOf("text", "eye"))
        val a = s.member(p, "text")
        val g = ModelGroup("g", "g", members = listOf(GroupMember(a)))
        assertTrue(s.saveGroup(g))
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))

        assertNull(s.modelFor(ModelRole.Vision, session, ModelChoice.Group("g")))

        val v = s.member(p, "eye", vision = true)
        assertTrue(s.saveGroup(ModelGroup("gv", "gv", members = listOf(GroupMember(a), GroupMember(v)))))
        assertTrue(s.modelFor(ModelRole.Vision, session, ModelChoice.Group("gv")) is GroupPool)
    }

    @Test fun unknownOrUnusableChoiceFallsBackToSettings() {        val s = store()
        val p = s.connectOne(models = arrayOf("main", "plain"))
        val main = s.member(p, "main")
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(main)))
        val plain = s.member(p, "plain", tools = false)
        assertTrue(s.saveGroup(ModelGroup("g", "g", members = listOf(GroupMember(plain)))))

        // 配置/组已经不在了
        assertNull(s.usableMainChoice(ModelChoice.Model("gone")))
        assertNull(s.usableMainChoice(ModelChoice.Group("gone")))
        assertEquals("main", s.modelFor(ModelRole.Main, session, ModelChoice.Model("gone"))!!.name)
        // 组里有人不支持工具:不能当主模型,回退设置
        assertNull(s.usableMainChoice(ModelChoice.Group("g")))
        assertEquals("main", s.modelFor(ModelRole.Main, session, ModelChoice.Group("g"))!!.name)
        assertEquals(ModelChoice.Model(main), s.usableMainChoice(ModelChoice.Model(main)))
    }

    /** 设置里选的思考档位继续用,但覆盖的模型/成员不支持时不传,不把服务商不认的值发出去。 */
    @Test fun overrideOnlyKeepsEffortTheModelSupports() {
        val s = store()
        val p = s.connect(
            "R", ProviderConfig.Kind.OpenAiResponses, "https://r.test/v1", "k",
            listOf(ListedModel("m1"), ListedModel("m2", reasoning = false)),
        )
        val a = s.ensureConfig(p.id, "m1", caps())!!
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(a)))
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        val b = s.ensureConfig(p.id, "m2", caps())!!

        s.modelFor(ModelRole.Main, session, ModelChoice.Model(b))
        assertNull(setups["m2"]!!.reasoningEffort)
        s.modelFor(ModelRole.Main, session)
        assertEquals("high", setups["m1"]!!.reasoningEffort)
    }

    /** 「重新测试」提示跟着对话覆盖走:覆盖到没测过的那家时提示那一家,而不是设置里的主模型。 */
    @Test fun retestHintFollowsTheConversationOverride() {
        val s = store()
        val good = s.connect("Good", ProviderConfig.Kind.OpenAiCompat, "https://good.test/v1", "k", listOf(ListedModel("m1")))
        val bad = s.connect("Bad", ProviderConfig.Kind.OpenAiCompat, "https://bad.test/v1", "k", emptyList(), untested = true)
        val a = s.ensureConfig(good.id, "m1", caps())!!
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(a)))
        val b = s.ensureConfig(bad.id, "m2", caps())!!

        assertNull(s.untestedMainProvider())
        assertEquals(bad.id, s.untestedMainProvider(ModelChoice.Model(b))?.id)
    }

    private class FakeModel(override val name: String, override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(emptyList(), StopReason.EndTurn, Usage(3, 2))
    }
}
