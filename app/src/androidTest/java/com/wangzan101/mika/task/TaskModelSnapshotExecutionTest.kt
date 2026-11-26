package com.wangzan101.mika.task

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.chat.ConversationSettings
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.run.ChannelPeer
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
import com.wangzan101.mika.settings.writeConfig
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real TaskService/TaskRun.execute routing tests; every model endpoint is a local fake. */
@RunWith(AndroidJUnit4::class)
class TaskModelSnapshotExecutionTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val providers = ProviderStore.get(context)
    private val conversations = ConversationStore.shared(context)
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val firstPrimaryRequest = CountDownLatch(1)
    private val releasePrimaryRequest = CountDownLatch(1)
    private val conversationIds = mutableListOf<String>()
    private val createdProviders = mutableListOf<String>()
    private lateinit var server: MockWebServer
    private lateinit var fixture: Fixture
    private var failAgentRequests = false

    @Before fun setUp() {
        DeviceSupport.service()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        automation.executeShellCommand("wm dismiss-keyguard").close()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val json = Json.parseToJsonElement(body).jsonObject
                val model = json.getValue("model").jsonPrimitive.content
                val streaming = json["stream"]?.jsonPrimitive?.booleanOrNull == true
                requests += Request(model, streaming)
                if (streaming && failAgentRequests) {
                    if (model == fixture.selectedModel) {
                        firstPrimaryRequest.countDown()
                        assertTrue("primary request timed out", releasePrimaryRequest.await(15, TimeUnit.SECONDS))
                    }
                    return MockResponse().setResponseCode(401).setBody("""{"error":{"message":"local failure"}}""")
                }
                if (streaming) return streamReply("done")
                return jsonReply("summary")
            }
        }
        server.start()
        fixture = createFixture()
    }

    @After fun tearDown() {
        releasePrimaryRequest.countDown()
        runCatching { TaskService.cancel(context) }
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        conversationIds.forEach { runCatching { conversations.delete(it) } }
        createdProviders.forEach { runCatching { providers.removeProvider(it) } }
        fixture.restore()
        runCatching { server.shutdown() }
    }

    @Test fun scheduledExecuteFreezesAgentFallbackStopSummaryAndRetestMetadata() {
        failAgentRequests = true
        val id = conversation("scheduled")
        conversations.setSettings(id, ConversationSettings(ModelChoice.Model(fixture.selectedId)))
        TaskService.startTrigger(context, "local scheduled run", id, "snapshot-test-${UUID.randomUUID()}")

        val primaryCalled = firstPrimaryRequest.await(45, TimeUnit.SECONDS)
        assertTrue("the scheduled Agent did not call the conversation primary; states=${DeviceTaskState.runs.value.map { it.state.value }}; snapshots=${DeviceTaskState.runs.value.map { it.modelSnapshot != null }}; kits=${DeviceTaskState.runs.value.map { it.kit != null }}; requests=$requests",
            primaryCalled)
        val run = pollUntil(5_000) { DeviceTaskState.runs.value.firstOrNull { it.conversationId == id } }
        assertNotNull(run)
        conversations.setSettings(id, ConversationSettings())
        providers.removeProvider(fixture.selectedProvider.id)
        releasePrimaryRequest.countDown()

        val end = pollUntil(20_000) {
            DeviceTaskState.runs.value.firstOrNull { it.conversationId == id }?.state?.value as? TaskState.Failed
        }
        assertNotNull("scheduled run did not fail locally", end)
        assertTrue("retest hint did not retain the run-start provider: ${end!!.reason}",
            fixture.selectedProvider.name in end.reason)
        assertTrue("the frozen fallback was not called: ${agentRequests()}",
            agentRequests().any { it.model == fixture.fallbackModel })
        assertTrue("the new global model leaked into the running Agent: ${agentRequests()}",
            agentRequests().none { it.model == fixture.globalModel })

        val summary = runBlocking { run!!.stopSummary("partial local draft") }
        assertEquals("summary", summary)
        assertTrue("stop summary did not use the frozen primary: $requests",
            requests.any { it.model == fixture.selectedModel && !it.streaming })
        assertTrue("stop summary used a post-change model: $requests",
            requests.none { it.model == fixture.globalModel })
    }

    @Test fun channelExecuteRereadsTheSidecarAtEveryRunStart() {
        val id = conversation("channel")
        conversations.rename(id, "local channel test")
        val peer = ChannelPeer(LocalChannel.ID, "local-test", id)
        conversations.setSettings(id, ConversationSettings(ModelChoice.Model(fixture.selectedId)))
        TaskService.startChannel(context, peer, "snapshot-${UUID.randomUUID()}", "first local channel run")
        pollUntil(20_000) { agentRequests().takeIf { it.isNotEmpty() } }
        awaitIdle(id)

        conversations.setSettings(id, ConversationSettings())
        TaskService.startChannel(context, peer, "snapshot-${UUID.randomUUID()}", "second local channel run")
        pollUntil(20_000) { agentRequests().takeIf { it.size >= 2 } }
        awaitIdle(id)

        assertEquals(listOf(fixture.selectedModel, fixture.globalModel), agentRequests().map { it.model })
    }

    private fun createFixture(): Fixture {
        val old = Fixture.State(
            providers.providers.value, providers.configs.value, providers.roles.value, providers.groups.value,
        )
        val suffix = UUID.randomUUID().toString().take(8)
        val base = server.url("/v1").toString().trimEnd('/')
        val global = providers.connect("Snapshot Global $suffix", ProviderConfig.Kind.OpenAiCompat, base, "local",
            listOf(ListedModel("global-$suffix"))).also { createdProviders += it.id }
        val globalId = providers.ensureConfig(global.id, "global-$suffix", caps())!!
        assertTrue(providers.choose(ModelRole.Main, RoleChoice.Model(globalId)))
        val selected = providers.connect("Snapshot Primary $suffix", ProviderConfig.Kind.OpenAiCompat, base, "local",
            emptyList(), untested = true).also { createdProviders += it.id }
        val selectedId = providers.ensureConfig(selected.id, "selected-$suffix", caps())!!
        assertTrue(providers.setMainFallback(0, selectedId))
        val fallback = providers.connect("Snapshot Fallback $suffix", ProviderConfig.Kind.OpenAiCompat, base, "local",
            listOf(ListedModel("fallback-$suffix"))).also { createdProviders += it.id }
        val fallbackId = providers.ensureConfig(fallback.id, "fallback-$suffix", caps())!!
        assertTrue(providers.setMainFallback(1, fallbackId))
        return Fixture(old, selectedId, selected, "selected-$suffix", "fallback-$suffix", "global-$suffix")
    }

    private fun conversation(prefix: String): String =
        "$prefix-snapshot-${UUID.randomUUID()}".also { conversationIds += it }

    private fun awaitIdle(id: String) {
        val end = pollUntil(20_000) {
            DeviceTaskState.runs.value.firstOrNull { it.conversationId == id }?.state?.value?.takeIf {
                it is TaskState.Succeeded || it is TaskState.Failed || it is TaskState.Cancelled
            }
        }
        assertTrue("channel run did not finish: $end", end is TaskState.Succeeded)
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    private fun agentRequests(): List<Request> = synchronized(requests) { requests.filter { it.streaming } }

    private data class Request(val model: String, val streaming: Boolean)

    private class Fixture(
        private val state: State,
        val selectedId: String,
        val selectedProvider: Provider,
        val selectedModel: String,
        val fallbackModel: String,
        val globalModel: String,
    ) {
        fun restore() {
            state.applyTo(ProviderStore.get(InstrumentationRegistry.getInstrumentation().targetContext))
        }

        class State(
            private val providerList: List<Provider>,
            private val configs: List<ProviderConfig>,
            private val roles: com.wangzan101.mika.settings.RoleChoices,
            private val groups: List<com.wangzan101.mika.core.model.ModelGroup>,
        ) {
            fun applyTo(store: ProviderStore) {
                store._providers.value = providerList
                store._configs.value = configs
                store._roles.value = roles
                store._groups.value = groups
                store.writeConfig()
            }
        }
    }

    private companion object {
        fun caps() = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)

        fun jsonReply(text: String) = MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"$text"}}]}""")

        fun streamReply(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

        fun <T> pollUntil(timeoutMs: Long, block: () -> T?): T {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                block()?.let { return it }
                Thread.sleep(50)
            }
            return block() ?: throw AssertionError("condition not met within ${timeoutMs}ms")
        }
    }
}
