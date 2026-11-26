package com.wangzan101.mika.settings

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationSettings
import com.wangzan101.mika.chat.ui.ChatViewModel
import com.wangzan101.mika.chat.ui.MainModelCatalog
import com.wangzan101.mika.chat.ui.MainModelOption
import com.wangzan101.mika.chat.ui.TaskActions
import com.wangzan101.mika.core.agent.GroupPool
import com.wangzan101.mika.core.agent.TaskState
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
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/** 按对话覆盖主模型：具体模型、组、回退，以及轻量/看图的「用主模型」跟着走。 */
class ConversationModelChoiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val setups = mutableMapOf<String, ClientSetup>()

    private fun store(conversations: ConversationStore? = null) = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/test",
        externalConfigReferences = { conversations?.referencedModelConfigIds().orEmpty() }) { cfg, _, _, setup ->
        setups[cfg.model] = setup
        FakeModel(cfg.model, cfg.capabilities)
    }

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
        val b = s.member(p, "m2")

        assertEquals("m2", s.modelFor(ModelRole.Main, session, ModelChoice.Model(b))!!.name)
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

    @Test fun visionFollowingGlobalTextOnlyGroupHasNoModel() {
        val s = store()
        val p = s.connectOne(models = arrayOf("text"))
        val text = s.member(p, "text")
        assertTrue(s.saveGroup(ModelGroup("g", "text-only", members = listOf(GroupMember(text)))))
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Group("g")))
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))

        assertNull(s.modelFor(ModelRole.Vision, session))
    }

    @Test fun unknownOrUnusableChoiceFallsBackToSettings() {
        val s = store()
        val p = s.connectOne(models = arrayOf("main", "plain"))
        val main = s.member(p, "main")
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(main)))
        val plain = s.member(p, "plain", tools = false)
        assertTrue(s.saveGroup(ModelGroup("g", "g", members = listOf(GroupMember(plain)))))

        assertNull(s.usableMainChoice(ModelChoice.Model("gone")))
        assertNull(s.usableMainChoice(ModelChoice.Group("gone")))
        assertEquals("main", s.modelFor(ModelRole.Main, session, ModelChoice.Model("gone"))!!.name)
        assertNull(s.usableMainChoice(ModelChoice.Group("g")))
        assertEquals("main", s.modelFor(ModelRole.Main, session, ModelChoice.Group("g"))!!.name)
        assertNull(s.usableMainChoice(ModelChoice.Model(plain)))
        assertEquals("main", s.modelFor(ModelRole.Main, session, ModelChoice.Model(plain))!!.name)
        assertEquals(ModelChoice.Model(main), s.usableMainChoice(ModelChoice.Model(main)))
    }

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

    @Test fun roleChangeKeepsModelOwnedBySavedConversation() {
        val conversations = ConversationStore(tmp.newFolder())
        val s = store(conversations)
        val p = s.connectOne(models = arrayOf("selected", "light"))
        val selected = s.member(p, "selected")
        val choice = ModelChoice.Model(selected)
        assertTrue(conversations.setSettings("c", ConversationSettings(choice)))
        val light = s.member(p, "light")

        assertTrue(s.choose(ModelRole.Light, RoleChoice.Model(light)))

        assertEquals(choice, s.usableMainChoice(choice))
        assertEquals("selected", s.modelFor(ModelRole.Main, session, choice)!!.name)
    }

    @Test fun fallbackChangeKeepsModelOwnedByPendingConversation() = runTest {
        val conversationDir = tmp.newFolder()
        val conversations = ConversationStore(conversationDir)
        val s = store(conversations)
        val p = s.connectOne(models = arrayOf("selected", "fallback"))
        val selected = s.member(p, "selected")
        val choice = ModelChoice.Model(selected)
        val catalog = object : MainModelCatalog {
            override val changes = MutableSharedFlow<Unit>()
            override fun options() = listOf(MainModelOption(choice, "selected"))
            override fun usable(value: ModelChoice?) = value?.takeIf { it == choice }
            override fun label(value: ModelChoice) = "selected"
            override fun globalLabel() = null
            override fun isGroup(value: ModelChoice) = false
        }
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle),
            MutableStateFlow(Takeover.Stage.None), NoActions, conversations, mainModels = catalog)
        vm.chooseMainModel(choice)
        assertNull(vm.ui.value.conversationId)
        assertFalse(conversationDir.listFiles().orEmpty().any { it.name.endsWith(".settings.json") })
        val fallback = s.member(p, "fallback")

        assertTrue(s.setFallback(ModelRole.Light, 0, fallback))

        assertEquals(choice, s.usableMainChoice(choice))
        assertEquals("selected", s.modelFor(ModelRole.Main, session, choice)!!.name)
    }

    private object NoActions : TaskActions {
        override fun start(goal: String, conversationId: String) {}
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private class FakeModel(override val name: String, override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(emptyList(), StopReason.EndTurn, Usage(3, 2))
    }
}
