package com.wangzan101.mika.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.cap.video.SavedVideo
import com.wangzan101.mika.core.cap.video.VideoError
import com.wangzan101.mika.core.cap.video.VideoFrame
import com.wangzan101.mika.core.cap.video.VideoInfo
import com.wangzan101.mika.core.cap.video.VideoPort
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 视频能力的手机实现:读工作区视频的元数据/抽帧用 MediaMetadataRetriever,存影片库走 [VideoExporter]。
 * 为了避免「检查完再按路径用」被沙箱里换掉,先经工作区闸门复制到缓存,再在缓存文件上解码。
 */
class AndroidVideoPort(
    private val context: Context,
    private val conversationId: String,
) : VideoPort {
    private val dirs by lazy { SandboxDirs(context) }
    private val workspace: File by lazy { dirs.workspace(conversationId) }

    override suspend fun info(path: String): VideoInfo {
        val rel = relative(path) ?: throw VideoError.Unsupported("the path must be under ${SandboxPaths.WORKSPACE}")
        val file = cacheCopy(rel)
        return withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (w, h) = if (rotation == 90 || rotation == 270) height to width else width to height
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            VideoInfo(path, duration, w, h, mimeOf(path.substringAfterLast('.', "")), file.length(), hasAudio)
        } catch (e: Exception) {
            throw VideoError.BadVideo(e.message ?: "cannot read this video")
        } finally {
            runCatching { retriever.release() }
            file.delete()
        }
        }
    }

    override suspend fun frames(path: String, count: Int): List<VideoFrame> {
        val rel = relative(path) ?: throw VideoError.Unsupported("the path must be under ${SandboxPaths.WORKSPACE}")
        val file = cacheCopy(rel)
        return withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val times = if (count <= 1) listOf(duration / 2) else List(count) { i -> duration * i / (count - 1) }
            val out = mutableListOf<VideoFrame>()
            for (atMs in times) {
                val bitmap = retriever.getFrameAtTime(atMs.coerceAtLeast(0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                try {
                    out += VideoFrame(atMs, jpeg(bitmap))
                } finally {
                    bitmap.recycle()
                }
            }
            out
        } catch (e: Exception) {
            throw VideoError.BadVideo(e.message ?: "cannot decode this video")
        } finally {
            runCatching { retriever.release() }
            file.delete()
        }
        }
    }

    override suspend fun saveToMovies(path: String, name: String?): SavedVideo {
        val rel = relative(path) ?: throw VideoError.Unsupported("the path must be under ${SandboxPaths.WORKSPACE}")
        return dirs.tree.shared {
            withContext(Dispatchers.IO) {
                dirs.gate.openRead(workspace, rel).use { g ->
                    if (g.size > MAX_BYTES) throw VideoError.TooLarge(g.size)
                    val mime = mimeOf(path.substringAfterLast('.', ""))
                    VideoExporter(context).save(g.input(), mime, name ?: path.substringAfterLast('/'))
                }
            }
        }
    }

    /** 经工作区闸门复制到缓存,返回临时文件;调用方负责删。 */
    private suspend fun cacheCopy(rel: String): File = dirs.tree.shared {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "video").apply { mkdirs() }
            val file = File(dir, "clip-${System.nanoTime()}.${rel.substringAfterLast('.', "mp4")}")
            try {
                dirs.gate.openRead(workspace, rel).use { g ->
                    if (g.size > MAX_BYTES) throw VideoError.TooLarge(g.size)
                    g.input().use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                file
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val maxSide = max(bitmap.width, bitmap.height)
        val scaled = if (maxSide <= 1280) bitmap else {
            val f = 1280.0 / maxSide
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

    private fun relative(path: String): String? {
        val prefix = SandboxPaths.WORKSPACE + "/"
        val p = path.trim()
        if (!p.startsWith(prefix)) return null
        val rel = p.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return rel
    }

    private fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "m4v" -> "video/x-m4v"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())?.takeIf { it.startsWith("video/") } ?: "video/mp4"
    }

    private companion object {
        const val MAX_BYTES = 500L * 1024 * 1024
    }
}
