package com.wangzan101.mika.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 分享前把工作区里的一个文件(图片、视频)复制到「下载/Mika/share」:MediaStore 的 content URI 可以被对方 App 读取,
 * 不需要 FileProvider,也不需要存储权限。复制出来的是独立的一份,任务结束、工作区被清理后对方仍能读。
 */
class ShareFileExporter(private val context: Context) {
    suspend fun save(bytes: ByteArray, mime: String, name: String): ShareableFile =
        ByteArrayInputStream(bytes).use { save(it, mime, name) }

    /** 边读边写,不把整个文件读进内存(视频可以有几百 MB);协程取消时删掉写了一半的副本。 */
    suspend fun save(input: InputStream, mime: String, name: String): ShareableFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val display = if (name.substringAfterLast('.', "").isEmpty()) "$name.${extensionOf(mime)}" else name
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, display)
            put(MediaStore.Downloads.RELATIVE_PATH, "$DIRECTORY/")
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            } ?: throw IllegalStateException("MediaStore openOutputStream failed")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        ShareableFile(uri, mime)
    }

    /** 删掉上次进程留下的、没写完的分享副本;启动时调用。 */
    fun cleanPending() {
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$DIRECTORY/%", context.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val ids = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }.orEmpty()
        for (id in ids) runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
    }

    private fun extensionOf(mime: String): String = shareExtension(mime)

    private companion object {
        const val DIRECTORY = "Download/Mika/share"
    }
}

/** 没有扩展名的分享文件按 MIME 补一个;认不出的图片按 png、视频按 mp4。 */
internal fun shareExtension(mime: String): String {
    val type = mime.substringBefore('/').lowercase()
    return when (mime.substringAfter('/', "").lowercase()) {
        "jpeg", "jpg" -> "jpg"
        "webp" -> "webp"
        "gif" -> "gif"
        "png" -> "png"
        "webm" -> "webm"
        "quicktime" -> "mov"
        "x-matroska" -> "mkv"
        "3gpp" -> "3gp"
        "mp4" -> "mp4"
        else -> if (type == "video") "mp4" else "png"
    }
}
