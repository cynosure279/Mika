package com.wangzan101.mika.media

import android.content.Context
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
import com.wangzan101.mika.core.model.VideoJobStage
import com.wangzan101.mika.core.model.VideoModel
import com.wangzan101.mika.core.model.VideoModelError
import com.wangzan101.mika.core.model.VideoRequest
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * 视频生成能力的手机实现:调 VideoModel 的异步任务,结果边下载边写进缓存,再经工作区闸门写进 out/video;
 * 用 MediaMetadataRetriever 读尺寸/时长并抽一帧当封面。所有对网络/磁盘的操作都在 IO 线程,取消时停下载并删掉缓存与半截文件。
 */
class AndroidVideoGenerationPort(
    private val context: Context,
    private val conversationId: String,
    private val model: VideoModel,
    private val http: OkHttpClient,
    private val onProgress: suspend (VideoJobStage) -> Unit = {},
    private val onGenerated: (com.wangzan101.mika.core.chat.GeneratedVideo) -> Unit = {},
) : VideoGenerationPort {
    override val modelName: String get() = model.name
    override val capabilities: VideoCapabilities get() = model.capabilities
    private val dirs by lazy { SandboxDirs(context) }
    private val workspace: File by lazy { dirs.workspace(com.wangzan101.mika.chat.WorkspaceResolver.resolveFor(context, conversationId)) }

    override suspend fun generate(request: VideoGenerationRequest, onStage: suspend (VideoJobStage) -> Unit): VideoTake {
        val first = request.firstFramePath?.let { firstFrame(it) }
        val output = model.generate(
            VideoRequest(request.prompt, request.durationSec, request.aspect, request.resolution, first?.first, first?.second),
        ) { stage ->
            onStage(stage)
            onProgress(stage)
        }
        val cache = withContext(Dispatchers.IO) {
            File(context.cacheDir, CACHE_DIR).apply { mkdirs() }.let { File(it, "gen-${System.nanoTime()}.mp4") }
        }
        try {
            val bytes = output.bytes
            if (bytes != null) withContext(Dispatchers.IO) { cache.writeBytes(bytes) }
            else download(output.url ?: throw VideoModelError.BadResponse("no video bytes or url"), cache)
            val info = withContext(Dispatchers.IO) { probe(cache) }
            val path = store(cache, baseName(request.prompt))
            onGenerated(
                com.wangzan101.mika.core.chat.GeneratedVideo(
                    path, info.durationMs, info.width, info.height, request.prompt, System.currentTimeMillis(),
                ),
            )
            return VideoTake(path, info.durationMs, info.width, info.height, info.poster)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { cache.delete() }
        }
    }

    /**
     * 首帧图:经闸门读出来。小图原样发;大图(或字节数超过 [FIRST_FRAME_RAW_MAX])缩成长边 [FIRST_FRAME_SIDE] 的 JPEG,
     * 免得请求体里塞几十 MB 的 base64。
     */
    private suspend fun firstFrame(path: String): Pair<ByteArray, String> {
        val rel = relative(path) ?: throw VideoError.Unsupported("first frame must be under ${SandboxPaths.WORKSPACE}")
        val bytes = dirs.tree.shared {
            withContext(Dispatchers.IO) {
                dirs.gate.openRead(workspace, rel).use { g ->
                    if (g.size > FIRST_FRAME_READ_MAX) throw VideoError.TooLarge(g.size)
                    g.read(0, g.size.toInt())
                }
            }
        }
        return withContext(Dispatchers.IO) {
            val (w, h) = runCatching { BitmapImageOps.size(bytes) }.getOrElse { throw VideoError.Unsupported("first frame is not an image Mika can read") }
            if (w <= 0 || h <= 0) throw VideoError.Unsupported("first frame is not an image Mika can read")
            if (bytes.size <= FIRST_FRAME_RAW_MAX && maxOf(w, h) <= FIRST_FRAME_SIDE) bytes to imageMime(path)
            else BitmapImageOps.toJpeg(bytes, FIRST_FRAME_SIDE, 90) to "image/jpeg"
        }
    }

    /**
     * 边下边写;超过 [MAX_BYTES] 就停。下载地址是服务商给的临时签名链接:不带服务商的 Key 和请求头,也不写进日志或报错。
     * 协程一取消就取消这次请求(阻塞中的读随之抛出),不等读完。
     */
    private suspend fun download(url: String, target: File) = coroutineScope {
        val call = http.newCall(Request.Builder().url(url).get().build())
        val cancelOnStop = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            withContext(Dispatchers.IO) {
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) throw ModelError.Server(resp.code, "video download failed")
                        val body = resp.body ?: throw VideoModelError.BadResponse("empty download")
                        if (body.contentLength() > MAX_BYTES) throw VideoError.TooLarge(body.contentLength())
                        body.byteStream().use { input ->
                            target.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_BYTES) throw VideoError.TooLarge(total)
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    ensureActive()
                    throw ModelError.Network(e)
                }
            }
        } finally {
            cancelOnStop.cancel()
        }
    }

    /** 写进 out/video;写到一半失败或被取消时删掉这个半截文件。 */
    private suspend fun store(cache: File, name: String): String = dirs.tree.shared {
        withContext(Dispatchers.IO) {
            val (actual, g) = dirs.gate.createNumbered(workspace, "out/video", name)
            g.use {
                try {
                    cache.inputStream().use { input ->
                        g.output().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    runCatching { g.delete() }
                    throw e
                }
                "${SandboxPaths.WORKSPACE}/out/video/$actual"
            }
        }
    }

    private class Probe(val durationMs: Long?, val width: Int, val height: Int, val poster: ByteArray?)
    private fun probe(file: File): Probe {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val m = VideoProbe.meta(retriever)
            return Probe(m.durationMs, m.width, m.height, VideoProbe.frameJpeg(retriever, 0, POSTER_SIDE))
        } catch (e: Exception) {
            return Probe(null, 0, 0, null)
        } finally {
            runCatching { retriever.release() }
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

    companion object {
        /** 生成结果的下载缓存目录(在 cacheDir 下);启动时清掉上次进程留下的。 */
        const val CACHE_DIR = "video-gen"
        private const val MAX_BYTES = 1024L * 1024 * 1024
        private const val FIRST_FRAME_READ_MAX = 20L * 1024 * 1024
        private const val FIRST_FRAME_RAW_MAX = 4 * 1024 * 1024
        private const val FIRST_FRAME_SIDE = 2048
        private const val POSTER_SIDE = 640
    }
}
