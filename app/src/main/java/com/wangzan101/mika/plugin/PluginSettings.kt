package com.wangzan101.mika.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 插件的非密设置:P2 先把键值写成 `settings.json`(原子写),secret 留给 P3 的 secrets.bin。
 * 值统一按字符串存;页面按 manifest 里的类型自己解析。
 */
class PluginSettings(private val dir: File) {
    private val file = File(dir, FILE)

    @Synchronized
    fun get(key: String): String? = read()[key]

    /** [value] 为 null 时删掉这一项;返回写入后的值。 */
    @Synchronized
    fun set(key: String, value: String?): String? {
        val next = read().toMutableMap()
        if (value == null) next.remove(key) else next[key] = value
        write(next)
        return value
    }

    @Synchronized
    fun all(): Map<String, String> = read()

    private fun read(): Map<String, String> = runCatching {
        val obj = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return emptyMap()
        obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun write(values: Map<String, String>) {
        dir.mkdirs()
        val text = buildJsonObject { values.toSortedMap().forEach { (k, v) -> put(k, v) } }.toString()
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write $FILE" }
        }
    }

    private companion object {
        const val FILE = "settings.json"
    }
}
