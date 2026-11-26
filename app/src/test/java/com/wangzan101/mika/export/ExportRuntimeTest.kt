package com.wangzan101.mika.export

import com.wangzan101.mika.core.export.AssetKind
import com.wangzan101.mika.core.export.AssetRequest
import com.wangzan101.mika.core.export.ExportAsset
import com.wangzan101.mika.core.export.ExportAssetReader
import com.wangzan101.mika.core.export.ExportContent
import com.wangzan101.mika.core.export.ExportFileRef
import com.wangzan101.mika.core.export.ExportIds
import com.wangzan101.mika.core.export.ExportLabels
import com.wangzan101.mika.core.export.ExportPresentation
import com.wangzan101.mika.core.export.ExportSelection
import com.wangzan101.mika.core.export.ExportSource
import com.wangzan101.mika.core.export.ExportTurnRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExportRuntimeTest {
    @Test fun previewUsesSelectedSourceSnapshot() = runBlocking {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val processor = ExportProcessor(ExportLabels.English, "test", "en", root)
            val selectedSnapshot = FakeSource("selected answer")
            val laterSource = FakeSource("later answer")

            val preview = processor.preview(
                source = selectedSnapshot,
                assets = selectedSnapshot,
                title = "title",
                selection = ExportSelection("c1", setOf(ExportIds.answer(0)), allSelected = false),
                content = ExportContent(),
                presentation = ExportPresentation(),
                formatId = "markdown",
                onProgress = {},
            ) as ExportPreview.Text

            assertTrue(preview.text.contains("selected answer"))
            assertFalse(preview.text.contains("later answer"))
            assertEquals(1, selectedSnapshot.turnsCalls)
            assertEquals(0, laterSource.turnsCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun previewCopiesAssetsAndWarnings() = runBlocking {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = FakeSource("answer", linkedMapOf("a b.txt" to "payload".toByteArray(), "gone.txt" to null))
            val processor = ExportProcessor(ExportLabels.English, "test", "en", root)

            val preview = processor.preview(
                source, source, "title", ExportSelection("c1"), ExportContent(),
                ExportPresentation(), "html",
            ) {}
            val html = preview as ExportPreview.Html
            try {
                assertEquals("payload", File(html.directory, "assets/a b.txt").readText())
                assertFalse(File(html.directory, "assets/gone.txt").exists())
                assertTrue(html.warnings.any { it.code == "asset_missing" && it.detail == "gone.txt" })
                assertEquals("document.html", html.entry)
                assertTrue(File(html.directory, html.entry).readText().contains("href=\"assets/a%20b.txt\""))
            } finally {
                html.release()
            }
            assertFalse(html.directory.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun htmlPreviewKeepsAssetsWhileTextPreviewIsTruncated() = runBlocking {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val longAnswer = "x".repeat(200_100)
            val source = FakeSource(longAnswer, linkedMapOf("asset.txt" to "asset".toByteArray()))
            val processor = ExportProcessor(ExportLabels.English, "test", "en", root)

            val html = processor.preview(
                source, source, "title", ExportSelection("c1"), ExportContent(),
                ExportPresentation(), "html",
            ) {} as ExportPreview.Html
            try {
                assertTrue(File(html.directory, "assets/asset.txt").isFile)
                assertTrue(File(html.directory, html.entry).readText().contains(longAnswer))
                assertFalse(html.warnings.isNotEmpty())
            } finally {
                html.release()
            }

            val text = processor.preview(
                source, source, "title", ExportSelection("c1"), ExportContent(),
                ExportPresentation(), "markdown",
            ) {} as ExportPreview.Text
            assertTrue(text.truncated)
            assertEquals(200_000, text.text.length)
            assertFalse(text.text.contains(longAnswer))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cancelRemovesStagingWithoutTouchingDestination() = runBlocking {
        val root = kotlin.io.path.createTempDirectory().toFile()
        val destination = File(root, "destination.html").apply { writeText("keep") }
        val started = CompletableDeferred<Unit>()
        val proceed = CountDownLatch(1)
        try {
            val source = FakeSource("answer", linkedMapOf("endless.txt" to ByteArray(0))) { name ->
                if (name == "endless.txt") BlockingInputStream(started, proceed) else null
            }
            val processor = ExportProcessor(ExportLabels.English, "test", "en", File(root, "cache"))
            var delivered = false
            val job = launch(Dispatchers.Default) {
                processor.export(
                    source, source, "title", ExportSelection("c1"), ExportContent(),
                    ExportPresentation(), "html", bundle = true,
                ) { _, _ -> delivered = true }
            }
            withTimeout(5_000) { started.await() }
            job.cancel()
            proceed.countDown()
            job.join()

            assertTrue(job.isCancelled)
            assertFalse(delivered)
            assertEquals("keep", destination.readText())
            assertTrue(File(root, "cache").walkTopDown().none { it.isFile })
        } finally {
            proceed.countDown()
            root.deleteRecursively()
        }
    }

    @Test fun previewCancellationRemovesStaging() = runBlocking {
        val root = kotlin.io.path.createTempDirectory().toFile()
        val started = CompletableDeferred<Unit>()
        val proceed = CountDownLatch(1)
        try {
            val source = FakeSource("answer", linkedMapOf("endless.txt" to ByteArray(0))) { BlockingInputStream(started, proceed) }
            val processor = ExportProcessor(ExportLabels.English, "test", "en", root)
            val job = launch(Dispatchers.Default) {
                processor.preview(
                    source, source, "title", ExportSelection("c1"), ExportContent(),
                    ExportPresentation(), "html",
                ) {}
            }
            withTimeout(5_000) { started.await() }
            job.cancel()
            proceed.countDown()
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(root.walkTopDown().none { it.isFile })
        } finally {
            proceed.countDown()
            root.deleteRecursively()
        }
    }

    private class FakeSource(
        private val answer: String,
        private val assetBytes: Map<String, ByteArray?> = emptyMap(),
        private val opener: (String) -> InputStream? = { name -> assetBytes[name]?.let(::ByteArrayInputStream) },
    ) : ExportSource, ExportAssetReader {
        var turnsCalls = 0

        override suspend fun turns(conversationId: String): List<ExportTurnRecord> {
            turnsCalls++
            return listOf(
                ExportTurnRecord(
                    index = 0,
                    userText = "question",
                    outcome = answer,
                    status = "succeeded",
                    files = assetBytes.keys.map { name ->
                        ExportFileRef(name, assetBytes[name]?.size?.toLong() ?: 1L, AssetRequest.ContentUri("content://test/$name"))
                    },
                ),
            )
        }

        override suspend fun run(runId: String) = null

        override suspend fun resolve(request: AssetRequest): ExportAsset? {
            val name = (request as AssetRequest.ContentUri).uri.substringAfterLast('/')
            if (name !in assetBytes) return null
            return ExportAsset(name, AssetKind.File, name, "text/plain", assetBytes[name]?.size?.toLong() ?: 1L, request)
        }

        override suspend fun open(asset: ExportAsset): InputStream? = opener(asset.fileName)
    }

    private class BlockingInputStream(
        private val started: CompletableDeferred<Unit>,
        private val proceed: CountDownLatch,
    ) : InputStream() {
        override fun read(): Int = 1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            started.complete(Unit)
            check(proceed.await(5, TimeUnit.SECONDS)) { "test input was not released" }
            buffer[offset] = 1
            return 1
        }
    }
}
