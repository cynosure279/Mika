package com.wangzan101.mika.export.html

import org.commonmark.renderer.html.DefaultUrlSanitizer
import org.commonmark.renderer.html.UrlSanitizer
import java.net.URLEncoder

/** HTML 里所有来自对话、网页、工具或文件的文字都从这里过。 */
internal object HtmlText {
    fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    /** 外部链接只允许 http/https，返回值已经过属性转义。 */
    fun link(url: String): String? {
        val trimmed = url.trim()
        if (!isRemote(trimmed)) return null
        return escape(trimmed)
    }

    /** 包内路径保留单段文件名，分隔符和 HTML 字符全部百分号编码。 */
    fun assetPath(fileName: String): String =
        "assets/" + URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")

    /**
     * CommonMark 自带的 sanitizer 会保留相对地址。模型正文只允许明确的 http/https 链接，
     * 以及本类白名单内的安全 data 图片；相对路径只由本后端为包内资产生成。
     */
    fun urlSanitizer(): UrlSanitizer {
        val delegate = DefaultUrlSanitizer(listOf("http", "https"))
        return object : UrlSanitizer {
            override fun sanitizeLinkUrl(url: String): String =
                if (isRemote(url.trim())) delegate.sanitizeLinkUrl(url) else ""

            override fun sanitizeImageUrl(url: String): String {
                val trimmed = url.trim()
                if (SAFE_DATA_IMAGE.containsMatchIn(trimmed)) return trimmed
                return if (isRemote(trimmed)) delegate.sanitizeImageUrl(url) else ""
            }
        }
    }

    private val SAFE_DATA_IMAGE = Regex("^data:image/(?:png|gif|jpeg|webp)(?:[;,]|$)", RegexOption.IGNORE_CASE)

    private fun isRemote(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}
