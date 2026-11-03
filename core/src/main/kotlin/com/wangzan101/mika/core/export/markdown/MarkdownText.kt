package com.wangzan101.mika.core.export.markdown

import java.net.URLEncoder

/** 导出 Markdown 时的转义:普通文字当纯文本,链接目的地单独处理。 */
internal object MarkdownText {
    private val SPECIAL = setOf('\\', '`', '*', '_', '[', ']', '<', '>', '|', '#', '!')

    /** 用户原话、标题、说明这类纯文本:转义会被 Markdown 解释成结构或 HTML 的字符。 */
    fun plain(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (ch in SPECIAL) append('\\')
            append(ch)
        }
    }

    /** 行内代码:只转义会提前结束代码的字符,不把 `_` 之类改成可见的反斜杠。 */
    fun inlineCode(text: String): String = text.replace("\\", "\\\\").replace("`", "\\`")

    /** 只允许 http/https;用尖括号包住,免得地址里的括号/空格把链接提前结束。 */
    fun destination(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
        val safe = trimmed.replace("<", "%3C").replace(">", "%3E").replace("\r", "").replace("\n", "%0A")
        return "<$safe>"
    }

    /** 包内资产路径:文件名按 UTF-8 百分号编码,括号等不会跳出链接。 */
    fun assetPath(fileName: String): String = "assets/" + URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")

    /** 工具输出可能含任意长度的反引号串;围栏至少比最长的一串多一个。 */
    fun codeFence(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(maxOf(3, longest + 1))
    }

    /**
     * 回答正文保留 Markdown,但围栏代码块以外不原样放行 HTML:
     * 模型输出可能被网页内容引导,导出的文件又会被别的渲染器打开。
     */
    fun safeHtml(text: String): String {
        val out = StringBuilder(text.length)
        var fence: String? = null
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (fence == null && marker != null) {
                fence = marker
                out.append(line).append('\n')
            } else if (fence != null && marker == fence) {
                fence = null
                out.append(line).append('\n')
            } else if (fence != null) {
                out.append(line).append('\n')
            } else {
                out.append(line.replace("<", "&lt;")).append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }
}
