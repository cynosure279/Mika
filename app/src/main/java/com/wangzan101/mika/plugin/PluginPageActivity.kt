package com.wangzan101.mika.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 插件动态页面:全屏 WebView,只加载 `files/plugins/<id>/pages/` 里的资源,固定 origin
 * `https://appassets.androidplatform.net`。页面通过白名单 `mika` 消息桥读写自己的设置/工作区、
 * 调用本插件工具;默认断网,只有插件声明 network 且全局允许联网时才放开。关闭即销毁 WebView。
 */
class PluginPageActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var bridge: PluginBridge? = null
    private var loader: WebViewAssetLoader? = null
    private var networkGranted = false
    private var csp = ""
    private val loadError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)
        val registry = (application as MikaApp).plugins
        val record = pluginId?.let { registry.record(it) }
        val manifest = pluginId?.let { registry.manifest(it) }
        val page = manifest?.pages?.firstOrNull { it.id == intent.getStringExtra(EXTRA_PAGE_ID) }
            ?: manifest?.pages?.firstOrNull()
        if (pluginId == null || record == null || manifest == null || page == null ||
            !record.enabled || "page" !in manifest.permissions
        ) {
            finish()
            return
        }

        val pagesDir = File(filesDir, "plugins/$pluginId/pages")
        val sandbox = AndroidSandbox(this, SandboxState.get(this))
        val runner = AndroidPluginScriptRunner(this, sandbox)
        val endpoint = PluginEndpoint(record, manifest, runner) { sandbox.networkAllowed() }
        val settings = PluginSettings(File(filesDir, "plugins/$pluginId"))
        networkGranted = "network" in manifest.permissions && sandbox.networkAllowed()
        csp = buildString {
            append("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; ")
            append("img-src 'self' data:; object-src 'none'; base-uri 'none'; form-action 'none'; ")
            append("connect-src ").append(if (networkGranted) "'self' https:" else "'none'").append(';')
        }
        bridge = PluginBridge(pluginId, manifest, Host(pluginId, endpoint, runner, settings))
        loader = WebViewAssetLoader.Builder()
            .addPathHandler("/plugin/$pluginId/", WebViewAssetLoader.InternalStoragePathHandler(this, pagesDir))
            .build()

        setContent {
            MikaTheme {
                PluginPageScreen(
                    title = page.title?.takeIf { it.isNotBlank() } ?: record.name,
                    error = loadError.value,
                    onBack = { finish() },
                    factory = { context ->
                        WebView(context).also { view -> webView = view; configure(view, manifest, page) }
                    },
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView, manifest: PluginManifest, page: PluginPageDecl) {
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        view.setBackgroundColor(Color.TRANSPARENT)
        with(view.settings) {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = !networkGranted
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                intercept(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                return !(url.scheme == "https" && url.host == ORIGIN_HOST)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loadError.value = tr(R.string.plugins_page_failed)
            }
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            loadError.value = tr(R.string.plugins_page_failed)
            return
        }
        val pageBridge = bridge ?: return
        WebViewCompat.addWebMessageListener(view, BRIDGE, setOf(ORIGIN)) { _, message, sourceOrigin, isMainFrame, reply ->
            if (!isMainFrame || sourceOrigin.scheme != "https" || sourceOrigin.host != ORIGIN_HOST) return@addWebMessageListener
            handleMessage(view, pageBridge, message, reply)
        }
        val pageUrl = "https://$ORIGIN_HOST/plugin/${manifest.id}/${page.entry.removePrefix("pages/")}"
        view.loadUrl(pageUrl)
    }

    private fun handleMessage(view: WebView, bridge: PluginBridge, message: WebMessageCompat, reply: JavaScriptReplyProxy) {
        val data = message.data ?: return
        if (data.length > MAX_MESSAGE) {
            post(view, reply, errorJson("message_too_large"))
            return
        }
        val request = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull()
        if (request == null) {
            post(view, reply, errorJson("bad_request"))
            return
        }
        lifecycleScope.launch {
            val result = bridge.handle(request)
            post(view, reply, result.toString())
        }
    }

    private fun post(view: WebView, reply: JavaScriptReplyProxy, text: String) {
        view.post { reply.postMessage(text) }
    }

    private fun errorJson(code: String): String = buildJsonObject {
        put("id", ""); put("ok", false); put("error", code)
    }.toString()

    /** 只对 HTML 加 CSP 头;页面脚本/样式仍由 WebViewAssetLoader 从包内提供。 */
    private fun intercept(url: Uri): WebResourceResponse? {
        val asset = loader?.shouldInterceptRequest(url) ?: return null
        if (!url.path.orEmpty().endsWith(".html")) return asset
        val input = asset.data ?: return asset
        val bytes = input.use { it.readBytes() }
        val html = String(bytes, Charsets.UTF_8)
        val meta = """<meta http-equiv="Content-Security-Policy" content="${csp.replace("\"", "&quot;")}">"""
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
        val injected = if (head.containsMatchIn(html)) html.replaceFirst(head, "$0\n$meta") else "$meta\n$html"
        return WebResourceResponse(
            "text/html", "UTF-8", 200, "OK",
            mapOf("Content-Security-Policy" to csp, "X-Content-Type-Options" to "nosniff"),
            ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)),
        )
    }

    override fun onDestroy() {
        webView?.let { view ->
            runCatching { WebViewCompat.removeWebMessageListener(view, BRIDGE) }
            runCatching { view.stopLoading() }
            runCatching { view.loadUrl("about:blank") }
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private inner class Host(
        private val pluginId: String,
        private val endpoint: PluginEndpoint,
        private val runner: AndroidPluginScriptRunner,
        private val settings: PluginSettings,
    ) : PluginBridgeHost {
        private val dirs by lazy { SandboxDirs(this@PluginPageActivity) }
        private val workspace by lazy { dirs.pluginWorkspace(pluginId) }

        override suspend fun settingsGet(key: String): String? = settings.get(key)

        override suspend fun settingsSet(key: String, value: String?): String? = settings.set(key, value)

        override suspend fun callTool(name: String, args: JsonObject): PluginToolResult {
            val result = endpoint.call(name, args)
            return PluginToolResult(result.text, result.isError)
        }

        override suspend fun workspaceRead(path: String): String? = withContext(Dispatchers.IO) {
            dirs.gate.openRead(workspace, path).use { file ->
                if (file.size > MAX_TEXT) null else String(file.read(0, file.size.toInt()), Charsets.UTF_8)
            }
        }

        override suspend fun workspaceWrite(path: String, text: String): Boolean = withContext(Dispatchers.IO) {
            dirs.tree.shared { dirs.gate.replace(workspace, path, text.toByteArray(Charsets.UTF_8)) }
            true
        }

        override suspend fun workspaceList(path: String): List<String> = withContext(Dispatchers.IO) {
            if (path.isEmpty()) {
                workspace.listFiles()?.filter { it.isFile || it.isDirectory }?.map { it.name }?.sorted().orEmpty()
            } else {
                dirs.gate.list(workspace, path).sorted()
            }
        }

        override suspend fun runScript(name: String, args: JsonObject): ScriptResult =
            runner.run(pluginId, name, args, timeoutSec = 60, network = networkGranted)

        override fun close() {
            runOnUiThread { finish() }
        }
    }

    companion object {
        private const val EXTRA_PLUGIN_ID = "plugin_id"
        private const val EXTRA_PAGE_ID = "page_id"
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val ORIGIN_HOST = "appassets.androidplatform.net"
        private const val BRIDGE = "mika"
        private const val MAX_MESSAGE = 64 * 1024
        private const val MAX_TEXT = 64 * 1024

        fun intent(context: Context, pluginId: String, pageId: String? = null): Intent =
            Intent(context, PluginPageActivity::class.java)
                .putExtra(EXTRA_PLUGIN_ID, pluginId)
                .putExtra(EXTRA_PAGE_ID, pageId)
    }
}

@Composable
private fun PluginPageScreen(
    title: String,
    error: String?,
    onBack: () -> Unit,
    factory: (Context) -> WebView,
) {
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, MikaIcons.Back, onBack, tr(R.string.back))
            if (error != null) {
                Text(error, Modifier.weight(1f).fillMaxSize(), color = MikaTheme.colors.ink2)
            } else {
                AndroidView(factory = factory, modifier = Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}
