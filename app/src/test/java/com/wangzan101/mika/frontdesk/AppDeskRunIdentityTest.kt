package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppDeskRunIdentityTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Run(override val conversationId: String, override val key: String, initial: TaskState) : DeskRun {
        val flow = MutableStateFlow(initial)
        override val state = flow
        var cancelled = false
        var changed = false
        override fun steer(words: String): Boolean { changed = true; return true }
        override fun answer(words: String) { changed = true }
        override fun cancel() { cancelled = true }
    }

    @Test fun adoptedRunCannotRebindBeforeItsMirrorStarts() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.setDeskParent("child", "parent")
        val original = Run("child", "original", TaskState.Running(1, "first"))
        val runs = MutableStateFlow<List<DeskRun>>(listOf(original))
        val port = AppDeskTaskPort(store, runs, { runs.value }, { _, _, _ -> },
            { 2 }, backgroundScope, { "not started" }, { "interrupted" })
        port.state("child")
        original.flow.value = TaskState.Succeeded("done")
        val replacement = Run("child", "replacement", TaskState.WaitingInput("second question"))
        runs.value = listOf(replacement)
        runCurrent()
        assertEquals(TaskState.Succeeded("done"), port.state("child")!!.value)
        assertFalse(port.answer("child", "yes"))
        assertFalse(port.cancel("child"))
        assertFalse(replacement.changed)
    }

    @Test fun controlsOnlyOperateAfterTheRegisteredRunIsObserved() = runTest {
        val runs = MutableStateFlow<List<DeskRun>>(emptyList())
        val port = AppDeskTaskPort(ConversationStore(tmp.newFolder()), runs, { runs.value }, { _, _, _ -> },
            { 2 }, backgroundScope, { "not started" }, { "interrupted" })
        val child = (port.dispatch("task", "", "parent") as DispatchOutcome.Started).taskId
        val original = Run(child, "original", TaskState.WaitingInput("question"))
        runs.value = listOf(original)
        assertFalse(port.answer(child, "answer"))
        runCurrent()
        assertTrue(port.answer(child, "answer"))
        original.flow.value = TaskState.Running(1, "working")
        assertTrue(port.steer(child, "more"))
        assertTrue(port.cancel(child))
        assertFalse(port.cancel("unknown"))
    }

    @Test fun completedDeskTaskCannotCancelOrSteerANewRunInItsConversation() = runTest {
        val runs = MutableStateFlow<List<DeskRun>>(emptyList())
        val port = AppDeskTaskPort(ConversationStore(tmp.newFolder()), runs, { runs.value }, { _, _, _ -> },
            { 2 }, backgroundScope, { "not started" }, { "interrupted" })
        val child = (port.dispatch("first task", "", "parent") as DispatchOutcome.Started).taskId
        val original = Run(child, "original", TaskState.Running(1, "first"))
        runs.value = listOf(original)
        runCurrent()
        original.flow.value = TaskState.Succeeded("done")
        runCurrent()
        val replacement = Run(child, "replacement", TaskState.Running(1, "second"))
        runs.value = listOf(replacement)
        runCurrent()
        assertEquals(TaskState.Succeeded("done"), port.state(child)!!.value)
        assertFalse("An old desk task cancelled a different run", port.cancel(child))
        assertFalse(port.steer(child, "change the old task"))
        assertFalse(replacement.cancelled)
        assertFalse(replacement.changed)
    }

    @Test fun completedDeskTaskCannotAnswerADifferentRunQuestion() = runTest {
        val runs = MutableStateFlow<List<DeskRun>>(emptyList())
        val port = AppDeskTaskPort(ConversationStore(tmp.newFolder()), runs, { runs.value }, { _, _, _ -> },
            { 2 }, backgroundScope, { "not started" }, { "interrupted" })
        val child = (port.dispatch("first task", "", "parent") as DispatchOutcome.Started).taskId
        val original = Run(child, "original", TaskState.Running(1, "first"))
        runs.value = listOf(original)
        runCurrent()
        original.flow.value = TaskState.Succeeded("done")
        runCurrent()
        val replacement = Run(child, "replacement", TaskState.WaitingInput("confirm second task?"))
        runs.value = listOf(replacement)
        runCurrent()
        assertFalse("An old desk task answered a different run", port.answer(child, "yes"))
        assertFalse(replacement.changed)
    }
}
