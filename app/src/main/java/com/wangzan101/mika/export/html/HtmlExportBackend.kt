package com.wangzan101.mika.export.html

import com.wangzan101.mika.core.export.AnswerOutcome
import com.wangzan101.mika.core.export.AssetKind
import com.wangzan101.mika.core.export.ExportAsset
import com.wangzan101.mika.core.export.ExportAssetReader
import com.wangzan101.mika.core.export.ExportBackend
import com.wangzan101.mika.core.export.ExportBlock
import com.wangzan101.mika.core.export.ExportCapability
import com.wangzan101.mika.core.export.ExportDescriptor
import com.wangzan101.mika.core.export.ExportDocument
import com.wangzan101.mika.core.export.ExportKind
import com.wangzan101.mika.core.export.ExportLabels
import com.wangzan101.mika.core.export.ExportNames
import com.wangzan101.mika.core.export.ExportPhase
import com.wangzan101.mika.core.export.ExportPresentation
import com.wangzan101.mika.core.export.ExportProgress
import com.wangzan101.mika.core.export.ExportSink
import com.wangzan101.mika.core.export.ExportWarningText
import com.wangzan101.mika.core.export.ToolStep
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 自包含 HTML:样式内联、不加载任何远程资源、不开脚本,图片用包内 assets/ 相对路径。
 * 回答正文交给 commonmark,原始 HTML 关掉;其余字段一律先转义。
 */
class HtmlExportBackend(private val labels: ExportLabels = ExportLabels.English) : ExportBackend {
    override val descriptor = ExportDescriptor(
        id = "app-html",
        formatId = "html",
        extension = "html",
        mime = "text/html",
        kind = ExportKind.SingleFile,
        capabilities = setOf(
            ExportCapability.ToolSteps, ExportCapability.Attachments, ExportCapability.Screens,
            ExportCapability.Timestamps, ExportCapability.ModelName, ExportCapability.Preview,
            ExportCapability.Streaming, ExportCapability.Background,
        ),
    )

    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .percentEncodeUrls(true)
        .build()

    override suspend fun render(
        document: ExportDocument,
        presentation: ExportPresentation,
        assets: ExportAssetReader,
        sink: ExportSink,
        progress: (ExportProgress) -> Unit,
    ) {
        val total = document.blocks.size
        progress(ExportProgress(ExportPhase.Rendering, 0, total))
        sink.open("document.html").bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("<!doctype html>")
            out.appendLine("<html lang=\"${HtmlText.escape(document.meta.language)}\">")
            out.appendLine("<head>")
            out.appendLine("<meta charset=\"utf-8\">")
            out.appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            out.appendLine("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'\">")
            out.appendLine("<title>${HtmlText.escape(document.meta.title)}</title>")
            out.appendLine("<style>$CSS</style>")
            out.appendLine("</head>")
            out.appendLine("<body><main>")
            out.appendLine("<h1>${HtmlText.escape(document.meta.title)}</h1>")
            out.appendLine("<p class=\"meta\">${HtmlText.escape(labels.exportedAt)}: ${time(document.meta.exportedAtMs)}</p>")
            document.blocks.forEachIndexed { index, block ->
                when (block) {
                    is ExportBlock.UserMessage -> user(out, block)
                    is ExportBlock.SteerMessage -> steer(out, block)
                    is ExportBlock.Answer -> answer(out, block)
                }
                progress(ExportProgress(ExportPhase.Rendering, index + 1, total))
            }
            warnings(out, document)
            out.appendLine("</main></body></html>")
        }
    }

    private fun user(out: Appendable, block: ExportBlock.UserMessage) {
        out.appendLine("<section class=\"turn\">")
        out.appendLine("<div class=\"who\">${HtmlText.escape(labels.user)}${block.atMs?.let { " · ${time(it)}" }.orEmpty()}</div>")
        block.quote?.let {
            out.appendLine("<blockquote class=\"quote\">${HtmlText.escape(it.text)}</blockquote>")
        }
        block.page?.let { page ->
            val title = HtmlText.escape(page.title.ifBlank { page.url })
            val href = HtmlText.link(page.url)
            out.appendLine("<p class=\"source\">${HtmlText.escape(labels.page)}: " + (href?.let { "<a href=\"$it\">$title</a>" } ?: title) + "</p>")
            page.selection?.takeIf { it.isNotBlank() }?.let { out.appendLine("<blockquote class=\"selection\">${HtmlText.escape(it)}</blockquote>") }
        }
        block.text.takeIf { it.isNotBlank() }?.let { out.appendLine("<div class=\"text\">${HtmlText.escape(it)}</div>") }
        block.screens.forEach { image(out, it) }
        if (block.attachments.isNotEmpty()) {
            out.appendLine("<h2>${HtmlText.escape(labels.attachments)}</h2>")
            block.attachments.forEach { if (it.kind == AssetKind.Image) image(out, it) else file(out, it) }
        }
        out.appendLine("</section>")
    }

    private fun steer(out: Appendable, block: ExportBlock.SteerMessage) {
        out.appendLine("<section class=\"turn steer\">")
        out.appendLine("<div class=\"who\">${HtmlText.escape(labels.steer)}</div>")
        block.interruptedText?.takeIf { it.isNotBlank() }?.let { out.appendLine("<blockquote class=\"quote\">${HtmlText.escape(it)}</blockquote>") }
        block.finishedText?.takeIf { it.isNotBlank() }?.let { out.appendLine("<blockquote class=\"quote\">${HtmlText.escape(it)}</blockquote>") }
        block.text.takeIf { it.isNotBlank() }?.let { out.appendLine("<div class=\"text\">${HtmlText.escape(it)}</div>") }
        block.assets.forEach { if (it.kind == AssetKind.Image || it.kind == AssetKind.UserScreen) image(out, it) else file(out, it) }
        out.appendLine("</section>")
    }

    private fun answer(out: Appendable, block: ExportBlock.Answer) {
        out.appendLine("<section class=\"turn answer\">")
        out.appendLine("<div class=\"who\">${HtmlText.escape(labels.mika)}${block.atMs?.let { " · ${time(it)}" }.orEmpty()}</div>")
        block.model?.takeIf { it.isNotBlank() }?.let {
            out.appendLine("<p class=\"meta\">${HtmlText.escape(labels.model)}: ${HtmlText.escape(it)}</p>")
        }
        when (val o = block.outcome) {
            AnswerOutcome.Succeeded -> Unit
            is AnswerOutcome.Unverified -> {
                out.appendLine("<p class=\"note\">${HtmlText.escape(labels.unverified.format(o.note.orEmpty()))}</p>")
                o.unconfirmed.forEach { out.appendLine("<p class=\"note\">${HtmlText.escape(it)}</p>") }
            }
            is AnswerOutcome.Failed -> out.appendLine("<p class=\"note\">${HtmlText.escape(labels.notDone.format(o.reason))}</p>")
            is AnswerOutcome.Cancelled -> {
                out.appendLine("<p class=\"note\">${HtmlText.escape(labels.cancelled)}</p>")
                o.summary?.let { out.appendLine("<p class=\"note\">${HtmlText.escape(it)}</p>") }
            }
        }
        block.markdown.takeIf { it.isNotBlank() }?.let {
            out.appendLine("<div class=\"markdown\">${renderer.render(parser.parse(it))}</div>")
        }
        if (block.toolSteps.isNotEmpty()) {
            out.appendLine("<h2>${HtmlText.escape(labels.toolSteps)}</h2>")
            block.toolSteps.forEach { step(out, it) }
        }
        if (block.files.isNotEmpty()) {
            out.appendLine("<h2>${HtmlText.escape(labels.files)}</h2>")
            block.files.forEach { file(out, it) }
        }
        out.appendLine("</section>")
    }

    private fun step(out: Appendable, step: ToolStep) {
        val tool = step.tool?.takeIf { it.isNotBlank() }?.let { " · <code>${HtmlText.escape(it)}</code>" }.orEmpty()
        val duration = if (step.durationMs > 0) " · ${step.durationMs} ms" else ""
        val error = if (step.isError) " · ${HtmlText.escape(labels.toolError)}" else ""
        out.appendLine("<details class=\"tool\"><summary>${String.format(Locale.ROOT, labels.step, step.step)}$tool$duration$error</summary>")
        step.note?.takeIf { it.isNotBlank() }?.let { out.appendLine("<p>${HtmlText.escape(it)}</p>") }
        step.argsJson?.takeIf { it.isNotBlank() }?.let { out.appendLine("<pre><code>${HtmlText.escape(it)}</code></pre>") }
        step.result?.takeIf { it.isNotBlank() }?.let { out.appendLine("<pre><code>${HtmlText.escape(it)}</code></pre>") }
        out.appendLine("</details>")
    }

    private fun image(out: Appendable, asset: ExportAsset) {
        out.appendLine("<figure><img src=\"${HtmlText.escape(ExportNames.assetPath(asset.fileName))}\" alt=\"${HtmlText.escape(asset.fileName)}\"></figure>")
    }

    private fun file(out: Appendable, asset: ExportAsset) {
        out.appendLine("<p class=\"file\"><a href=\"${HtmlText.escape(ExportNames.assetPath(asset.fileName))}\">${HtmlText.escape(asset.fileName)}</a></p>")
    }

    private fun warnings(out: Appendable, document: ExportDocument) {
        if (document.warnings.isEmpty()) return
        out.appendLine("<section class=\"warnings\"><h2>${HtmlText.escape(labels.warnings)}</h2><ul>")
        document.warnings.forEach {
            out.appendLine("<li>${HtmlText.escape(ExportWarningText.line(it, labels))}</li>")
        }
        out.appendLine("</ul></section>")
    }

    private fun time(ms: Long) = FORMAT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
        const val CSS = """
:root{color-scheme:light dark;--bg:#fff;--fg:#1b1b1f;--muted:#61616b;--line:#ddd;--accent:#3d6b5c;--code:#f5f5f7}
@media (prefers-color-scheme:dark){:root{--bg:#121214;--fg:#ececf1;--muted:#a7a7b0;--line:#33333a;--accent:#8bc3b0;--code:#1e1e23}}
*{box-sizing:border-box}
html,body{margin:0;padding:0;background:var(--bg);color:var(--fg)}
body{font:16px/1.6 system-ui,"Noto Sans CJK SC","Noto Sans SC","PingFang SC","Microsoft YaHei",sans-serif}
main{max-width:760px;margin:0 auto;padding:32px 20px 64px}
h1{font-size:1.6rem;line-height:1.3;margin:0 0 8px}
h2{font-size:1.05rem;margin:24px 0 8px}
.meta,.source,.file{color:var(--muted);font-size:.9rem}
.turn{border-top:1px solid var(--line);padding:20px 0}
.who{font-weight:600;color:var(--accent);margin-bottom:8px}
.text,.quote,.selection{white-space:pre-wrap;overflow-wrap:anywhere}
.quote{border-left:3px solid var(--line);margin:8px 0;padding:4px 12px;color:var(--muted)}
.selection{font-family:ui-monospace,monospace;font-size:.9rem}
.markdown table{border-collapse:collapse;width:100%;margin:12px 0}
.markdown th,.markdown td{border:1px solid var(--line);padding:6px 8px;text-align:left}
.markdown img{max-width:100%;height:auto}
.markdown pre,.tool pre{background:var(--code);padding:12px;overflow:auto;border-radius:8px}
.markdown code{font-family:ui-monospace,monospace;font-size:.9em}
figure{margin:12px 0}
figure img{max-width:100%;height:auto;border-radius:8px}
a{color:var(--accent)}
details.tool{border:1px solid var(--line);border-radius:8px;padding:8px 12px;margin:8px 0}
details.tool summary{cursor:pointer;color:var(--muted)}
.warnings{border-top:1px solid var(--line);margin-top:24px;padding-top:12px;color:var(--muted)}
@media print{:root{--bg:#fff;--fg:#000;--muted:#444;--line:#bbb;--code:#f2f2f2}body{font-size:11pt}main{max-width:none;padding:0}.turn{break-inside:avoid}.markdown pre,.tool pre{white-space:pre-wrap}details.tool{break-inside:avoid}}
"""
    }
}
