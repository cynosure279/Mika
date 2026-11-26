package com.wangzan101.mika.core.cap.video

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.screen.vision.LocScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoToolsTest {
    private class FakePort : VideoPort {
        var savedPath: String? = null
        var savedName: String? = null
        override suspend fun info(path: String) = VideoInfo(path, 12_000, 1080, 1920, "video/mp4", 1_000_000, true)
        override suspend fun frames(path: String, count: Int) = List(count) { i -> VideoFrame(i * 1000L, byteArrayOf(1, 2, 3)) }
        override suspend fun saveToMovies(path: String, name: String?): SavedVideo {
            savedPath = path
            savedName = name
            return SavedVideo("Movies/Mika/clip.mp4", "content://mika/video", 1_000_000)
        }
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun dispatcher(port: VideoPort? = FakePort(), vision: Boolean = true) =
        toolDispatcher(LocScreen(), visionEnabled = vision, video = port)

    @Test fun framesAreAttachedToTheModel() = runTest {
        val d = dispatcher()
        val r = d.execute(Part.ToolCall("c", "video_frames", """{"path":"/workspace/inbox/clip.mp4","count":3}"""), ActionPass())
        assertEquals("true", r.content.s("ok"))
        assertEquals(3, r.content["frames"]!!.jsonArray.size)
        assertEquals(3, r.images.size)
        assertEquals("12000", r.content.s("durationMs"))
        assertEquals("true", r.content.s("hasAudio"))
    }

    @Test fun framesAreNotAttachedWhenTheModelCannotSee() = runTest {
        val r = dispatcher(vision = false).execute(Part.ToolCall("c", "video_frames", """{"path":"/workspace/inbox/clip.mp4","count":2}"""), ActionPass())
        assertEquals("false", r.content.s("vision"))
        assertTrue(r.images.isEmpty())
    }

    @Test fun infoDoesNotAttachFrames() = runTest {
        val r = dispatcher().execute(Part.ToolCall("c", "video_info", """{"path":"/workspace/inbox/clip.mp4"}"""), ActionPass())
        assertEquals("true", r.content.s("ok"))
        assertEquals("1920", r.content.s("height"))
        assertTrue(r.images.isEmpty())
    }

    @Test fun saveAsksByDefaultAndWritesThroughThePort() = runTest {
        val port = FakePort()
        val d = dispatcher(port)
        val risk = d.approvalRisk("video_save", JsonObject(mapOf("path" to JsonPrimitive("/workspace/out/video/clip.mp4"))))
        assertTrue(risk?.ask != null)
        val r = d.execute(Part.ToolCall("c", "video_save", """{"path":"/workspace/out/video/clip.mp4","name":"clip.mp4"}"""), ActionPass()).content
        assertEquals("true", r.s("ok"))
        assertEquals("Movies/Mika/clip.mp4", r.s("savedAs"))
        assertEquals("/workspace/out/video/clip.mp4", port.savedPath)
        assertEquals("clip.mp4", port.savedName)
    }

    @Test fun noPortMeansNoTools() = runTest {
        val d = dispatcher(port = null)
        assertFalse("video_frames" in d.definitions().map { it.name })
        assertEquals("unknown_tool", d.execute(Part.ToolCall("c", "video_frames", """{"path":"/workspace/inbox/a.mp4"}"""), ActionPass()).content.s("error"))
    }

    @Test fun rejectsPathsOutsideWorkspace() = runTest {
        val r = dispatcher().execute(Part.ToolCall("c", "video_info", """{"path":"/sdcard/a.mp4"}"""), ActionPass()).content
        assertEquals("invalid_arguments", r.s("error"))
    }

    @Test fun emptyFramesIsAnError() = runTest {
        val empty = object : VideoPort {
            override suspend fun info(path: String) = VideoInfo(path, 1, 1, 1, "video/mp4", 1, false)
            override suspend fun frames(path: String, count: Int) = emptyList<VideoFrame>()
            override suspend fun saveToMovies(path: String, name: String?) = SavedVideo("x", "y", 0)
        }
        val r = dispatcher(empty).execute(Part.ToolCall("c", "video_frames", """{"path":"/workspace/inbox/a.mp4"}"""), ActionPass()).content
        assertEquals("video_bad_media", r.s("error"))
    }
}
