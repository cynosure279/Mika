package com.wangzan101.mika.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import com.wangzan101.mika.core.cap.video.SavedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream

/** 把一段视频存到手机的「影片/Mika」(MediaStore.Video,不需要存储权限)。 */
class VideoExporter(private val context: Context) {
    suspend fun save(input: InputStream, mime: String, name: String): SavedVideo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val display = if (name.substringAfterLast('.', "").isEmpty()) "$name.${extensionOf(mime)}" else name
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, display)
            put(MediaStore.Video.Media.RELATIVE_PATH, "$DIRECTORY/")
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        var size = 0L
        try {
            resolver.openOutputStream(uri)?.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    out.write(buf, 0, n)
                }
            } ?: throw IllegalStateException("MediaStore openOutputStream failed")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val actual = displayName(resolver, uri) ?: display
        SavedVideo("$DIRECTORY/$actual", uri.toString(), size)
    }

    /** 删掉上次进程留下的、没写完的影片库条目;启动时调用。 */
    fun cleanPending() {
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$DIRECTORY/%", context.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val ids = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }.orEmpty()
        for (id in ids) runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), null, null) }
    }

    private fun displayName(resolver: ContentResolver, uri: android.net.Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun extensionOf(mime: String): String = when (mime.substringAfter('/', "").lowercase()) {
        "webm" -> "webm"
        "quicktime" -> "mov"
        "x-matroska" -> "mkv"
        "3gpp" -> "3gp"
        else -> "mp4"
    }

    private companion object {
        const val DIRECTORY = "Movies/Mika"
    }
}
