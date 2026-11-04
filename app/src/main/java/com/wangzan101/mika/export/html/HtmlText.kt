package com.wangzan101.mika.export.html

/** HTML 里所有来自对话/网页/文件的文字都从这里过。 */
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

    /** 只允许 http/https;返回已经转义的属性值。 */
    fun link(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
        return escape(trimmed)
    }
}
