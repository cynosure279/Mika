package com.wangzan101.mika.export

import android.net.Uri
import android.system.Os
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class HtmlPreviewTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val temp = TemporaryFolder()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var session: HtmlPreviewSession? = null

    @After fun tearDown() {
        instrumentation.runOnMainSync { session?.release() }
    }

    @Test fun webViewDisablesActiveContentAndStorage() {
        instrumentation.runOnMainSync {
            val current = newSession()
            val settings = current.webView.settings
            assertFalse(settings.javaScriptEnabled)
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)
            assertFalse(settings.domStorageEnabled)
            assertFalse(settings.databaseEnabled)
            assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
            assertFalse(settings.allowFileAccessFromFileURLs)
            assertFalse(settings.allowUniversalAccessFromFileURLs)
            assertFalse(settings.supportMultipleWindows())
            assertTrue(settings.blockNetworkLoads)
        }
    }

    @Test fun externalNavigationIsBlocked() {
        instrumentation.runOnMainSync {
            val current = newSession()
            val client = current.webView.webViewClient
            for (url in listOf(
                "https://example.com/",
                "http://example.com/",
                "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "file:///data/user/0/secret",
                "content://provider/secret",
            )) {
                assertTrue(url, client.shouldOverrideUrlLoading(current.webView, url))
                assertTrue(url, client.shouldOverrideUrlLoading(current.webView, request(url)))
            }
        }
    }

    @Test fun assetLoaderCannotEscapePreviewRoot() {
        val root = temp.newFolder("preview")
        val outside = temp.newFile("secret.txt").apply { writeText("secret") }
        File(root, "document.html").writeText("<title>safe</title>")
        File(root, "assets").mkdirs()
        File(root, "assets/a.txt").writeText("asset")
        Os.symlink(outside.absolutePath, File(root, "linked.txt").absolutePath)
        val loader = htmlPreviewAssetLoader(context, root)

        assertNotNull(loader.shouldInterceptRequest(Uri.parse("$HTML_PREVIEW_ORIGIN/document.html")))
        assertNotNull(loader.shouldInterceptRequest(Uri.parse("$HTML_PREVIEW_ORIGIN/assets/a.txt")))
        for (url in listOf(
            "$HTML_PREVIEW_ORIGIN/%2E%2E%2Fsecret.txt",
            "$HTML_PREVIEW_ORIGIN/assets/%2E%2E%2F%2E%2E%2Fsecret.txt",
            "$HTML_PREVIEW_ORIGIN/linked.txt",
            "https://example.com/document.html",
            "file://${root.absolutePath}/document.html",
            "content://provider/document.html",
        )) {
            assertNull(url, loader.shouldInterceptRequest(Uri.parse(url)))
        }
    }

    @Test fun releaseDestroysWebViewAndCleansDirectory() {
        instrumentation.runOnMainSync {
            val current = newSession()
            val directory = current.preview.directory
            val webView = current.webView as HtmlPreviewWebView
            assertTrue(directory.isDirectory)

            current.release()

            assertTrue(current.isReleased)
            assertTrue(webView.isDestroyed)
            assertFalse(directory.exists())
            current.release()
            assertFalse(directory.exists())
        }
    }

    @Test fun dismissDestroysPreviewAndCleansDirectory() {
        val directory = temp.newFolder("dismissed")
        File(directory, "document.html").writeText("<!doctype html><title>dismiss</title>")
        val preview = ExportPreview.Html(directory, "document.html", emptyList())
        val show = mutableStateOf(true)
        compose.setContent {
            if (show.value) ExportPreviewDialog(preview) { show.value = false }
        }
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.export_close)).performClick()
        compose.waitForIdle()

        assertFalse(directory.exists())
    }

    private fun newSession(): HtmlPreviewSession {
        val directory = temp.newFolder("session-${System.nanoTime()}")
        File(directory, "document.html").writeText("<!doctype html><title>preview</title>")
        val preview = ExportPreview.Html(directory, "document.html", emptyList())
        return HtmlPreviewSession(context, preview).also { session = it }
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
