package com.wangzan101.mika.plugin

import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 插件的非密设置和 secret:
 * - `settings.json`:普通键值,原子写;
 * - `secrets.bin`:整份 JSON 用 Keystore 密钥加密,页面只能看到「是否已设置」,脚本通过临时文件拿到。
 * secret 的值不写日志。
 */
class PluginSettings(
    private val dir: File,
    private val cipher: KeyCipher? = null,
) {
    private val file = File(dir, FILE)
    private val secretsFile = File(dir, SECRETS)

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

    @Synchronized
    fun secretSet(key: String): Boolean = secrets()[key] != null

    /** 只给脚本注入用;页面桥不返回明文。 */
    @Synchronized
    fun secretEnv(): Map<String, String> = secrets()

    /** [value] 为 null 时删掉;没有加密密钥或不支持的实现返回 false。 */
    @Synchronized
    fun secretWrite(key: String, value: String?): Boolean {
        val crypto = cipher ?: return false
        val next = secrets().toMutableMap()
        if (value == null) next.remove(key) else next[key] = value
        return runCatching {
            dir.mkdirs()
            val text = buildJsonObject { next.toSortedMap().forEach { (k, v) -> put(k, v) } }.toString()
            if (next.isEmpty()) {
                secretsFile.delete()
            } else {
                val tmp = File(dir, "$SECRETS.tmp")
                tmp.writeBytes(crypto.encrypt(text.toByteArray(Charsets.UTF_8)))
                if (!tmp.renameTo(secretsFile)) {
                    secretsFile.delete()
                    check(tmp.renameTo(secretsFile)) { "cannot write $SECRETS" }
                }
            }
        }.isSuccess
    }

    private fun read(): Map<String, String> = runCatching {
        val obj = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return emptyMap()
        obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun secrets(): Map<String, String> {
        val crypto = cipher ?: return emptyMap()
        if (!secretsFile.isFile) return emptyMap()
        return runCatching {
            val text = String(crypto.decrypt(secretsFile.readBytes()), Charsets.UTF_8)
            val obj = Json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
            obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
    }

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
        const val SECRETS = "secrets.bin"
    }
}

/** 每个插件一把独立的 Keystore 密钥;别名只留安全字符。 */
fun pluginKeyAlias(pluginId: String): String =
    "mika-plugin-" + pluginId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(48)
