package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GroupMember
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class MainModelCatalogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/test") { cfg, _, _, _ ->
        FakeModel(cfg.model, cfg.capabilities)
    }

    private fun ProviderStore.connectOne(name: String, vararg models: String): Provider =
        connect(name, ProviderConfig.Kind.OpenAiCompat, "https://${name.lowercase()}.test/v1", "k", models.map { ListedModel(it) })

    private fun cap(tools: Boolean = true) = Capabilities(toolCalls = tools, vision = false, contextTokens = 64_000, reasoning = false)

    @Test fun optionsPutUsableGroupsBeforeModels() {
        val s = store()
        val p = s.connectOne("P", "m1", "m2")
        val a = s.ensureConfig(p.id, "m1", cap())!!
        val b = s.ensureConfig(p.id, "m2", cap())!!
        s.saveGroup(ModelGroup("g", "主力兜底", members = listOf(GroupMember(a), GroupMember(b))))

        val catalog = ProviderMainModelCatalog(s)
        val options = catalog.options()
        assertEquals(listOf(ModelChoice.Group("g"), ModelChoice.Model(a), ModelChoice.Model(b)), options.map { it.choice })
        assertEquals("主力兜底", options[0].title)
        assertEquals(2, options[0].memberCount)
        assertTrue(options[0].group)
        assertEquals("P", options[1].subtitle)
        assertFalse(options[1].group)
    }

    @Test fun toolCapableModelsComeBeforeOthersWithinAProvider() {
        val s = store()
        val p = s.connectOne("P", "zz-tool", "aa-plain")
        s.ensureConfig(p.id, "zz-tool", cap(tools = true))
        s.ensureConfig(p.id, "aa-plain", cap(tools = false))

        assertEquals(listOf("zz-tool", "aa-plain"), ProviderMainModelCatalog(s).options().map { it.title })
    }

    @Test fun unusableGroupIsNotOffered() {
        val s = store()
        val p = s.connectOne("P", "plain")
        val a = s.ensureConfig(p.id, "plain", cap(tools = false))!!
        s.saveGroup(ModelGroup("g", "g", members = listOf(GroupMember(a))))
        assertTrue(ProviderMainModelCatalog(s).options().none { it.group })
    }

    @Test fun labelsCoverModelGroupAndFollowingSettings() {
        val s = store()
        val p = s.connectOne("P", "m1", "m2")
        val a = s.ensureConfig(p.id, "m1", cap())!!
        val b = s.ensureConfig(p.id, "m2", cap())!!
        s.saveGroup(ModelGroup("g", "主力兜底", members = listOf(GroupMember(a), GroupMember(b))))
        s.choose(ModelRole.Main, RoleChoice.Group("g"))
        val catalog = ProviderMainModelCatalog(s)

        assertEquals("主力兜底", catalog.globalLabel())
        assertEquals("主力兜底", catalog.label(ModelChoice.Group("g")))
        assertEquals("m2", catalog.label(ModelChoice.Model(b)))
        assertTrue(catalog.isGroup(ModelChoice.Group("g")))
        assertFalse(catalog.isGroup(ModelChoice.Model(b)))
        assertNull(catalog.label(ModelChoice.Model("gone")))
        assertNull(catalog.usable(ModelChoice.Model("gone")))
    }

    @Test fun followingASpecificMainModelUsesItsModelName() {
        val s = store()
        val p = s.connectOne("P", "m1")
        val a = s.ensureConfig(p.id, "m1", cap())!!
        s.choose(ModelRole.Main, RoleChoice.Model(a))
        assertEquals("m1", ProviderMainModelCatalog(s).globalLabel())
    }

    private class FakeModel(override val name: String, override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(emptyList(), StopReason.EndTurn, Usage(1, 1))
    }
}
