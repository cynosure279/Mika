package com.wangzan101.mika.export.html

import com.wangzan101.mika.core.export.AnswerOutcome
import com.wangzan101.mika.core.export.AssetKind
import com.wangzan101.mika.core.export.AssetRequest
import com.wangzan101.mika.core.export.DirectoryExportSink
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
import com.wangzan101.mika.core.export.QuotedText
import com.wangzan101.mika.core.export.QuoteSource
import com.wangzan101.mika.core.export.ToolStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class HtmlExportBackendTest {
    @Test fun rendersSelfContainedHtmlAndEscapesUntrustedContent() = runBlocking {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val image = ExportAsset("i", AssetKind.UserScreen, "screen one.png", "image/png", 1, AssetRequest.ContentUri("content://i"))
            val file = ExportAsset("f", AssetKind.File, "a&<b>.txt", "text/plain", 1, AssetRequest.ContentUri("content://f"))
            val document = ExportDocument(
                meta = ExportMeta("c", "T <script>&\"'", 1_000L, "test", "en"),
                blocks = listOf(
                    ExportBlock.UserMessage(
                        ExportIds.user(0), "<b>x</b>", QuotedText(QuoteSource.Mika, "quote <i>"),
                        PageRef("https://example.com/?a=1&b=2", "p <i>", "sel <b>"),
                        listOf(file), listOf(image), 1_000L,
                    ),
                    ExportBlock.Answer(
                        ExportIds.answer(0),
                        "# Hi\n\n<script>alert(1)</script>\n\n| a | b |\n|---|---|\n| 1 | 2 |",
                        AnswerOutcome.Succeeded, null, "gpt <model>",
                        listOf(ToolStep(1, "note <n>", "tool <t>", "{\"x\":\"<arg>\"}", "<script>tool</script>", false, 5, null)),
                        emptyList(),
                    ),
                ),
                assets = listOf(image, file),
                warnings = listOf(ExportWarning("asset_missing", "gone <file>.txt")),
            )
            HtmlExportBackend(ExportLabels.English).render(document, ExportPresentation(), Reader, DirectoryExportSink(root)) {}
            val html = File(root, "document.html").readText()

            assertTrue(html.startsWith("<!doctype html>"))
            assertTrue(html.contains("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'\">"))
            assertTrue(html.contains("<title>T &lt;script&gt;&amp;&quot;&#39;</title>"))
            assertTrue(html.contains("<h1>T &lt;script&gt;&amp;&quot;&#39;</h1>"))
            assertTrue(html.contains("<div class=\"text\">&lt;b&gt;x&lt;/b&gt;</div>"))
            assertTrue(html.contains("quote &lt;i&gt;"))
            assertTrue(html.contains("p &lt;i&gt;"))
            assertTrue(html.contains("sel &lt;b&gt;"))
            assertTrue(html.contains("href=\"https://example.com/?a=1&amp;b=2\""))
            assertTrue(html.contains("src=\"assets/screen%20one.png\""))
            assertTrue(html.contains("alt=\"screen one.png\""))
            assertTrue(html.contains("href=\"assets/a%26%3Cb%3E.txt\""))
            assertTrue(html.contains(">a&amp;&lt;b&gt;.txt</a>"))
            assertTrue(html.contains("<h1>Hi</h1>"))
            assertTrue(html.contains("<table>"))
            assertTrue(html.contains("gpt &lt;model&gt;"))
            assertTrue(html.contains("tool &lt;t&gt;"))
            assertTrue(html.contains("&lt;arg&gt;"))
            assertTrue(html.contains("&lt;script&gt;tool&lt;/script&gt;"))
            assertFalse(html.contains("<script>alert"))
            assertTrue(html.contains("&lt;script&gt;alert"))
            assertTrue(html.contains("Warnings"))
            assertTrue(html.contains("Missing: gone &lt;file&gt;.txt"))
            assertFalse(html.contains("<script src="))
            assertFalse(html.contains("<link rel=\"stylesheet\""))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsDangerousUrlsAndEscapesAssetNames() = runBlocking {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val asset = ExportAsset(
                "f", AssetKind.File, "../a&\"<b>.txt", "text/plain", 1,
                AssetRequest.ContentUri("content://f"),
            )
            val dangerousPages = listOf(
                "javascript:alert(1)",
                "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
                "file:///data/user/0/secret",
                "content://provider/secret",
                "../secret",
            )
            val users = dangerousPages.mapIndexed { index, url ->
                ExportBlock.UserMessage(
                    ExportIds.user(index), "user", null, PageRef(url, "page", null),
                    emptyList(), emptyList(), null,
                )
            }
            val answer = ExportBlock.Answer(
                ExportIds.answer(0),
                "[js](javascript:alert(1))\n\n" +
                    "[html](data:text/html;base64,PHNjcmlwdD4=)\n\n" +
                    "[file](file:///secret)\n\n" +
                    "[content](content://provider/secret)\n\n" +
                    "[up](../secret)\n\n" +
                    "![html](data:text/html;base64,PHNjcmlwdD4=)\n\n" +
                    "![png](data:image/png;base64,iVBORw0KGgo=)\n\n" +
                    "[safe](<https://example.com/a b>)",
                AnswerOutcome.Succeeded, null, null, emptyList(), listOf(asset),
            )
            val document = ExportDocument(
                ExportMeta("c", "title", 1L, "test", "en"), users + answer, listOf(asset),
            )

            HtmlExportBackend(ExportLabels.English).render(document, ExportPresentation(), Reader, DirectoryExportSink(root)) {}
            val html = File(root, "document.html").readText()

            assertFalse(html.contains("href=\"javascript:"))
            assertFalse(html.contains("href=\"data:text/html"))
            assertFalse(html.contains("src=\"data:text/html"))
            assertFalse(html.contains("href=\"file:"))
            assertFalse(html.contains("href=\"content:"))
            assertFalse(html.contains("href=\"../"))
            assertFalse(html.contains("src=\"../"))
            assertFalse(html.contains("assets/../"))
            assertTrue(html.contains("src=\"data:image/png;base64,iVBORw0KGgo=\""))
            assertTrue(html.contains("href=\"https://example.com/a%20b\""))
            assertTrue(html.contains("href=\"assets/..%2Fa%26%22%3Cb%3E.txt\""))
            assertTrue(html.contains(">../a&amp;&quot;&lt;b&gt;.txt</a>"))
        } finally {
            root.deleteRecursively()
        }
    }

    private object Reader : ExportAssetReader {
        override suspend fun open(asset: ExportAsset): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
