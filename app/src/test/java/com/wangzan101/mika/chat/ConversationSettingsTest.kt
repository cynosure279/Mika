package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.ModelChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test fun followingSettingsDeletesTheSidecarWithoutWritingADefault() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val id = s.newConversation()
        assertTrue(s.setSettings(id, ConversationSettings()))
        assertFalse(File(dir, "$id.settings.json").exists())
        s.setSettings(id, ConversationSettings(ModelChoice.Model("cfg-1")))
        assertTrue(File(dir, "$id.settings.json").isFile)
        assertTrue(s.setSettings(id, ConversationSettings()))
        assertFalse(File(dir, "$id.settings.json").exists())
        assertEquals(ConversationSettings(), s.settings(id))
    }

    @Test fun settingsFileIsNotAConversationOrExportRecord() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val id = s.newConversation()
        s.setSettings(id, ConversationSettings(ModelChoice.Model("cfg-1")))
        assertTrue(s.list().isEmpty())
        assertTrue(s.autoArchiveCandidates().isEmpty())
        assertTrue(s.allRecords().conversations.isEmpty())
        s.append(id, Turn("查天气", "晴", "succeeded"))
        assertEquals(listOf(id), s.list().map { it.id })
        assertEquals(listOf(id), s.autoArchiveCandidates().map { it.id })
        assertEquals(listOf(id), s.allRecords().conversations.map { it.first })
    }

    @Test fun deletingConversationRemovesSettingsAndTemporaryFile() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val id = s.newConversation()
        s.append(id, Turn("查天气", "晴", "succeeded"))
        s.setSettings(id, ConversationSettings(ModelChoice.Group("group-1")))
        File(dir, "$id.settings.json.tmp").writeText("partial")
        s.delete(id)
        assertFalse(File(dir, "$id.settings.json").exists())
        assertFalse(File(dir, "$id.settings.json.tmp").exists())
    }

    @Test fun failedTemporaryWritePreservesThePreviousChoice() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val id = s.newConversation()
        s.setSettings(id, ConversationSettings(ModelChoice.Model("old")))
        val blocker = File(dir, "$id.settings.json.tmp")
        assertTrue(blocker.mkdir())
        File(blocker, "keep").writeText("partial")
        assertFalse(s.setSettings(id, ConversationSettings(ModelChoice.Model("new"))))
        assertEquals(ModelChoice.Model("old"), ConversationStore(dir).settings(id).main)
        assertTrue(File(blocker, "keep").isFile)
    }

    @Test fun successfulWriteUsesTemporaryFileAndLeavesNoPartialFile() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val id = s.newConversation()
        assertTrue(s.setSettings(id, ConversationSettings(ModelChoice.Model("cfg-1"))))
        assertFalse(File(dir, "$id.settings.json.tmp").exists())
        assertEquals(ModelChoice.Model("cfg-1"), ConversationStore(dir).settings(id).main)
    }

    @Test fun changingSettingsEmitsTheNormalConversationChange() = runTest {
        val s = store()
        val id = s.newConversation()
        val changed = async { s.changes.first() }
        runCurrent()
        assertTrue(s.setSettings(id, ConversationSettings(ModelChoice.Model("cfg-1"))))
        assertEquals(id, changed.await())
    }

    @Test fun branchAndBranchBeforeCopyIndependentChoices() {
        val dir = tmp.newFolder("c")
        val s = store(dir)
        val source = s.newConversation()
        s.append(source, Turn("查天气", "晴", "succeeded"))
        s.append(source, Turn("订票", "好了", "succeeded"))
        s.setSettings(source, ConversationSettings(ModelChoice.Group("group-1")))
        val branch = s.newConversation()
        val retry = s.newConversation()
        assertTrue(s.branch(source, branch, 0))
        assertTrue(s.branchBefore(source, retry, 1))
        assertEquals(ModelChoice.Group("group-1"), s.settings(branch).main)
        assertEquals(ModelChoice.Group("group-1"), s.settings(retry).main)

        s.setSettings(source, ConversationSettings(ModelChoice.Model("source-new")))
        s.setSettings(branch, ConversationSettings(ModelChoice.Model("branch-new")))
        assertEquals(ModelChoice.Group("group-1"), s.settings(retry).main)
        assertEquals(ModelChoice.Model("source-new"), s.settings(source).main)
        assertEquals(ModelChoice.Model("branch-new"), ConversationStore(dir).settings(branch).main)
    }

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
