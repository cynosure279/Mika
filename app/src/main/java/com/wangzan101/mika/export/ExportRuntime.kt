package com.wangzan101.mika.export

import android.content.Context
import android.net.Uri
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.export.DirectoryExportSink
import com.wangzan101.mika.core.export.ExportBlock
import com.wangzan101.mika.core.export.ExportContent
import com.wangzan101.mika.core.export.ExportDocument
import com.wangzan101.mika.core.export.ExportDocumentBuilder
import com.wangzan101.mika.core.export.ExportFormat
import com.wangzan101.mika.core.export.ExportManifest
import com.wangzan101.mika.core.export.ExportPackage
import com.wangzan101.mika.core.export.ExportPhase
import com.wangzan101.mika.core.export.ExportPresentation
import com.wangzan101.mika.core.export.ExportProgress
import com.wangzan101.mika.core.export.ExportRegistry
import com.wangzan101.mika.core.export.ExportSelection
import com.wangzan101.mika.core.export.ExportWarning
import com.wangzan101.mika.core.export.ManifestContent
import com.wangzan101.mika.core.export.ManifestConversation
import com.wangzan101.mika.core.export.ManifestCounts
import com.wangzan101.mika.core.export.ManifestSelection
import com.wangzan101.mika.core.export.ManifestWarning
import com.wangzan101.mika.core.export.markdown.MarkdownExportBackend
import com.wangzan101.mika.core.export.json.JsonExportBackend
import com.wangzan101.mika.export.html.HtmlExportBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

data class ExportOutcome(
    val warnings: List<ExportWarning>,
    val entryCount: Int,
    val bytes: Long,
)

/** 预览结果:文本预览直接给字符串;HTML 预览保留暂存目录给 WebViewAssetLoader,由调用方关闭时删除。 */
sealed interface ExportPreview {
    data class Text(
        val formatId: String,
        val text: String,
        val warnings: List<ExportWarning>,
        val truncated: Boolean = false,
    ) : ExportPreview
    data class Html(val directory: File, val entry: String, val warnings: List<ExportWarning>) : ExportPreview
}

/**
 * P1 的导出执行器:构建文档 → 后端渲染到 cache 暂存 → 复制资产 → 打包 → 写到 SAF。
 * 先写暂存再复制,失败/取消只清暂存,不删目的地。
 */
class ExportRuntime(private val context: Context) {
    private val labels = ExportLabelsUi.of(context)
    private val registry = ExportRegistry(
        formats = listOf(
            ExportFormat("markdown", "export_format_markdown", "text/markdown", "md"),
            ExportFormat("json", "export_format_json", "application/json", "json"),
            ExportFormat("html", "export_format_html", "text/html", "html"),
        ),
        backends = listOf(MarkdownExportBackend(labels), JsonExportBackend(), HtmlExportBackend(labels)),
    )

    fun formats() = registry.formats()

    suspend fun export(
        title: String,
        selection: ExportSelection,
        content: ExportContent,
        presentation: ExportPresentation,
        formatId: String,
        destination: Uri,
        bundle: Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        val format = registry.format(formatId) ?: throw IOException("unknown format: $formatId")
        val source = ConversationExportSource(context, selection.conversationId)
        val staging = File(context.cacheDir, "exports/staging/${UUID.randomUUID()}")
        val temp = File(context.cacheDir, "exports/${UUID.randomUUID()}.${format.extension}")
        try {
            val built = buildDocument(source, title, selection, content, onProgress)
            val warnings = built.warnings.toMutableList()
            copyAssets(built, source, staging, warnings, onProgress)
            val document = built.copy(warnings = warnings.toList())
            renderDocument(document, staging, formatId, presentation, source, onProgress)

            val entries = ExportPackage.contentEntries(staging)
            val asBundle = bundle || entries.size > 1 || document.assets.isNotEmpty()
            val bytes = if (asBundle) {
                val manifest = manifest(document, selection, content, entries, warnings)
                ExportPackage.writeManifest(staging, manifest)
                ExportPackage.zip(staging, temp)
                temp.length()
            } else {
                if (!ExportPackage.copySingle(staging, temp)) throw IOException("nothing to export")
                temp.length()
            }
            onProgress(ExportProgress(ExportPhase.Delivering, 0, 0, bytes))
            copyToDestination(temp, destination) { done -> onProgress(ExportProgress(ExportPhase.Delivering, 0, 0, done)) }
            ExportOutcome(warnings = warnings, entryCount = entries.size, bytes = bytes)
        } finally {
            staging.deleteRecursively()
            temp.delete()
        }
    }

    /**
     * 只构建和渲染,不写目的地。文本格式返回字符串;HTML 返回暂存目录,调用方用 WebViewAssetLoader 打开,
     * 关闭预览时负责删除目录。
     */
    suspend fun preview(
        title: String,
        selection: ExportSelection,
        content: ExportContent,
        presentation: ExportPresentation,
        formatId: String,
        onProgress: (ExportProgress) -> Unit,
    ): ExportPreview {
        val format = registry.format(formatId) ?: throw IOException("unknown format: $formatId")
        val source = ConversationExportSource(context, selection.conversationId)
        val staging = File(context.cacheDir, "exports/preview/${UUID.randomUUID()}")
        var owned: File? = null
        val result = try {
            withContext(Dispatchers.IO) {
                val entry = "document.${format.extension}"
                val built = buildDocument(source, title, selection, content, onProgress)
                val warnings = built.warnings.toMutableList()
                if (formatId == "html") {
                    copyAssets(built, source, staging, warnings, onProgress)
                    val document = built.copy(warnings = warnings.toList())
                    renderDocument(document, staging, formatId, presentation, source, onProgress)
                    owned = staging
                    ExportPreview.Html(staging, entry, warnings)
                } else {
                    val document = built.copy(warnings = warnings.toList())
                    renderDocument(document, staging, formatId, presentation, source, onProgress)
                    val (text, truncated) = readLimited(File(staging, entry), MAX_PREVIEW_CHARS)
                    staging.deleteRecursively()
                    ExportPreview.Text(formatId, text, warnings, truncated)
                }
            }
        } catch (e: Throwable) {
            // 还没交给调用方之前,任何失败/取消都清掉这次预览的暂存目录。
            (owned ?: staging).deleteRecursively()
            throw e
        }
        // 正常返回后目录归调用方;withContext 在取消时会在边界抛异常,上面的 catch 负责清理。
        owned = null
        return result
    }

    private suspend fun buildDocument(
        source: ConversationExportSource,
        title: String,
        selection: ExportSelection,
        content: ExportContent,
        onProgress: (ExportProgress) -> Unit,
    ): ExportDocument {
        onProgress(ExportProgress(ExportPhase.Preparing, 0, 0))
        return ExportDocumentBuilder(source, labels, BuildConfig.VERSION_NAME, language()).build(
            conversationId = selection.conversationId,
            title = title,
            selection = selection,
            content = content,
        )
    }

    private suspend fun renderDocument(
        document: ExportDocument,
        staging: File,
        formatId: String,
        presentation: ExportPresentation,
        source: ConversationExportSource,
        onProgress: (ExportProgress) -> Unit,
    ) {
        registry.defaultBackend(formatId).render(document, presentation, source, DirectoryExportSink(staging), onProgress)
    }

    private suspend fun copyAssets(
        document: ExportDocument,
        source: ConversationExportSource,
        staging: File,
        warnings: MutableList<ExportWarning>,
        onProgress: (ExportProgress) -> Unit,
    ) {
        val sink = DirectoryExportSink(staging)
        var totalAssets = 0L
        document.assets.forEachIndexed { index, asset ->
            currentCoroutineContext().ensureActive()
            onProgress(ExportProgress(ExportPhase.Packaging, index, document.assets.size))
            if (asset.sizeBytes > MAX_ASSET_BYTES) {
                warnings += ExportWarning("asset_too_large", asset.fileName)
                return@forEachIndexed
            }
            val remaining = MAX_TOTAL_ASSETS - totalAssets
            if (remaining <= 0) {
                warnings += ExportWarning("asset_total_too_large", asset.fileName)
                return@forEachIndexed
            }
            val target = File(staging, "assets/${asset.fileName}")
            try {
                val input = source.open(asset)
                if (input == null) {
                    warnings += ExportWarning("asset_missing", asset.fileName)
                    return@forEachIndexed
                }
                totalAssets += input.use { from ->
                    sink.open("assets/${asset.fileName}").use { to -> copyLimited(from, to, minOf(MAX_ASSET_BYTES, remaining)) }
                }
            } catch (e: CancellationException) {
                target.delete()
                throw e
            } catch (e: AssetTooLarge) {
                target.delete()
                warnings += ExportWarning("asset_too_large", asset.fileName)
            } catch (e: Exception) {
                target.delete()
                warnings += ExportWarning("asset_unreadable", asset.fileName)
            }
        }
    }

    private fun manifest(
        document: com.wangzan101.mika.core.export.ExportDocument,
        selection: ExportSelection,
        content: ExportContent,
        entries: List<com.wangzan101.mika.core.export.ManifestEntry>,
        warnings: List<ExportWarning>,
    ) = ExportManifest(
        appVersion = BuildConfig.VERSION_NAME,
        exportedAt = Instant.ofEpochMilli(document.meta.exportedAtMs).toString(),
        conversation = ManifestConversation(document.meta.conversationId, document.meta.title),
        selection = ManifestSelection(
            turns = document.blocks.mapNotNull { it.turnIndex() }.distinct().size,
            blocks = document.blocks.size,
            allSelected = selection.allSelected,
        ),
        content = ManifestContent(
            toolSteps = content.includeToolSteps,
            attachments = content.includeAttachments,
            screens = content.includeScreens,
            timestamps = content.includeTimestamps,
            model = content.includeModel,
            redaction = content.redaction.name.lowercase(),
        ),
        counts = ManifestCounts(
            blocks = document.blocks.size,
            toolSteps = document.blocks.filterIsInstance<ExportBlock.Answer>().sumOf { it.toolSteps.size },
            assets = document.assets.size,
        ),
        entries = entries,
        warnings = warnings.map { ManifestWarning(it.code, it.detail) },
    )

    private fun ExportBlock.turnIndex(): Int? = id.value.drop(1).substringBefore('-').toIntOrNull()

    private suspend fun copyToDestination(file: File, destination: Uri, progress: (Long) -> Unit) {
        currentCoroutineContext().ensureActive()
        val output = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("cannot open destination")
        output.use { out -> file.inputStream().use { input -> copy(input, out, progress) } }
    }

    private suspend fun copy(input: InputStream, output: OutputStream, progress: ((Long) -> Unit)? = null): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
            progress?.invoke(total)
        }
        return total
    }

    private suspend fun copyLimited(input: InputStream, output: OutputStream, max: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            if (total + n > max) throw AssetTooLarge()
            output.write(buf, 0, n)
            total += n
        }
        return total
    }

    private class AssetTooLarge : Exception()

    private fun readLimited(file: File, max: Int): Pair<String, Boolean> {
        file.reader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(max)
            var n = 0
            while (n < max) {
                val read = reader.read(buf, n, max - n)
                if (read < 0) break
                n += read
            }
            val more = reader.read() >= 0
            return String(buf, 0, n) to more
        }
    }

    private fun language(): String = java.util.Locale.getDefault().language

    private companion object {
        const val MAX_ASSET_BYTES = 50L shl 20
        const val MAX_TOTAL_ASSETS = 200L shl 20
        const val MAX_PREVIEW_CHARS = 200_000
    }
}
