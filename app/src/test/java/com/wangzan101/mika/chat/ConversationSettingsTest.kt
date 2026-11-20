package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.ModelChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(dir: File = tmp.newFolder("c")) = ConversationStore(dir)

    @Test fun missingOrCorruptFileMeansFollowSettings() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val id = s.newConversation()
        assertEquals(ConversationSettings(), s.settings(id))
        s.setSettings(id, ConversationSettings(ModelChoice.Model("cfg-1")))
        File(dir, "$id.settings.json").writeText("not json")
        assertEquals(ConversationSettings(), s.settings(id))
    }

    @Test fun modelAndGroupRoundTripAcrossInstances() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val id = s.newConversation()
        assertTrue(s.setSettings(id, ConversationSettings(ModelChoice.Model("cfg-1"))))
        assertEquals(ModelChoice.Model("cfg-1"), ConversationStore(dir).settings(id).main)
        assertTrue(s.setSettings(id, ConversationSettings(ModelChoice.Group("group-1"))))
        assertEquals(ModelChoice.Group("group-1"), ConversationStore(dir).settings(id).main)
    }

    @Test fun followingSettingsDeletesTheSidecar() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val id = s.newConversation()
        s.setSettings(id, ConversationSettings(ModelChoice.Model("cfg-1")))
        assertTrue(File(dir, "$id.settings.json").isFile)
        assertTrue(s.setSettings(id, ConversationSettings()))
        assertFalse(File(dir, "$id.settings.json").exists())
        assertEquals(ConversationSettings(), s.settings(id))
    }

    @Test fun settingsFileIsNotAConversation() {
        val s = store()
        val id = s.newConversation()
        s.append(id, Turn("查天气", "晴", "succeeded"))
        s.setSettings(id, ConversationSettings(ModelChoice.Model("cfg-1")))
        assertEquals(listOf(id), s.list().map { it.id })
    }

    @Test fun deletingConversationRemovesSettings() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val id = s.newConversation()
        s.append(id, Turn("查天气", "晴", "succeeded"))
        s.setSettings(id, ConversationSettings(ModelChoice.Group("group-1")))
        s.delete(id)
        assertFalse(File(dir, "$id.settings.json").exists())
    }

    @Test fun branchCopiesMainChoice() {
        val s = store()
        val source = s.newConversation()
        s.append(source, Turn("查天气", "晴", "succeeded"))
        s.append(source, Turn("订票", "好了", "succeeded"))
        s.setSettings(source, ConversationSettings(ModelChoice.Group("group-1")))
        val target = s.newConversation()
        assertTrue(s.branch(source, target, 0))
        assertEquals(ModelChoice.Group("group-1"), s.settings(target).main)
    }

    /** 没有覆盖的对话分支出来还是跟随设置,不凭空写一个空旁文件。 */
    @Test fun branchWithoutChoiceLeavesTargetFollowingSettings() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val source = s.newConversation()
        s.append(source, Turn("查天气", "晴", "succeeded"))
        val target = s.newConversation()
        assertTrue(s.branch(source, target, 0))
        assertNull(s.settings(target).main)
        assertFalse(File(dir, "$target.settings.json").exists())
    }
}
