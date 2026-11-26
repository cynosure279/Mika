package com.wangzan101.mika.calls

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class RealtimeNoticeCorrelationTest {
    private class Socket : WebSocket {
        val sent = mutableListOf<JsonObject>()
        override fun request() = Request.Builder().url("http://localhost/").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += Json.parseToJsonElement(text).jsonObject; return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }

    private class Connection(val openAi: Boolean = false, timeout: Long = 200) : AutoCloseable {
        val socket = Socket()
        val session = VolcRealtimeSession(VolcRealtimeConfig(protocol =
            if (openAi) CallVoiceProtocol.OPENAI_REALTIME else CallVoiceProtocol.VOLC_DUPLEX), 1, timeout)
        init {
            session.attach(socket)
            session.listener.onOpen(socket, Response.Builder().request(socket.request())
                .protocol(Protocol.HTTP_1_1).code(101).message("test").build())
            emit("""{"type":"session.created"}""")
            if (openAi) emit("""{"type":"session.updated"}""")
        }
        fun emit(json: String) = session.listener.onMessage(socket, json)
        fun notice(done: (Boolean) -> Unit): String {
            assertTrue(session.sendNotice("report", done))
            val frame = socket.sent.last()
            return if (openAi) frame.getValue("response").jsonObject.getValue("metadata")
                .jsonObject.getValue("mika_notice_id").jsonPrimitive.content
            else frame.getValue("event_id").jsonPrimitive.content
        }
        fun created(response: String, request: String) = emit("""{"type":"response.created","response":{
            "id":"$response","metadata":{"mika_notice_id":"$request"}}}""")
        fun audio(type: String, response: String?, request: String? = null) = emit(buildJsonObject {
            put("type", type)
            response?.let { put("response_id", it) }
            request?.let { put("event_id", it) }
        }.toString())
        fun done(response: String, status: String) = emit(
            """{"type":"response.done","response":{"id":"$response","status":"$status"}}""")
        override fun close() = session.listener.onClosed(socket, 1000, "test")
    }

    @Test fun unrelatedOrAnonymousAudioFallsBackWithoutClaimingSuccess() = runBlocking {
        Connection().use { c ->
            val ends = Channel<Boolean>(4)
            val request = c.notice { ends.trySend(it) }
            c.audio(VolcRealtimeProtocol.AUDIO_STARTED, "older")
            c.audio(VolcRealtimeProtocol.AUDIO_DONE, "older")
            c.audio(VolcRealtimeProtocol.AUDIO_STARTED, null, request)
            c.audio(VolcRealtimeProtocol.AUDIO_DONE, null)
            assertTrue(c.session.sendPcm16(ByteArray(640)))
            assertEquals(false, withTimeout(3000) { ends.receive() })
            c.close()
            assertFalse(ends.tryReceive().isSuccess)
        }
    }

    @Test fun onlyTheCorrelatedResponseCanCompleteANotice() {
        Connection(timeout = 3000).use { c ->
            val ends = mutableListOf<Boolean>()
            val request = c.notice { ends += it }
            c.audio(VolcRealtimeProtocol.AUDIO_STARTED, "old")
            c.audio(VolcRealtimeProtocol.AUDIO_DONE, "old")
            assertTrue(ends.isEmpty())
            c.audio(VolcRealtimeProtocol.AUDIO_STARTED, "new", request)
            c.audio(VolcRealtimeProtocol.AUDIO_DONE, "new")
            c.audio(VolcRealtimeProtocol.AUDIO_DONE, "new")
            c.close()
            assertEquals(listOf(true), ends)
        }
    }

    @Test fun noticeCannotBeReboundToAnotherResponse() {
        Connection(timeout = 3000).use { c ->
            val ends = mutableListOf<Boolean>()
            val request = c.notice { ends += it }
            c.audio(VolcRealtimeProtocol.AUDIO_STARTED, "bound", request)
            c.audio(VolcRealtimeProtocol.AUDIO_STARTED, "other", request)
            c.audio(VolcRealtimeProtocol.AUDIO_DONE, "other")
            assertEquals(listOf(false), ends)
            c.close()
            assertEquals(listOf(false), ends)
        }
    }

    @Test fun anonymousCompletionCannotConfirmACorrelatedNotice() {
        Connection(timeout = 3000).use { c ->
            val ends = mutableListOf<Boolean>()
            val request = c.notice { ends += it }
            c.audio(VolcRealtimeProtocol.AUDIO_STARTED, "bound", request)
            c.audio(VolcRealtimeProtocol.AUDIO_DONE, null)
            assertTrue(ends.isEmpty())
            c.audio(VolcRealtimeProtocol.AUDIO_DONE, "bound")
            assertEquals(listOf(true), ends)
        }
    }

    @Test fun playbackWithoutFurtherProgressAlsoFallsBack() = runBlocking {
        Connection().use { c ->
            val ends = Channel<Boolean>(4)
            val request = c.notice { ends.trySend(it) }
            c.audio(VolcRealtimeProtocol.AUDIO_STARTED, "stalled", request)
            assertEquals(false, withTimeout(3000) { ends.receive() })
            c.close()
            assertFalse(ends.tryReceive().isSuccess)
        }
    }

    @Test fun openAiAudioDoneWaitsForTheResponseTerminalStatus() {
        for (status in listOf("completed", "cancelled", "failed", "incomplete")) {
            Connection(openAi = true, timeout = 3000).use { c ->
                val ends = mutableListOf<Boolean>()
                val request = c.notice { ends += it }
                c.created("response", request)
                c.audio(VolcRealtimeProtocol.AUDIO_STARTED, "response")
                c.audio(VolcRealtimeProtocol.AUDIO_DONE, "response")
                assertTrue(ends.isEmpty())
                c.done("response", status)
                c.close()
                assertEquals(listOf(status == "completed"), ends)
            }
        }
    }

    @Test fun anIdentifiedResponseThatNeverPlaysStillTimesOut() = runBlocking {
        Connection(openAi = true).use { c ->
            val ends = Channel<Boolean>(4)
            val request = c.notice { ends.trySend(it) }
            c.created("silent", request)
            assertEquals(false, withTimeout(3000) { ends.receive() })
            c.audio(VolcRealtimeProtocol.AUDIO_STARTED, "silent")
            c.audio(VolcRealtimeProtocol.AUDIO_DONE, "silent")
            c.done("silent", "completed")
            c.close()
            assertFalse(ends.tryReceive().isSuccess)
        }
    }

    @Test fun completedResponseWithoutAudioFallsBackImmediately() {
        Connection(openAi = true, timeout = 3000).use { c ->
            val ends = mutableListOf<Boolean>()
            val request = c.notice { ends += it }
            c.created("silent", request)
            c.done("silent", "completed")
            assertEquals(listOf(false), ends)
        }
    }

    @Test fun cueUsesTheSameMetadataAndCompletionRules() {
        Connection(openAi = true, timeout = 3000).use { c ->
            val ends = mutableListOf<Boolean>()
            assertTrue(c.session.sendCue("owner answered") { ends += it })
            val id = c.socket.sent.last().getValue("response").jsonObject.getValue("metadata")
                .jsonObject.getValue("mika_notice_id").jsonPrimitive.content
            c.created("reply", id)
            c.audio(VolcRealtimeProtocol.AUDIO_STARTED, "reply")
            c.done("reply", "completed")
            assertEquals(listOf(true), ends)
        }
    }
}
