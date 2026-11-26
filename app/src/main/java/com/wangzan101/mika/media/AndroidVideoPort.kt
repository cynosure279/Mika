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
import com.wangzan101.mika.core.workspace.GatedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频能力的手机实现:读工作区视频的元数据/抽帧用 MediaMetadataRetriever([VideoProbe]),存影片库走 [VideoExporter]。
 * 为了避免「检查完再按路径用」被沙箱里换掉,解码直接读工作区闸门核实过的那个句柄;不复制到缓存,也不整段读进内存。
 */
class AndroidVideoPort(
    private val context: Context,
    private val conversationId: String,
) : VideoPort {
    private val dirs by lazy { SandboxDirs(context) }
    private val workspace: File by lazy { dirs.workspace(com.wangzan101.mika.chat.WorkspaceResolver.resolveFor(context, conversationId)) }

    override suspend fun info(path: String): VideoInfo = probe(path) { g, r ->
        val m = VideoProbe.meta(r)
        VideoInfo(path, m.durationMs, m.width, m.height, mimeOf(path.substringAfterLast('.', "")), g.size, m.hasAudio)
    }

    override suspend fun frames(path: String, count: Int): List<VideoFrame> {
        val job = currentCoroutineContext()[Job]
        return probe(path) { _, r ->
            val duration = VideoProbe.meta(r).durationMs ?: 0L
            VideoProbe.sampleTimes(duration, count).mapNotNull { atMs ->
                job?.ensureActive()
                VideoProbe.frameJpeg(r, atMs, FRAME_SIDE)?.let { VideoFrame(atMs, it) }
            }
        }
    }

    /** 聊天卡片的封面:开头附近一帧,长边 [maxSide] 以内;读不出来为 null。 */
    suspend fun poster(path: String, maxSide: Int): Bitmap? =
        runCatching { probe(path) { _, r -> VideoProbe.frameBitmap(r, 0, maxSide) } }
            .getOrElse { if (it is CancellationException) throw it else null }

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

    /** 经工作区闸门打开 [path],在同一个句柄上解码。 */
    private suspend fun <T> probe(path: String, block: (GatedFile, MediaMetadataRetriever) -> T): T {
        val rel = relative(path) ?: throw VideoError.Unsupported("the path must be under ${SandboxPaths.WORKSPACE}")
        return dirs.tree.shared {
            withContext(Dispatchers.IO) {
                dirs.gate.openRead(workspace, rel).use { g ->
                    try {
                        VideoProbe.open(g) { r -> block(g, r) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: VideoError) {
                        throw e
                    } catch (e: Exception) {
                        throw VideoError.BadVideo(e.message ?: "cannot decode this video")
                    }
                }
            }
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

    companion object {
        /** 存、分享一段工作区视频的上限。 */
        const val MAX_BYTES = 1024L * 1024 * 1024
        /** 给模型看的帧长边。 */
        private const val FRAME_SIDE = 1280
    }
}
