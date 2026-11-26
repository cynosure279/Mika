package com.wangzan101.mika.media

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.wangzan101.mika.core.workspace.GatedFile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * 用 MediaMetadataRetriever 读视频的元数据和帧。数据直接从工作区闸门核实过的句柄按需读([GatedSource]),
 * 不先把整段视频复制到缓存,也不读进内存;调用方在闸门的 shared 区里用完再关句柄。
 */
internal object VideoProbe {
    class Meta(val durationMs: Long?, val width: Int, val height: Int, val hasAudio: Boolean)

    /** 在 [g] 上开一个 retriever,用完释放;解不开时抛出 retriever 的异常。 */
    fun <T> open(g: GatedFile, block: (MediaMetadataRetriever) -> T): T {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(GatedSource(g))
            return block(retriever)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 时长、显示方向上的宽高(已按旋转角交换)、有没有音轨。 */
    fun meta(r: MediaMetadataRetriever): Meta {
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (w, h) = if (rotation == 90 || rotation == 270) height to width else width to height
        val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        return Meta(duration, w, h, hasAudio)
    }

    /** [atMs] 附近的关键帧,长边缩到 [maxSide] 以内的 JPEG;取不到为 null。 */
    fun frameJpeg(r: MediaMetadataRetriever, atMs: Long, maxSide: Int): ByteArray? {
        val bitmap = r.getFrameAtTime(atMs.coerceAtLeast(0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        return try { jpeg(bitmap, maxSide) } finally { bitmap.recycle() }
    }

    /** [atMs] 附近的关键帧,长边缩到 [maxSide] 以内;取不到为 null。调用方负责 recycle。 */
    fun frameBitmap(r: MediaMetadataRetriever, atMs: Long, maxSide: Int): Bitmap? {
        val bitmap = r.getFrameAtTime(atMs.coerceAtLeast(0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scaled = scaled(bitmap, maxSide)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** 在 0..[durationMs] 上均匀取 [count] 个时间点;一帧时取中间。 */
    fun sampleTimes(durationMs: Long, count: Int): List<Long> =
        if (count <= 1) listOf(durationMs.coerceAtLeast(0) / 2)
        else List(count) { i -> durationMs.coerceAtLeast(0) * i / (count - 1) }

    private fun jpeg(bitmap: Bitmap, maxSide: Int): ByteArray {
        val scaled = scaled(bitmap, maxSide)
        try {
            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun scaled(bitmap: Bitmap, maxSide: Int): Bitmap {
        val side = max(bitmap.width, bitmap.height)
        if (side <= maxSide) return bitmap
        val f = maxSide.toDouble() / side
        return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * f).toInt()), max(1, (bitmap.height * f).toInt()), true)
    }

    /** 按位置从闸门句柄读;关掉 retriever 不关句柄(句柄由调用方的 use 关)。 */
    private class GatedSource(private val g: GatedFile) : MediaDataSource() {
        override fun getSize(): Long = g.size

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position >= g.size) return -1
            val n = g.channel().read(ByteBuffer.wrap(buffer, offset, size), position)
            return if (n <= 0) -1 else n
        }

        override fun close() = Unit
    }
}
