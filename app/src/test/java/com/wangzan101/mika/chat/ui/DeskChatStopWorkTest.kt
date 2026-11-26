package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.BranchWorkspace
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.RetryAttachmentResult
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskBrain
import com.wangzan101.mika.core.frontdesk.DeskLedger
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReplyContext
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.CoroutineContext

/** 停止会话:附件准备、汇报生成和最终保存的取消边界。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeskChatStopWorkTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val calls = mutableListOf<String>()
    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { calls += "start:$goal" }
        override fun steer(conversationId: String, messageId: String, text: String) { calls += "steer:$text" }
        override fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean): Boolean {
            calls += "queue:$text"
            return true
        }
        override fun cancel() { calls += "cancel" }
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) { calls += "answer:$text" }
        override fun answerIn(conversationId: String, text: String) { calls += "answer@$conversationId:$text" }
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
    private val ok = Prerequisites.check(ready, hasMainModel = true)

    /** 后台任务端口:派出的任务各有一条可改的状态流,编号 child-1、child-2…… */
    private class FakePort : DeskTaskPort {
        val states = LinkedHashMap<String, MutableStateFlow<TaskState>>()
        val dispatched = mutableListOf<String>()
        val answered = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()

        override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome {
            val id = "child-${states.size + 1}"
            states[id] = MutableStateFlow(TaskState.Running(1, ""))
            dispatched += words
            return DispatchOutcome.Started(id)
        }

        override suspend fun dispatchWithFiles(parentConversation: String, prepare: suspend (child: String) -> String?): DispatchOutcome {
            val id = "child-${states.size + 1}"
            val words = prepare(id) ?: return DispatchOutcome.Failed("not_prepared")
            states[id] = MutableStateFlow(TaskState.Running(1, ""))
            dispatched += words
            return DispatchOutcome.Started(id)
        }

        override fun state(taskId: String): StateFlow<TaskState>? = states[taskId]
        override fun queuePosition(taskId: String): Int? = null
        override fun steer(taskId: String, words: String) = true
        override fun answer(taskId: String, words: String): Boolean { answered += taskId to words; return true }
        override fun cancel(taskId: String): Boolean { cancelled += taskId; return true }
    }

    /** 按脚本回话的前台大脑。 */
    private class ScriptBrain(
        val onReply: suspend (String, ReplyContext) -> Unit,
        val onNotice: suspend (String, ReplyContext) -> Unit = { _, _ -> },
    ) : DeskBrain {
        val replies = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val seeded = mutableListOf<List<Turn>>()
        override suspend fun reply(userText: String, ctx: ReplyContext) { replies += userText; onReply(userText, ctx) }
        override suspend fun lateResult(name: String, result: JsonObject, ctx: ReplyContext) {}
        override suspend fun notice(text: String, ctx: ReplyContext) { notices += text; onNotice(text, ctx) }
        override fun remember(assistantText: String) {}
        override fun seed(prior: List<Turn>) { seeded += prior }
    }

    private class Harness(
        val vm: ChatViewModel, val store: ConversationStore, val port: FakePort, val sunk: MutableList<Pair<String, DeskReport>>,
        val desk: (String) -> FrontDesk,
    )

    private class PausedIo(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var paused = false
        private val waiting = mutableListOf<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (paused) waiting += context to block else delegate.dispatch(context, block)
        }
        fun resume() {
            paused = false
            val ready = waiting.toList()
            waiting.clear()
            ready.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
    }

    private fun TestScope.harness(
        brain: DeskBrain?,
        store: ConversationStore = ConversationStore(tmp.newFolder()),
        brains: () -> DeskBrain? = { brain },
        importAttachments: suspend (String, List<String>) -> ImportResult = { _, _ -> ImportResult(emptyList(), emptyList()) },
        branchWorkspace: BranchWorkspace? = null,
        title: suspend (String, String) -> Unit = { _, _ -> },
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): Harness {
        val port = FakePort()
        val sunk = mutableListOf<Pair<String, DeskReport>>()
        val sink = object : DeskSink {
            override suspend fun record(conversationId: String, report: DeskReport) { sunk += conversationId to report }
        }
        val desks = HashMap<String, Pair<FrontDesk, DeskLedger>>()
        fun entry(id: String) = desks.getOrPut(id) {
            val ledger = DeskLedger()
            FrontDesk(id, backgroundScope, port, ledger, ReportPolicy({ 0L }), sink) to ledger
        }
        val deps = DeskChatDeps(
            desk = { entry(it).first },
            executor = { id, words ->
                val (desk, ledger) = entry(id)
                DeskToolExecutor(port, ledger, words, { 0L }, id) { desk.track(it.taskId, it.title) }
            },
            brain = { _, _ -> brains() },
            title = title,
        )
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
            io = io, desk = deps, sandboxReady = MutableStateFlow(true),
            importAttachments = importAttachments, branchWorkspace = branchWorkspace).also { runCurrent() }
        return Harness(vm, store, port, sunk) { entry(it).first }
    }

    private fun dispatch(words: String, title: String) = buildJsonObject { put("words", words); put("title", title) }

    @Test fun stoppingDuringAttachmentImportKeepsTheDraftAndClearsImporting() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val h = harness(ScriptBrain({ _, _ -> }), importAttachments = { _, _ ->
            try { gate.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
            ImportResult(listOf("/workspace/inbox/file.pdf" to 12L), emptyList())
        })
        h.vm.openConversation("c")
        runCurrent()
        h.vm.attach(listOf("content://x/file.pdf"))
        h.vm.input("analyze this file")
        runCurrent()
        assertTrue(h.vm.send(h.vm.ui.value.input, ok))
        runCurrent()
        assertTrue(h.vm.ui.value.importing)

        h.vm.stopAll()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(cancelled)
        assertFalse(h.vm.ui.value.importing)
        assertEquals("analyze this file", h.vm.ui.value.input)
        assertEquals(1, h.vm.ui.value.attachments.size)
        assertTrue(h.port.dispatched.isEmpty())
        assertTrue(h.store.turns("c").isEmpty())
    }

    @Test fun stoppingDuringFileCopyPreventsLaterDispatchAndSavesTheMessage() = runTest {
        val gate = CompletableDeferred<Unit>()
        var copying = false
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? = null
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long): List<RetryAttachmentResult> {
                copying = true
                gate.await()
                return paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.COPIED, 12L) }
            }
        }
        val h = harness(ScriptBrain({ _, _ -> }), branchWorkspace = workspace,
            importAttachments = { _, _ -> ImportResult(listOf("/workspace/inbox/file.pdf" to 12L), emptyList()) })
        h.vm.openConversation("c")
        runCurrent()
        h.vm.attach(listOf("content://x/file.pdf"))
        runCurrent()
        assertTrue(h.vm.send("analyze this file", ok))
        runCurrent()
        assertTrue(copying)

        h.vm.stopAll()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(h.port.dispatched.isEmpty())
        assertTrue(h.vm.ui.value.exchanges.single().status is ExchangeStatus.Done)
        val saved = h.store.turns("c").single()
        assertTrue(saved.userText.contains("analyze this file"))
        assertTrue(saved.userText.contains("/workspace/inbox/file.pdf"))
        assertTrue(saved.deskTasks.isEmpty())
    }

    @Test fun stoppingImmediatelyAfterSendingFilesFinishesTheRow() = runTest {
        val gate = CompletableDeferred<Unit>()
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? = null
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long): List<RetryAttachmentResult> {
                gate.await()
                return paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.COPIED, 12L) }
            }
        }
        val h = harness(ScriptBrain({ _, _ -> }), branchWorkspace = workspace,
            importAttachments = { _, _ -> ImportResult(listOf("/workspace/inbox/file.pdf" to 12L), emptyList()) })
        h.vm.openConversation("c")
        runCurrent()
        h.vm.attach(listOf("content://x/file.pdf"))
        runCurrent()
        assertTrue(h.vm.send("analyze this file", ok))
        h.vm.stopAll()
        runCurrent()

        assertTrue(h.port.dispatched.isEmpty())
        assertTrue(h.vm.ui.value.exchanges.single().status is ExchangeStatus.Done)
        assertEquals(1, h.store.turns("c").size)
    }

    @Test fun stoppingFilesWaitingToSaveKeepsTheDispatchedTaskOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val brain = ScriptBrain({ _, ctx -> ctx.say("partial"); gate.await() })
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? = null
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long): List<RetryAttachmentResult> =
                paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.COPIED, 12L) }
        }
        val h = harness(brain, branchWorkspace = workspace,
            importAttachments = { _, _ -> ImportResult(listOf("/workspace/inbox/file.pdf" to 12L), emptyList()) })
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("first", ok))
        runCurrent()
        h.vm.attach(listOf("content://x/file.pdf"))
        runCurrent()
        assertTrue(h.vm.send("analyze this file", ok))
        runCurrent()
        assertEquals(1, h.port.dispatched.size)

        h.vm.stopAll()
        runCurrent()

        val saved = h.store.turns("c")
        assertEquals(2, saved.size)
        assertEquals("first", saved.first().userText)
        assertEquals(listOf("child-1"), saved.last().deskTasks)
        assertTrue(h.vm.ui.value.exchanges.all { it.status is ExchangeStatus.Done })
    }

    @Test fun stoppingAnEmptyReportRemovesThePendingRowAndHandsItToTheFallback() = runTest {
        val gate = CompletableDeferred<Unit>()
        val brain = ScriptBrain({ text, ctx -> ctx.callTool(DeskTools.DISPATCH, dispatch(text, "task")) },
            { _, _ -> gate.await() })
        val h = harness(brain)
        h.vm.openConversation("c")
        h.vm.pageShown(true)
        runCurrent()
        assertTrue(h.vm.send("work", ok))
        runCurrent()
        h.port.states.getValue("child-1").value = TaskState.Failed("failure", com.wangzan101.mika.core.agent.FailKind.Environment)
        runCurrent()
        assertEquals(2, h.vm.ui.value.exchanges.size)

        h.vm.stopAll()
        runCurrent()

        assertEquals(1, h.vm.ui.value.exchanges.size)
        assertEquals(1, h.store.turns("c").size)
        // 一个字没说就被停:失败这件事交给兜底,不能就此丢掉
        assertEquals(listOf("child-1"), h.sunk.map { it.second.event.taskId })
    }

    @Test fun stoppingWhileASuccessReportWaitsForTheReplyKeepsTheResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        var replies = 0
        val brain = ScriptBrain({ text, ctx ->
            replies++
            if (replies == 1) ctx.callTool(DeskTools.DISPATCH, dispatch(text, "task"))
            else { ctx.say("thinking"); gate.await() }
        })
        val h = harness(brain)
        h.vm.openConversation("c")
        h.vm.pageShown(true)
        runCurrent()
        assertTrue(h.vm.send("work", ok))
        runCurrent()
        // 第二句正在回话、占着回话锁;这时后台任务办好了,汇报排在锁后面
        assertTrue(h.vm.send("another question", ok))
        runCurrent()
        h.port.states.getValue("child-1").value = TaskState.Succeeded("task result")
        runCurrent()

        h.vm.stopAll()
        runCurrent()

        val saved = h.store.turns("c")
        val result = saved.single { it.status == DeskTurn.REPORT_STATUS }
        assertTrue(result.outcome.contains("task result"))
        assertEquals(listOf("child-1"), result.deskTasks)
        assertTrue(h.vm.ui.value.exchanges.any { it.deskReply.orEmpty().contains("task result") })
        assertTrue(h.vm.ui.value.exchanges.all { it.status is ExchangeStatus.Done })
        assertTrue(h.sunk.isEmpty())
    }

    @Test fun stoppingAReportKeepsItsPartialTextAndAllowsLaterReports() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        var reports = 0
        val brain = ScriptBrain({ text, ctx -> ctx.callTool(DeskTools.DISPATCH, dispatch(text, "task")) },
            { _, ctx ->
                reports++
                if (reports == 1) {
                    ctx.say("partial report")
                    try { gate.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
                } else ctx.say("next report")
            })
        val h = harness(brain)
        h.vm.openConversation("c")
        h.vm.pageShown(true)
        runCurrent()
        assertTrue(h.vm.send("first work", ok))
        runCurrent()
        h.port.states.getValue("child-1").value = TaskState.Failed("failure", com.wangzan101.mika.core.agent.FailKind.Environment)
        runCurrent()
        assertEquals(1, brain.notices.size)

        h.vm.stopAll()
        runCurrent()

        assertTrue(cancelled)
        assertTrue(h.vm.ui.value.exchanges.all { it.status is ExchangeStatus.Done })
        val saved = h.store.turns("c").last()
        assertEquals(DeskTurn.REPORT_STATUS, saved.status)
        assertEquals("partial report", saved.outcome)
        assertEquals(listOf("child-1"), saved.deskTasks)
        assertTrue(h.sunk.isEmpty())

        assertTrue(h.vm.send("second work", ok))
        runCurrent()
        h.port.states.getValue("child-2").value = TaskState.Failed("failure", com.wangzan101.mika.core.agent.FailKind.Environment)
        runCurrent()
        assertEquals(2, brain.notices.size)
        assertEquals("next report", h.store.turns("c").last().outcome)
    }

    @Test fun stoppingAfterReportGenerationStillCompletesItsPendingSave() = runTest {
        val io = PausedIo(StandardTestDispatcher(testScheduler))
        val brain = ScriptBrain({ text, ctx -> ctx.callTool(DeskTools.DISPATCH, dispatch(text, "task")) },
            { _, ctx -> ctx.say("finished report"); io.paused = true })
        val h = harness(brain, io = io)
        h.vm.openConversation("c")
        h.vm.pageShown(true)
        runCurrent()
        assertTrue(h.vm.send("work", ok))
        runCurrent()
        h.port.states.getValue("child-1").value = TaskState.Failed("failure", com.wangzan101.mika.core.agent.FailKind.Environment)
        runCurrent()
        assertEquals("finished report", h.vm.ui.value.exchanges.last().deskReply)
        assertEquals(1, h.store.turns("c").size)

        h.vm.stopAll()
        runCurrent()
        io.resume()
        runCurrent()

        assertEquals("finished report", h.store.turns("c").last().outcome)
        assertEquals(listOf("child-1"), h.store.turns("c").last().deskTasks)
        assertEquals(2, h.store.turns("c").size)
        assertTrue(h.sunk.isEmpty())
    }

    @Test fun stoppingASuccessReportStillCompletesItsPendingSave() = runTest {
        val io = PausedIo(StandardTestDispatcher(testScheduler))
        val h = harness(ScriptBrain({ text, ctx -> ctx.callTool(DeskTools.DISPATCH, dispatch(text, "task")) }), io = io)
        h.vm.openConversation("c")
        h.vm.pageShown(true)
        runCurrent()
        assertTrue(h.vm.send("work", ok))
        runCurrent()
        io.paused = true
        h.port.states.getValue("child-1").value = TaskState.Succeeded("task result")
        runCurrent()
        val report = h.vm.ui.value.exchanges.last().deskReply
        assertTrue(report.orEmpty().contains("task result"))
        assertEquals(1, h.store.turns("c").size)

        h.vm.stopAll()
        runCurrent()
        io.resume()
        runCurrent()

        assertEquals(report, h.store.turns("c").last().outcome)
        assertEquals(listOf("child-1"), h.store.turns("c").last().deskTasks)
        assertEquals(2, h.store.turns("c").size)
        assertTrue(h.sunk.isEmpty())
    }

}
