package com.wangzan101.mika.task

import com.wangzan101.mika.chat.ConversationSettings
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.choose
import com.wangzan101.mika.settings.connect
import com.wangzan101.mika.settings.ensureConfig
import com.wangzan101.mika.settings.removeProvider
import com.wangzan101.mika.settings.setMainFallback
import com.wangzan101.mika.settings.setFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class TaskModelSnapshotTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun providers() = ProviderStore(tmp.newFolder("providers"), AesGcmKeyCipher { key }) { cfg, _, _, _ ->
        FakeModel(cfg.model, cfg.capabilities)
    }

    private fun ProviderStore.connectOne(name: String, vararg models: String, untested: Boolean = false): Provider =
        connect(name, ProviderConfig.Kind.OpenAiCompat, "https://${name.lowercase()}.test/v1", "k",
            if (untested) emptyList() else models.map { ListedModel(it) }, untested = untested)

    private fun ProviderStore.member(p: Provider, model: String): String = ensureConfig(p.id, model,
        Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false))!!

    @Test fun snapshotFreezesOverrideFallbacksAndRetestMetadata() {
        val s = providers()
        val global = s.connectOne("Global", "global")
        val selected = s.connectOne("Selected", "selected", untested = true)
        val backup = s.connectOne("Backup", "backup")
        val globalId = s.member(global, "global")
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(globalId)))
        val selectedId = s.member(selected, "selected")
        assertTrue(s.setMainFallback(0, selectedId))
        // ensureConfig 后若一直不被用途/备援引用，下一次 prune 会删掉它；逐个建并逐个挂上。
        val backupId = s.member(backup, "backup")
        assertTrue(s.setMainFallback(1, backupId))
        val choice = ModelChoice.Model(selectedId)

        val snapshot = TaskModelSnapshot.capture(s, ModelSession.of("chat"), choice)
        val frozenMain = snapshot.main
        val frozenFallbacks = snapshot.mainFallbacks
        assertEquals("selected", frozenMain!!.name)
        assertEquals(listOf("backup"), frozenFallbacks.map { it.name })
        assertEquals(listOf(selected.id, backup.id), snapshot.mainProviderIds)
        assertEquals(selected.id, snapshot.retestProvider?.id)
        assertFalse(snapshot.routeLightSeparately)

        s.removeProvider(selected.id)
        assertSame(frozenMain, snapshot.main)
        assertSame(frozenFallbacks, snapshot.mainFallbacks)
        assertEquals(selected.id, snapshot.retestProvider?.id)
        assertEquals(listOf(selected.id, backup.id), snapshot.mainProviderIds)

        val next = TaskModelSnapshot.capture(s, ModelSession.of("chat"), choice)
        assertEquals("global", next.main!!.name)
        assertNull(next.retestProvider)
    }

    @Test fun captureReadsTheLatestConversationChoice() {
        val conversations = ConversationStore(tmp.newFolder("chats"))
        val s = providers()
        val global = s.connectOne("Global", "global")
        val selected = s.connectOne("Selected", "selected")
        val globalId = s.member(global, "global")
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(globalId)))
        val selectedId = s.member(selected, "selected")
        val id = "scheduled-or-channel"

        assertEquals("global", TaskModelSnapshot.capture(s, ModelSession.of(id), conversations.settings(id).main).main!!.name)
        conversations.setSettings(id, ConversationSettings(ModelChoice.Model(selectedId)))
        assertEquals("selected", TaskModelSnapshot.capture(s, ModelSession.of(id), conversations.settings(id).main).main!!.name)
        conversations.setSettings(id, ConversationSettings(ModelChoice.Model("deleted")))
        assertEquals("global", TaskModelSnapshot.capture(s, ModelSession.of(id), conversations.settings(id).main).main!!.name)
    }

    @Test fun directModelLightSameAsMainExcludesTheConversationPrimaryFromFallbacks() {
        val s = providers()
        val p = s.connectOne("P", "global", "selected")
        val globalId = s.member(p, "global")
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(globalId)))
        val selectedId = s.member(p, "selected")
        assertTrue(s.setFallback(ModelRole.Light, 0, selectedId))

        val snapshot = TaskModelSnapshot.capture(s, ModelSession.of("chat"), ModelChoice.Model(selectedId))

        assertEquals("selected", snapshot.light!!.name)
        assertEquals(emptyList<String>(), snapshot.lightFallbacks.map { it.name })
    }

    @Test fun directModelVisionSameAsMainExcludesTheConversationPrimaryFromFallbacks() {
        val s = providers()
        val p = s.connectOne("P", "global", "selected")
        val globalId = s.member(p, "global")
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(globalId)))
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        val selectedId = s.member(p, "selected")
        assertTrue(s.setFallback(ModelRole.Vision, 0, selectedId))

        val snapshot = TaskModelSnapshot.capture(s, ModelSession.of("chat"), ModelChoice.Model(selectedId))

        assertEquals("selected", snapshot.vision!!.name)
        assertEquals(emptyList<String>(), snapshot.visionFallbacks.map { it.name })
    }

    @Test fun explicitDifferentLightStillUsesStepRouting() {
        val s = providers()
        val p = s.connectOne("P", "main", "override", "light")
        val main = s.member(p, "main")
        assertTrue(s.choose(ModelRole.Main, RoleChoice.Model(main)))
        val light = s.member(p, "light")
        assertTrue(s.choose(ModelRole.Light, RoleChoice.Model(light)))
        val override = s.member(p, "override")

        val snapshot = TaskModelSnapshot.capture(s, ModelSession.of("chat"), ModelChoice.Model(override))
        assertEquals("override", snapshot.main!!.name)
        assertEquals("light", snapshot.light!!.name)
        assertTrue(snapshot.routeLightSeparately)
    }

    private class FakeModel(override val name: String, override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(emptyList(), StopReason.EndTurn, Usage(1, 1))
    }
}
