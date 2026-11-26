package com.wangzan101.mika.calls

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class VolcNoticeIdentityTest {
    @Test fun earlierQueuedGreetingCannotCompleteANewerNotice() = runBlocking {
        val server = MockWebServer()
        val http = OkHttpClient()
        val socket = CompletableDeferred<WebSocket>()
        val requests = Channel<String>(Channel.UNLIMITED)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { socket.complete(ws) }
            override fun onMessage(ws: WebSocket, text: String) {
                val type = Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content
                requests.trySend(type)
                when (type) {
                    "session.create" -> ws.send("""{"type":"session.created"}""")
                    "session.update" -> ws.send("""{"type":"session.updated"}""")
                    "session.close" -> ws.send("""{"type":"session.closed"}""")
                }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, null) }
        }))
        var session: VolcRealtimeSession? = null
        var collector: Job? = null
        try {
            val live = VolcRealtimeClient(http, server.url("/realtime").toString(), 3000, 250)
                .connect(VolcRealtimeCredentials(apiKey = "test-only"), VolcRealtimeConfig(agentTools = true))
            session = live
            repeat(2) { withTimeout(3000) { requests.receive() } }
            val events = Channel<VolcRealtimeEvent>(Channel.UNLIMITED)
            collector = launch { live.events.collect { events.send(it) } }
            assertTrue(live.sendGreeting("old greeting"))
            withTimeout(3000) { requests.receive() }
            val noticeDone = CompletableDeferred<Boolean>()
            assertTrue(live.sendNotice("new report") { noticeDone.complete(it) })
            withTimeout(3000) { requests.receive() }
            val peer = socket.await()
            peer.send("""{"type":"${VolcRealtimeProtocol.AUDIO_STARTED}","response_id":"old-greeting"}""")
            peer.send("""{"type":"${VolcRealtimeProtocol.AUDIO_DONE}","response_id":"old-greeting"}""")
            withTimeout(3000) { while (events.receive() != VolcRealtimeEvent.AssistantAudioEnded) {} }
            assertFalse("An earlier queued response was mistaken for the new report", noticeDone.isCompleted)
        } finally {
            session?.close()
            session?.let { withTimeoutOrNull(3000) { it.awaitClosed() } }
            collector?.cancelAndJoin()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
