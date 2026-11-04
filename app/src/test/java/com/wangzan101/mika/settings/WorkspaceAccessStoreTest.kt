package com.wangzan101.mika.settings

import com.wangzan101.mika.chat.WorkspaceChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceAccessStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = WorkspaceAccessStore(java.io.File(tmp.newFolder("s"), "workspace-access.json"))

    @Test fun defaultsToEverythingIncludingFutureItems() {
        val s = store()
        assertTrue(s.current().skillsAll)
        assertTrue(s.current().mcpAll)
        assertNull(s.current().skillsFor(WorkspaceChoice.FollowGlobal))
        assertNull(s.current().mcpFor(WorkspaceChoice.FollowGlobal))
    }

    @Test fun turningOneOffSwitchesToExplicitListAndRemembersWhatWasKnown() {
        val s = store()
        val all = setOf("a", "b", "c")
        s.setSkill("b", false, all)
        assertEquals(setOf("a", "c"), s.current().skills)
        assertFalse(s.current().skillsAll)
        assertEquals(all, s.current().skillsKnown)
        // 新出现的技能不在已知里(界面据此标「新」)
        assertFalse("d" in s.current().skillsKnown)
        assertTrue(s.current().skillsFor(WorkspaceChoice.FollowGlobal) == setOf("a", "c"))
    }

    @Test fun turningAllOffKeepsCurrentListEditable() {
        val s = store()
        s.customizeSkills(setOf("a", "b"))
        assertFalse(s.current().skillsAll)
        assertEquals(setOf("a", "b"), s.current().skills)
        s.customizeMcp(setOf("one"))
        assertFalse(s.current().mcpAll)
        assertEquals(setOf("one"), s.current().mcpServers)
    }

    /** 后来新增的项用户勾上后不再标「新」。 */
    @Test fun knownGrowsWhenUserInteracts() {
        val s = store()
        s.customizeSkills(setOf("a"))
        assertFalse("b" in s.current().skillsKnown)
        s.setSkill("b", true, setOf("a", "b"))
        assertTrue("b" in s.current().skillsKnown)
        assertTrue("b" in s.current().skills)
    }

    @Test fun selectAllAndNone() {
        val s = store()
        s.setAllSkills(false, setOf("a", "b"))
        assertEquals(WorkspaceChoice.Only(emptySet()).ids, s.current().skillsFor(WorkspaceChoice.FollowGlobal))
        assertFalse(s.current().skillsAll)
        s.setAllSkills(true, setOf("a", "b"))
        assertTrue(s.current().skillsAll)
        assertNull(s.current().skillsFor(WorkspaceChoice.FollowGlobal))
    }

    @Test fun mcpListAndConversationChoiceResolution() {
        val s = store()
        s.setMcpServer("one", false, setOf("one", "two"))
        assertEquals(setOf("two"), s.current().mcpFor(WorkspaceChoice.FollowGlobal))
        assertEquals(setOf("one"), s.current().mcpFor(WorkspaceChoice.Only(setOf("one"))))
        assertNull(s.current().mcpFor(WorkspaceChoice.All))
        assertEquals(emptySet<String>(), s.current().mcpFor(WorkspaceChoice.Only(emptySet())))
    }

    @Test fun persistsAcrossInstances() {
        val file = java.io.File(tmp.newFolder("s"), "workspace-access.json")
        val a = WorkspaceAccessStore(file)
        a.setSkill("a", false, setOf("a", "b"))
        a.setAllMcp(false, setOf("m1"))
        val b = WorkspaceAccessStore(file)
        assertEquals(a.current(), b.current())
    }

    @Test fun corruptFileFallsBackToEverything() {
        val file = java.io.File(tmp.newFolder("s"), "workspace-access.json")
        file.writeText("not json")
        assertTrue(WorkspaceAccessStore(file).current().skillsAll)
    }
}
