package com.wangzan101.mika.core.cap.video

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ArkSeedanceModel
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.VideoCapabilities
import com.wangzan101.mika.core.model.VideoModelError
import com.wangzan101.mika.core.model.VideoRequest
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.vision.LocScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGenerationTest {
    private class FakePort : VideoPort {
        override suspend fun info(path: String) = VideoInfo(path, 5_000, 1280, 720, "video/mp4", 1_000_000, true)
        override suspend fun frames(path: String, count: Int) = emptyList<VideoFrame>()
        override suspend fun saveToMovies(path: String, name: String?) = SavedVideo("Movies/Mika/x.mp4", "content://x", 1_000_000)
    }

    private class FakeGeneration(
        override val capabilities: VideoCapabilities = VideoCapabilities(),
        private val fail: Throwable? = null,
        private val failOnlyFirst: Boolean = false,
    ) : VideoGenerationPort {
        var last: VideoGenerationRequest? = null
        var calls = 0
        override val modelName = "fake-video"
        override suspend fun generate(request: VideoGenerationRequest, onProgress: suspend (String) -> Unit): VideoTake {
            calls += 1
            last = request
            onProgress("working")
            if (fail != null && (!failOnlyFirst || calls == 1)) throw fail
            return VideoTake("/workspace/out/video/take.mp4", 5_000, 1280, 720, byteArrayOf(9, 8, 7))
        }
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun dispatcher(generation: VideoGenerationPort?, max: Int = 1, vision: Boolean = true) =
        toolDispatcher(LocScreen(), visionEnabled = vision, video = FakePort(), videoGeneration = generation, videoMaxPerTask = max)

    @Test fun generateRunsThroughThePortAndAttachesThePoster() = runTest {
        val gen = FakeGeneration()
        val r = dispatcher(gen).execute(
            Part.ToolCall("c", "video_generate", """{"prompt":"a cat surfing","durationSec":6,"aspect":"9:16","resolution":"1080p"}"""),
            ActionPass(),
        )
        assertEquals("true", r.content.s("ok"))
        assertEquals("/workspace/out/video/take.mp4", r.content.s("path"))
        assertEquals("fake-video", r.content.s("model"))
        assertEquals(6, gen.last!!.durationSec)
        assertEquals("9:16", gen.last!!.aspect)
        assertEquals("1080p", gen.last!!.resolution)
        assertEquals(1, r.images.size)
    }

    @Test fun posterIsNotAttachedWhenTheModelCannotSee() = runTest {
        val r = dispatcher(FakeGeneration(), vision = false).execute(
            Part.ToolCall("c", "video_generate", """{"prompt":"a cat"}"""), ActionPass(),
        )
        assertEquals("true", r.content.s("ok"))
        assertTrue(r.images.isEmpty())
    }

    @Test fun defaultsComeFromTheCapability() = runTest {
        val gen = FakeGeneration()
        val d = dispatcher(gen)
        d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"a cat"}"""), ActionPass())
        // VideoCapability 的默认值就是工具没给时的 5/16:9/720p
        assertEquals(5, gen.last!!.durationSec)
        assertEquals("16:9", gen.last!!.aspect)
        assertEquals("720p", gen.last!!.resolution)
    }

    @Test fun refusedWhenOverThePerTaskAllowance() = runTest {
        val gen = FakeGeneration()
        val d = dispatcher(gen)
        assertEquals("true", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"one"}"""), ActionPass()).content.s("ok"))
        val second = d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"two"}"""), ActionPass()).content
        assertEquals("task_video_limit_reached", second.s("error"))
        assertEquals(1, gen.calls)
    }

    @Test fun aFailedJobRefundsTheAllowance() = runTest {
        val gen = FakeGeneration(fail = VideoModelError.Failed("service said no"), failOnlyFirst = true)
        val d = dispatcher(gen)
        val first = d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"one"}"""), ActionPass()).content
        assertEquals("video_generation_failed", first.s("error"))
        // 失败不占额度:同一个任务还能再试一次
        assertEquals("true", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"two"}"""), ActionPass()).content.s("ok"))
    }

    @Test fun unsupportedArgumentsAreRejectedBeforeCallingTheService() = runTest {
        val gen = FakeGeneration(VideoCapabilities(durations = 4..10, aspects = setOf("16:9"), resolutions = setOf("720p")))
        val d = dispatcher(gen)
        assertEquals("invalid_arguments", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"x","durationSec":30}"""), ActionPass()).content.s("error"))
        assertEquals("invalid_arguments", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"x","aspect":"1:1"}"""), ActionPass()).content.s("error"))
        assertEquals(0, gen.calls)
    }

    @Test fun noGenerationPortMeansNoTool() = runTest {
        val d = dispatcher(null)
        assertFalse("video_generate" in d.definitions().map { it.name })
        assertEquals("unknown_tool", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"x"}"""), ActionPass()).content.s("error"))
    }

    // ---- 火山方舟 Seedance 适配器 ----

    private fun ark(server: MockWebServer, capabilities: VideoCapabilities = VideoCapabilities()) = ArkSeedanceModel(
        server.url("/api/v3").toString().trimEnd('/'),
        "test-key",
        "doubao-seedance-1-0-pro-250528",
        capabilities,
        OkHttpClient(),
        pollMs = 1,
        timeoutMs = 5_000,
    )

    @Test fun arkSubmitsPollsAndReturnsTheDownloadUrl() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"job-1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"running"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"succeeded","content":{"video_url":"http://cdn/v.mp4"}}"""))
        val progress = mutableListOf<String>()
        val out = ark(server).generate(VideoRequest("a cat surfing", 5, "16:9", "720p")) { progress += it }
        assertEquals("http://cdn/v.mp4", out.url)
        assertEquals("job-1", out.jobId)
        assertTrue(progress.isNotEmpty())

        val submit = server.takeRequest()
        assertEquals("/api/v3/contents/generations/tasks", submit.path)
        assertEquals("Bearer test-key", submit.getHeader("Authorization"))
        val body = Json.parseToJsonElement(submit.body.readUtf8()).jsonObject
        assertEquals("doubao-seedance-1-0-pro-250528", body["model"]!!.jsonPrimitive.content)
        assertEquals("5", body["duration"]!!.jsonPrimitive.content)
        assertEquals("16:9", body["ratio"]!!.jsonPrimitive.content)
        assertEquals("720p", body["resolution"]!!.jsonPrimitive.content)
        val content = body["content"]!!.jsonArray
        assertEquals("a cat surfing", content[0].jsonObject["text"]!!.jsonPrimitive.content)

        val poll = server.takeRequest()
        assertEquals("/api/v3/contents/generations/tasks/job-1", poll.path)
        server.shutdown()
    }

    @Test fun arkSendsTheFirstFrameAsADataUrl() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"job-2"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"succeeded","content":{"video_url":"http://cdn/v.mp4"}}"""))
        ark(server).generate(VideoRequest("open", 4, "1:1", "480p", byteArrayOf(1, 2), "image/png"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("data:image/png;base64,"))
        assertTrue(body.contains("first_frame"))
        server.shutdown()
    }

    @Test fun arkMapsFailedJobsToVideoModelError() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"job-3"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"failed","error":{"message":"content blocked"}}"""))
        val e = runCatching { ark(server).generate(VideoRequest("x", 5, "16:9", "720p")) }.exceptionOrNull()
        assertTrue(e is VideoModelError.Failed)
        assertEquals("content blocked", e!!.message)
        server.shutdown()
    }

    @Test fun arkRejectsUnsupportedRequestsBeforeSending() = runTest {
        val server = MockWebServer()
        val e = runCatching {
            ark(server, VideoCapabilities(durations = 4..10)).generate(VideoRequest("x", 30, "16:9", "720p"))
        }.exceptionOrNull()
        assertTrue(e is VideoModelError.Unsupported)
        assertEquals(0, server.requestCount)
        server.shutdown()
    }
}
