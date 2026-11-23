package com.wangzan101.mika.plugin

import java.io.File

/**
 * 每个插件一份调试日志。只记事件名、工具名、退出码、耗时这类信息;不记参数、输出全文和 secret。
 * 超过 [MAX_BYTES] 时整份清空重记,避免无限增长。
 */
class PluginLog(private val dir: File) {
    private val file = File(dir, FILE)

    @Synchronized
    fun append(kind: String, message: String) {
        runCatching {
            dir.mkdirs()
            if (file.length() > MAX_BYTES) file.writeText("")
            file.appendText("${System.currentTimeMillis()} $kind $message\n")
        }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    @Synchronized
    fun read(): String = runCatching { file.takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")

    private companion object {
        const val FILE = "plugin.log"
        const val MAX_BYTES = 1L shl 20
    }
}
