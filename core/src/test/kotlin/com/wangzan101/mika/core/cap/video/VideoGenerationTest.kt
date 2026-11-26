package com.wangzan101.mika.core.cap.video

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ArkSeedance
import com.wangzan101.mika.core.model.ArkSeedanceModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.VideoCapabilities
import com.wangzan101.mika.core.model.VideoJobStage
import com.wangzan101.mika.core.model.VideoModelError
import com.wangzan101.mika.core.model.VideoRequest
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.screen.vision.LocScreen
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
        override val capabilities: VideoCapabilities = VideoCapabilities(durations = 4..30, resolutions = setOf("480p", "720p", "1080p")),
        private val fail: Throwable? = null,
        private val failOnlyFirst: Boolean = false,
        /** 失败前先报一次「已提交」:模拟服务商已收下任务之后才出错。 */
        private val failAfterSubmit: Boolean = false,
    ) : VideoGenerationPort {
        var last: VideoGenerationRequest? = null
        var calls = 0
        override val modelName = "fake-video"
        override suspend fun generate(request: VideoGenerationRequest, onStage: suspend (VideoJobStage) -> Unit): VideoTake {
            calls += 1
            last = request
            val failing = fail != null && (!failOnlyFirst || calls == 1)
            if (!failing || failAfterSubmit) onStage(VideoJobStage.Submitted)
            if (failing) throw fail!!
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

    @Test fun settingsDefaultsOutsideTheModelRangeFallBackToWhatItSupports() = runTest {
        val gen = FakeGeneration(VideoCapabilities(durations = 4..10, aspects = setOf("9:16"), resolutions = setOf("720p")))
        val d = toolDispatcher(LocScreen(), visionEnabled = true, video = FakePort(), videoGeneration = gen,
            videoDurationSec = 60, videoAspect = "16:9", videoResolution = "4k")
        assertEquals("true", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"a cat"}"""), ActionPass()).content.s("ok"))
        assertEquals(10, gen.last!!.durationSec)
        assertEquals("9:16", gen.last!!.aspect)
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

    private fun ark(
        server: MockWebServer,
        capabilities: VideoCapabilities = ArkSeedance.capabilities(MODEL),
        timeoutMs: Long = 5_000,
    ) = ArkSeedanceModel(
        server.url("/api/v3").toString().trimEnd('/'),
        "test-key",
        MODEL,
        capabilities,
        OkHttpClient(),
        pollMs = 1,
        timeoutMs = timeoutMs,
    )

    @Test fun arkSubmitsPollsAndReturnsTheDownloadUrl() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"job-1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"running"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"succeeded","content":{"video_url":"http://cdn/v.mp4"}}"""))
        val progress = mutableListOf<VideoJobStage>()
        val out = ark(server).generate(VideoRequest("a cat surfing", 5, "16:9", "720p")) { progress += it }
        assertEquals("http://cdn/v.mp4", out.url)
        assertEquals("job-1", out.jobId)
        assertEquals(listOf(VideoJobStage.Submitted, VideoJobStage.Running), progress)

        val submit = server.takeRequest()
        assertEquals("/api/v3/contents/generations/tasks", submit.path)
        assertEquals("Bearer test-key", submit.getHeader("Authorization"))
        val body = Json.parseToJsonElement(submit.body.readUtf8()).jsonObject
        assertEquals(MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals("5", body["duration"]!!.jsonPrimitive.content)
        assertEquals("16:9", body["ratio"]!!.jsonPrimitive.content)
        assertEquals("720p", body["resolution"]!!.jsonPrimitive.content)
        val content = body["content"]!!.jsonArray
        assertEquals("a cat surfing", content[0].jsonObject["text"]!!.jsonPrimitive.content)

        val poll = server.takeRequest()
        assertEquals("/api/v3/contents/generations/tasks/job-1", poll.path)
        server.shutdown()
    }

    @Test fun arkSendsTheFirstFrameAsADataUrl() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"job-2"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"succeeded","content":{"video_url":"http://cdn/v.mp4"}}"""))
        ark(server).generate(VideoRequest("open", 4, "1:1", "480p", byteArrayOf(1, 2), "image/png"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("data:image/png;base64,"))
        assertTrue(body.contains("first_frame"))
        server.shutdown()
    }

    @Test fun arkMapsFailedJobsToVideoModelError() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"job-3"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"failed","error":{"message":"content blocked"}}"""))
        val e = runCatching { ark(server).generate(VideoRequest("x", 5, "16:9", "720p")) }.exceptionOrNull()
        assertTrue(e is VideoModelError.Failed)
        assertEquals("content blocked", e!!.message)
        server.shutdown()
    }

    @Test fun arkRejectsUnsupportedRequestsBeforeSending() = runBlocking {
        val server = MockWebServer()
        val e = runCatching {
            ark(server, VideoCapabilities(durations = 4..10)).generate(VideoRequest("x", 30, "16:9", "720p"))
        }.exceptionOrNull()
        assertTrue(e is VideoModelError.Unsupported)
        assertEquals(0, server.requestCount)
        server.shutdown()
    }

    @Test fun arkTreatsACancelledJobAsFailedInsteadOfPollingUntilTimeout() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"job-4"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"cancelled"}"""))
        val e = runCatching { ark(server).generate(VideoRequest("x", 5, "16:9", "720p")) }.exceptionOrNull()
        assertTrue(e is VideoModelError.Failed)
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test fun arkCancelsTheRemoteJobWhenTheTaskIsCancelled() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"job-5"}"""))
        repeat(50) { server.enqueue(MockResponse().setBody("""{"status":"queued"}""")) }
        val model = ArkSeedanceModel(server.url("/api/v3").toString().trimEnd('/'), "k", "m", ArkSeedance.COMMON, OkHttpClient(),
            pollMs = 20, timeoutMs = 60_000)
        val stages = mutableListOf<VideoJobStage>()
        val job = launch { model.generate(VideoRequest("x", 5, "16:9", "720p")) { stages += it } }
        withTimeout(5_000) { while (VideoJobStage.Queued !in stages) delay(5) }
        job.cancelAndJoin()
        val methods = List(server.requestCount) { server.takeRequest() }.map { it.method to it.path }
        assertEquals("DELETE" to "/api/v3/contents/generations/tasks/job-5", methods.last())
        server.shutdown()
    }

    @Test fun aJobThatTimesOutAfterSubmissionKeepsItsAllowance() = runTest {
        // 服务商已收下任务(可能在计费),之后超时:不退额度,模型不能接着再建一个
        val gen = FakeGeneration(fail = VideoModelError.Timeout("slow"), failAfterSubmit = true)
        val d = dispatcher(gen)
        assertEquals("video_timeout", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"one"}"""), ActionPass()).content.s("error"))
        assertEquals("task_video_limit_reached", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"two"}"""), ActionPass()).content.s("error"))
        assertEquals(1, gen.calls)
    }

    @Test fun anUnconfirmedSubmitKeepsItsAllowanceAndSaysNotToRetry() = runTest {
        val gen = FakeGeneration(fail = VideoModelError.Unconfirmed(java.io.IOException("reset")))
        val d = dispatcher(gen)
        val first = d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"one"}"""), ActionPass()).content
        assertEquals("video_submit_unconfirmed", first.s("error"))
        assertEquals("false", first.s("recoverable"))
        assertEquals("task_video_limit_reached", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"two"}"""), ActionPass()).content.s("error"))
    }

    @Test fun aFailureBeforeSubmissionRefundsTheAllowance() = runTest {
        val gen = FakeGeneration(fail = ModelError.Network(java.io.IOException("offline")), failOnlyFirst = true)
        val d = dispatcher(gen)
        assertEquals("video_network_failed", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"one"}"""), ActionPass()).content.s("error"))
        assertEquals("true", d.execute(Part.ToolCall("c", "video_generate", """{"prompt":"two"}"""), ActionPass()).content.s("ok"))
    }

    @Test fun aClientErrorPassesTheServiceDetailThrough() = runTest {
        val gen = FakeGeneration(fail = ModelError.Server(400, "InvalidParameter: ratio"))
        val r = dispatcher(gen).execute(Part.ToolCall("c", "video_generate", """{"prompt":"one"}"""), ActionPass()).content
        assertEquals("video_request_rejected", r.s("error"))
        assertTrue(r.s("message").contains("InvalidParameter: ratio"))
    }

    @Test fun arkRequestBodyMatchesTheDocumentedShape() = runBlocking {
        val model = ArkSeedanceModel("https://ark.cn-beijing.volces.com/api/v3", "k", MODEL, ArkSeedance.capabilities(MODEL), OkHttpClient())
        val body = model.submitBody(VideoRequest("a cat", 8, "21:9", "1080p"))
        // 参数放在 body 顶层(文档推荐的强校验方式),不拼进提示词
        assertEquals(setOf("model", "content", "ratio", "duration", "resolution", "generate_audio", "execution_expires_after"), body.keys)
        assertEquals("21:9", body["ratio"]!!.jsonPrimitive.content)
        assertEquals("8", body["duration"]!!.jsonPrimitive.content)
        assertEquals("1080p", body["resolution"]!!.jsonPrimitive.content)
        assertEquals("true", body["generate_audio"]!!.jsonPrimitive.content)
        assertEquals("3600", body["execution_expires_after"]!!.jsonPrimitive.content)
        val text = body["content"]!!.jsonArray.single().jsonObject
        assertEquals("text", text["type"]!!.jsonPrimitive.content)
        assertEquals("a cat", text["text"]!!.jsonPrimitive.content)
        assertFalse(text["text"]!!.jsonPrimitive.content.contains("--"))
    }

    @Test fun arkSendsAdaptiveRatioWhenTheModelKeepsTheFirstFramesAspect() = runBlocking {
        val v25 = "doubao-seedance-2-5-260628"
        val model = ArkSeedanceModel("https://ark.cn-beijing.volces.com/api/v3", "k", v25, ArkSeedance.capabilities(v25), OkHttpClient())
        val body = model.submitBody(VideoRequest("go", 5, "16:9", "720p", byteArrayOf(1), "IMAGE/PNG"))
        assertEquals("adaptive", body["ratio"]!!.jsonPrimitive.content)
        val image = body["content"]!!.jsonArray[1].jsonObject
        assertEquals("image_url", image["type"]!!.jsonPrimitive.content)
        assertEquals("first_frame", image["role"]!!.jsonPrimitive.content)
        // 文档要求 data:image/<格式> 里的格式小写
        assertTrue(image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))
        // 不带首帧时照常发选的比例
        assertEquals("16:9", model.submitBody(VideoRequest("go", 5, "16:9", "720p"))["ratio"]!!.jsonPrimitive.content)
    }

    @Test fun arkOmitsTheAudioFlagForModelsItDoesNotRecognise() = runBlocking {
        val model = ArkSeedanceModel("https://ark.cn-beijing.volces.com/api/v3", "k", "ep-20261126-abc", ArkSeedance.capabilities("ep-20261126-abc"), OkHttpClient())
        assertFalse("generate_audio" in model.submitBody(VideoRequest("go", 5, "16:9", "720p")).keys)
    }

    @Test fun arkUsesTheReportedDuration() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"cgt-1"}"""))
        server.enqueue(MockResponse().setBody(
            """{"id":"cgt-1","model":"$MODEL","status":"succeeded","content":{"video_url":"https://cdn/v.mp4"},"duration":11,"ratio":"16:9","resolution":"720p","framespersecond":24}""",
        ))
        val out = ark(server).generate(VideoRequest("x", 5, "16:9", "720p"))
        assertEquals(11_000L, out.durationMs)
        server.shutdown()
    }

    @Test fun arkRidesOutTransientPollErrorsInsteadOfDroppingAPaidJob() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"cgt-2"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("""{"status":"succeeded","content":{"video_url":"https://cdn/v.mp4"}}"""))
        assertEquals("https://cdn/v.mp4", ark(server).generate(VideoRequest("x", 5, "16:9", "720p")).url)
        assertEquals(4, server.requestCount)
        server.shutdown()
    }

    @Test fun arkGivesUpOnAuthErrorsWhilePollingAndCancelsTheJob() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"cgt-3"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("{}"))
        val e = runCatching { ark(server).generate(VideoRequest("x", 5, "16:9", "720p")) }.exceptionOrNull()
        assertTrue(e is ModelError.Auth)
        val methods = List(server.requestCount) { server.takeRequest().method }
        assertEquals(listOf("POST", "GET", "DELETE"), methods)
        server.shutdown()
    }

    @Test fun arkTimesOutLocallyAndTriesToCancelTheQueuedJob() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"cgt-4"}"""))
        repeat(400) { server.enqueue(MockResponse().setBody("""{"status":"queued"}""")) }
        val e = runCatching { ark(server, timeoutMs = 150).generate(VideoRequest("x", 5, "16:9", "720p")) }.exceptionOrNull()
        assertTrue(e is VideoModelError.Timeout)
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals("DELETE" to "/api/v3/contents/generations/tasks/cgt-4", requests.last().method to requests.last().path)
        server.shutdown()
    }

    @Test fun arkDoesNotCancelAJobTheServiceAlreadyEnded() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"cgt-5"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"expired"}"""))
        val e = runCatching { ark(server).generate(VideoRequest("x", 5, "16:9", "720p")) }.exceptionOrNull()
        assertTrue(e is VideoModelError.Timeout)
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test fun arkReportsTheErrorCodeOfAFailedJob() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"id":"cgt-6"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"failed","error":{"code":"OutputVideoSensitiveContentDetected","message":"blocked"}}"""))
        val e = runCatching { ark(server).generate(VideoRequest("x", 5, "16:9", "720p")) }.exceptionOrNull()
        assertEquals("OutputVideoSensitiveContentDetected: blocked", e!!.message)
        server.shutdown()
    }

    @Test fun arkNeverResendsASubmitWhoseConnectionDropped() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"id":"duplicate"}"""))
        val e = runCatching { ark(server).generate(VideoRequest("x", 5, "16:9", "720p")) }.exceptionOrNull()
        assertTrue(e is VideoModelError.Unconfirmed)
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test fun arkMapsSubmitRejectionsThroughModelHttp() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"InvalidParameter","message":"ratio is invalid"}}"""))
        val e = runCatching { ark(server).generate(VideoRequest("x", 5, "16:9", "720p")) }.exceptionOrNull()
        assertTrue(e is ModelError.Server)
        assertEquals(400, (e as ModelError.Server).status)
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test fun arkKeepsTheKeyOutOfErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val e = runCatching {
            ArkSeedanceModel(server.url("/api/v3").toString(), "sk-secret-123", MODEL, ArkSeedance.capabilities(MODEL), OkHttpClient(), pollMs = 1)
                .generate(VideoRequest("x", 5, "16:9", "720p"))
        }.exceptionOrNull()!!
        assertFalse(generateSequence(e) { it.cause }.any { it.message.orEmpty().contains("sk-secret-123") })
        server.shutdown()
    }

    private companion object {
        const val MODEL = "doubao-seedance-2-0-260128"
    }
}
