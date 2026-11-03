package com.wangzan101.mika.export.html

import com.wangzan101.mika.core.export.AnswerOutcome
import com.wangzan101.mika.core.export.AssetKind
import com.wangzan101.mika.core.export.AssetRequest
import com.wangzan101.mika.core.export.ExportAsset
import com.wangzan101.mika.core.export.ExportAssetReader
import com.wangzan101.mika.core.export.ExportBlock
import com.wangzan101.mika.core.export.ExportDocument
import com.wangzan101.mika.core.export.ExportIds
import com.wangzan101.mika.core.export.ExportLabels
import com.wangzan101.mika.core.export.ExportMeta
import com.wangzan101.mika.core.export.ExportPresentation
import com.wangzan101.mika.core.export.ExportWarning
import com.wangzan101.mika.core.export.PageRef
import com.wangzan101.mika.core.export.ToolStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class HtmlExportBackendTest {
    @Test fun rendersSelfContainedHtmlAndEscapesUntrustedContent() = kotlinx.coroutines.runBlocking {
        val root = createTempDir()
        try {
            val image = ExportAsset("i", AssetKind.Image, "p.png", "image/png", 1, AssetRequest.ContentUri("content://i"))
            val file = ExportAsset("f", AssetKind.File, "a.txt", "text/plain", 1, AssetRequest.ContentUri("content://f"))
            val document = ExportDocument(
                meta = ExportMeta("c", "T <script>", 1_000L, "test", "en"),
                blocks = listOf(
                    ExportBlock.UserMessage(
                        ExportIds.user(0), "<b>x</b>", null,
                        PageRef("https://example.com/?a=1&b=2", "p <i>", "sel <b>"),
                        listOf(file), listOf(image), 1_000L,
                    ),
                    ExportBlock.Answer(
                        ExportIds.answer(0),
                        "# Hi\n\n<script>alert(1)</script>\n\n| a | b |\n|---|---|\n| 1 | 2 |",
                        AnswerOutcome.Succeeded, null, "gpt",
                        listOf(ToolStep(1, null, "tool", null, "<script>tool</script>", false, 5, null)),
                        emptyList(),
                    ),
                ),
                assets = listOf(image, file),
                warnings = listOf(ExportWarning("asset_missing", "gone.txt")),
            )
            HtmlExportBackend(ExportLabels.English).render(document, ExportPresentation(), Reader, com.wangzan101.mika.core.export.DirectoryExportSink(root)) {}
            val html = File(root, "document.html").readText()
            assertTrue(html.contains("<!doctype html>"))
            assertTrue(html.contains("Content-Security-Policy"))
            assertTrue(html.contains("<title>T &lt;script&gt;</title>"))
            assertTrue(html.contains("<div class=\"text\">&lt;b&gt;x&lt;/b&gt;</div>"))
            assertTrue(html.contains("href=\"https://example.com/?a=1&amp;b=2\""))
            assertTrue(html.contains("src=\"assets/p.png\""))
            assertTrue(html.contains("href=\"assets/a.txt\""))
            assertTrue(html.contains("<h1>Hi</h1>"))
            assertTrue(html.contains("<table>"))
            assertFalse(html.contains("<script>alert"))
            assertTrue(html.contains("&lt;script&gt;alert"))
            assertTrue(html.contains("&lt;script&gt;tool&lt;/script&gt;"))
            assertTrue(html.contains("Warnings"))
            assertTrue(html.contains("Missing: gone.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    private object Reader : ExportAssetReader {
        override suspend fun open(asset: ExportAsset): InputStream = java.io.ByteArrayInputStream(ByteArray(0))
    }
}
