package com.wangzan101.mika.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.cap.video.VideoTake
import com.wangzan101.mika.core.cap.video.VideoError
import com.wangzan101.mika.core.cap.video.VideoGenerationPort
import com.wangzan101.mika.core.cap.video.VideoGenerationRequest
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.VideoCapabilities
import com.wangzan101.mika.core.model.VideoModel
import com.wangzan101.mika.core.model.VideoModelError
import com.wangzan101.mika.core.model.VideoRequest
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * 视频生成能力的手机实现:调 VideoModel 的异步任务,结果下载到缓存,再经工作区闸门写进 out/video;
 * 用 MediaMetadataRetriever 读尺寸/时长并抽一帧当封面。所有对网络/磁盘的操作都在 IO 线程。
 */
class AndroidVideoGenerationPort(
    private val context: Context,
    private val conversationId: String,
    private val model: VideoModel,
    override val capabilities: VideoCapabilities,
    private val http: OkHttpClient,
    private val onProgress: suspend (String) -> Unit = {},
    private val onGenerated: (com.wangzan101.mika.core.chat.GeneratedVideo) -> Unit = {},
) : VideoGenerationPort {
    override val modelName: String get() = model.name
    private val dirs by lazy { SandboxDirs(context) }
    private val workspace: File by lazy { dirs.workspace(conversationId) }

    override suspend fun generate(request: VideoGenerationRequest, onProgress: suspend (String) -> Unit): VideoTake {
        val first = request.firstFramePath?.let { path ->
            val rel = relative(path) ?: throw VideoError.Unsupported("first frame must be under ${SandboxPaths.WORKSPACE}")
            dirs.tree.shared {
                withContext(Dispatchers.IO) {
                    dirs.gate.openRead(workspace, rel).use { g ->
                        if (g.size > 20L * 1024 * 1024) throw VideoError.TooLarge(g.size)
                        g.read(0, g.size.toInt()) to imageMime(path)
                    }
                }
            }
        }
        val output = model.generate(
            VideoRequest(request.prompt, request.durationSec, request.aspect, request.resolution, first?.first, first?.second),
            onProgress,
        )
        val cache = File(context.cacheDir, "video-gen").apply { mkdirs() }
            .let { File(it, "gen-${System.nanoTime()}.mp4") }
        try {
            val bytes = output.bytes
            if (bytes != null) cache.writeBytes(bytes)
            else {
                val url = output.url ?: throw VideoModelError.BadResponse("no video bytes or url")
                download(url, cache)
            }
            val info = probe(cache)
            val name = baseName(request.prompt)
            val path = store(cache, name)
            onGenerated(
                com.wangzan101.mika.core.chat.GeneratedVideo(
                    path, info.durationMs, info.width, info.height, request.prompt, System.currentTimeMillis(),
                ),
            )
            return VideoTake(path, info.durationMs, info.width, info.height, info.poster)
        } finally {
            cache.delete()
        }
    }

    private suspend fun download(url: String, target: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw ModelError.Server(resp.code)
                val body = resp.body ?: throw VideoModelError.BadResponse("empty download")
                if (body.contentLength() > MAX_BYTES) throw VideoError.TooLarge(body.contentLength())
                body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                if (target.length() > MAX_BYTES) throw VideoError.TooLarge(target.length())
            }
        } catch (e: IOException) {
            throw ModelError.Network(e)
        }
    }

    private suspend fun store(cache: File, name: String): String = dirs.tree.shared {
        withContext(Dispatchers.IO) {
            val (actual, g) = dirs.gate.createNumbered(workspace, "out/video", name)
            try {
                cache.inputStream().use { input -> g.output().use { input.copyTo(it) } }
                "${SandboxPaths.WORKSPACE}/out/video/$actual"
            } finally {
                g.close()
            }
        }
    }

    private class Probe(val durationMs: Long?, val width: Int, val height: Int, val poster: ByteArray?)
    private fun probe(file: File): Probe {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val poster = bitmap?.let {
                try { jpeg(it) } finally { it.recycle() }
            }
            return Probe(duration, width, height, poster)
        } catch (e: Exception) {
            return Probe(null, 0, 0, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val maxSide = max(bitmap.width, bitmap.height)
        val scaled = if (maxSide <= 640) bitmap else {
            val f = 640.0 / maxSide
            Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * f).toInt()), max(1, (bitmap.height * f).toInt()), true)
        }
        try {
            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun baseName(prompt: String): String {
        val safe = Attachments.safeName(prompt.lineSequence().firstOrNull().orEmpty().take(24)).ifBlank { "video" }
        return if (safe.substringAfterLast('.', "").isEmpty()) "$safe.mp4" else safe
    }

    private fun imageMime(path: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase())
            ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"

    private fun relative(path: String): String? {
        val prefix = SandboxPaths.WORKSPACE + "/"
        val p = path.trim()
        if (!p.startsWith(prefix)) return null
        val rel = p.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return rel
    }

    private companion object {
        const val MAX_BYTES = 1024L * 1024 * 1024
    }
}
