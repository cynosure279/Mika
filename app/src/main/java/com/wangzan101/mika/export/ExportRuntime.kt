package com.wangzan101.mika.export

import android.content.Context
import android.net.Uri
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.export.DirectoryExportSink
import com.wangzan101.mika.core.export.ExportAssetReader
import com.wangzan101.mika.core.export.ExportBlock
import com.wangzan101.mika.core.export.ExportContent
import com.wangzan101.mika.core.export.ExportDocument
import com.wangzan101.mika.core.export.ExportDocumentBuilder
import com.wangzan101.mika.core.export.ExportFormat
import com.wangzan101.mika.core.export.ExportManifest
import com.wangzan101.mika.core.export.ExportNames
import com.wangzan101.mika.core.export.ExportPackage
import com.wangzan101.mika.core.export.ExportPhase
import com.wangzan101.mika.core.export.ExportPresentation
import com.wangzan101.mika.core.export.ExportProgress
import com.wangzan101.mika.core.export.ExportRegistry
import com.wangzan101.mika.core.export.ExportSelection
import com.wangzan101.mika.core.export.ExportSource
import com.wangzan101.mika.core.export.ExportWarning
import com.wangzan101.mika.core.export.ManifestContent
import com.wangzan101.mika.core.export.ManifestConversation
import com.wangzan101.mika.core.export.ManifestCounts
import com.wangzan101.mika.core.export.ManifestEntry
import com.wangzan101.mika.core.export.ManifestSelection
import com.wangzan101.mika.core.export.ManifestWarning
import com.wangzan101.mika.core.export.json.JsonExportBackend
import com.wangzan101.mika.core.export.markdown.MarkdownExportBackend
import com.wangzan101.mika.export.html.HtmlExportBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class ExportOutcome(
    val warnings: List<ExportWarning>,
    val entryCount: Int,
    val bytes: Long,
)

/** Android 适配层：预览和导出都显式接收对话框已经选定的同一个来源快照。 */
class ExportRuntime(private val context: Context) {
    private val processor = ExportProcessor(
        labels = ExportLabelsUi.of(context),
        appVersion = BuildConfig.VERSION_NAME,
        language = Locale.getDefault().language,
        cacheDir = context.cacheDir,
    )

    fun formats() = processor.formats()

    suspend fun export(
        source: ConversationExportSource,
        title: String,
        selection: ExportSelection,
        content: ExportContent,
        presentation: ExportPresentation,
        formatId: String,
        destination: Uri,
        bundle: Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ExportOutcome = processor.export(
        source = source,
        assets = source,
        title = title,
        selection = selection,
        content = content,
        presentation = presentation,
        formatId = formatId,
        bundle = bundle,
        onProgress = onProgress,
    ) { file, bytes ->
        onProgress(ExportProgress(ExportPhase.Delivering, 0, 0, bytes))
        copyToDestination(file, destination) { done ->
            onProgress(ExportProgress(ExportPhase.Delivering, 0, 0, done))
        }
    }

    suspend fun preview(
        source: ConversationExportSource,
        title: String,
        selection: ExportSelection,
        content: ExportContent,
        presentation: ExportPresentation,
        formatId: String,
        onProgress: (ExportProgress) -> Unit,
    ): ExportPreview = processor.preview(source, source, title, selection, content, presentation, formatId, onProgress)

    private suspend fun copyToDestination(file: File, destination: Uri, progress: (Long) -> Unit) {
        currentCoroutineContext().ensureActive()
        val output = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("cannot open destination")
        output.use { out -> file.inputStream().use { input -> copy(input, out, progress) } }
    }

    private suspend fun copy(input: InputStream, output: OutputStream, progress: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count
            progress(total)
        }
    }
}

/**
 * 与 Android 无关的导出处理器，便于用同一份真实代码验证快照、资产和取消语义。
 * 暂存成功后，HTML 预览目录只归返回的 [ExportPreview.Html] 所有。
 */
internal class ExportProcessor(
    private val labels: com.wangzan101.mika.core.export.ExportLabels,
    private val appVersion: String,
    private val language: String,
    private val cacheDir: File,
    private val registry: ExportRegistry = defaultRegistry(labels),
) {
    fun formats() = registry.formats()

    suspend fun export(
        source: ExportSource,
        assets: ExportAssetReader,
        title: String,
        selection: ExportSelection,
        content: ExportContent,
        presentation: ExportPresentation,
        formatId: String,
        bundle: Boolean,
        onProgress: (ExportProgress) -> Unit = {},
        deliver: suspend (File, Long) -> Unit,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        val format = registry.format(formatId) ?: throw IOException("unknown format: $formatId")
        val staging = File(cacheDir, "exports/staging/${UUID.randomUUID()}")
        val temp = File(cacheDir, "exports/${UUID.randomUUID()}.${format.extension}")
        try {
            val staged = stage(source, assets, title, selection, content, presentation, formatId, staging, true, onProgress)
            val entries = ExportPackage.contentEntries(staging)
            val bytes = if (bundle) {
                val manifest = manifest(staged.document, selection, content, entries, staged.warnings)
                ExportPackage.writeManifest(staging, manifest)
                ExportPackage.zip(staging, temp)
                temp.length()
            } else {
                if (staged.document.assets.isNotEmpty()) throw IOException("assets require a ZIP destination")
                if (!ExportPackage.copySingle(staging, temp)) throw IOException("nothing to export")
                temp.length()
            }
            deliver(temp, bytes)
            ExportOutcome(staged.warnings, entries.size, bytes)
        } finally {
            staging.deleteRecursively()
            temp.delete()
        }
    }

    suspend fun preview(
        source: ExportSource,
        assets: ExportAssetReader,
        title: String,
        selection: ExportSelection,
        content: ExportContent,
        presentation: ExportPresentation,
        formatId: String,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportPreview {
        val format = registry.format(formatId) ?: throw IOException("unknown format: $formatId")
        val staging = File(cacheDir, "exports/preview/${UUID.randomUUID()}")
        var handedOff = false
        try {
            val result = withContext(Dispatchers.IO) {
                val html = formatId == "html"
                val staged = stage(source, assets, title, selection, content, presentation, formatId, staging, html, onProgress)
                if (html) {
                    ExportPreview.Html(staging, "document.${format.extension}", staged.warnings)
                } else {
                    val (text, truncated) = readLimited(File(staging, "document.${format.extension}"), MAX_PREVIEW_CHARS)
                    staging.deleteRecursively()
                    ExportPreview.Text(formatId, text, staged.warnings, truncated)
                }
            }
            handedOff = true
            return result
        } finally {
            // withContext 在取消边界丢弃返回值时也必须清掉尚未交付的 HTML 目录。
            if (!handedOff) staging.deleteRecursively()
        }
    }

    private suspend fun stage(
        source: ExportSource,
        assets: ExportAssetReader,
        title: String,
        selection: ExportSelection,
        content: ExportContent,
        presentation: ExportPresentation,
        formatId: String,
        staging: File,
        copyAssets: Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): StagedExport {
        onProgress(ExportProgress(ExportPhase.Preparing, 0, 0))
        val built = ExportDocumentBuilder(source, labels, appVersion, language).build(
            conversationId = selection.conversationId,
            title = title,
            selection = selection,
            content = content,
        )
        registry.defaultBackend(formatId).render(built, presentation, assets, DirectoryExportSink(staging), onProgress)
        val warnings = built.warnings.toMutableList()
        if (copyAssets) copyAssets(built, assets, staging, warnings, onProgress)
        return StagedExport(built.copy(warnings = warnings.toList()), warnings.toList())
    }

    private suspend fun copyAssets(
        document: ExportDocument,
        assets: ExportAssetReader,
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
            val path = try {
                ExportNames.entryPath("assets/${asset.fileName}")
            } catch (e: IllegalArgumentException) {
                warnings += ExportWarning("asset_unreadable", asset.fileName)
                return@forEachIndexed
            }
            val target = File(staging, path)
            try {
                val input = assets.open(asset)
                if (input == null) {
                    warnings += ExportWarning("asset_missing", asset.fileName)
                    return@forEachIndexed
                }
                totalAssets += input.use { from ->
                    sink.open(path).use { to -> copyLimited(from, to, minOf(MAX_ASSET_BYTES, remaining)) }
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

    private suspend fun copyLimited(input: InputStream, output: OutputStream, max: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (total + count > max) throw AssetTooLarge()
            output.write(buffer, 0, count)
            total += count
        }
        return total
    }

    private fun readLimited(file: File, max: Int): Pair<String, Boolean> {
        file.reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(max)
            var count = 0
            while (count < max) {
                val read = reader.read(buffer, count, max - count)
                if (read < 0) break
                count += read
            }
            return String(buffer, 0, count) to (reader.read() >= 0)
        }
    }

    private fun manifest(
        document: ExportDocument,
        selection: ExportSelection,
        content: ExportContent,
        entries: List<ManifestEntry>,
        warnings: List<ExportWarning>,
    ) = ExportManifest(
        appVersion = appVersion,
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

    private data class StagedExport(
        val document: ExportDocument,
        val warnings: List<ExportWarning>,
    )

    private class AssetTooLarge : Exception()

    private companion object {
        const val MAX_ASSET_BYTES = 50L shl 20
        const val MAX_TOTAL_ASSETS = 200L shl 20
        const val MAX_PREVIEW_CHARS = 200_000

        fun defaultRegistry(labels: com.wangzan101.mika.core.export.ExportLabels) = ExportRegistry(
            formats = listOf(
                ExportFormat("markdown", "export_format_markdown", "text/markdown", "md"),
                ExportFormat("json", "export_format_json", "application/json", "json"),
                ExportFormat("html", "export_format_html", "text/html", "html"),
            ),
            backends = listOf(MarkdownExportBackend(labels), JsonExportBackend(), HtmlExportBackend(labels)),
        )
    }
}
