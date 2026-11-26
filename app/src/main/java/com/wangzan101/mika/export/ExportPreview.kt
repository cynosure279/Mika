package com.wangzan101.mika.export

import com.wangzan101.mika.core.export.ExportWarning
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 文本预览直接返回内容；HTML 预览把本次暂存目录的所有权交给调用方。 */
sealed interface ExportPreview {
    val warnings: List<ExportWarning>

    data class Text(
        val formatId: String,
        val text: String,
        override val warnings: List<ExportWarning>,
        val truncated: Boolean = false,
    ) : ExportPreview

    data class Html(
        val directory: File,
        val entry: String,
        override val warnings: List<ExportWarning>,
    ) : ExportPreview {
        private val released = AtomicBoolean(false)

        /** 关闭预览或离开面板时调用；重复释放不会误删后续预览的目录。 */
        fun release() {
            if (released.compareAndSet(false, true)) directory.deleteRecursively()
        }
    }
}
