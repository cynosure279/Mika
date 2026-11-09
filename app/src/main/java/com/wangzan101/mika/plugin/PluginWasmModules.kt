package com.wangzan101.mika.plugin

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 包内 Wasm 模块与安装时记录的 SHA-256 清单(包目录的 `wasm.sha256`)。
 * 运行前按它复核,模块被换过就拒绝,和 [PluginScripts] 对脚本的做法一致。
 */
object PluginWasmModules {
    const val HASH_FILE = "wasm.sha256"

    fun collect(packageDir: File): Map<String, String> {
        val tools = File(packageDir, "tools")
        if (!tools.isDirectory) return emptyMap()
        val out = sortedMapOf<String, String>()
        Files.walk(tools.toPath()).use { paths ->
            for (p in paths) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    throw PluginScriptException(PluginScriptErrors.UNSAFE, "tools contain a non-regular file")
                }
                val rel = "tools/" + tools.toPath().relativize(p).joinToString("/")
                if (!PluginManifest.WASM.matches(rel)) continue
                out[rel] = PluginScripts.hash(Files.readAllBytes(p))
            }
        }
        return out
    }

    fun writeHashes(packageDir: File, hashes: Map<String, String>) {
        val json = buildString {
            append('{')
            hashes.toSortedMap().entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append('"').append(k).append("\":\"").append(v).append('"')
            }
            append('}')
        }
        File(packageDir, HASH_FILE).writeText(json)
    }

    fun hashes(packageDir: File): Map<String, String> = runCatching {
        val text = File(packageDir, HASH_FILE).readText().trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return emptyMap()
        text.removePrefix("{").removeSuffix("}").split(',')
            .mapNotNull { part ->
                val k = part.substringBefore(':').trim().removeSurrounding("\"")
                val v = part.substringAfter(':').trim().removeSurrounding("\"")
                if (k.isNotEmpty() && v.isNotEmpty()) k to v else null
            }.toMap()
    }.getOrDefault(emptyMap())

    /** 复核模块:路径合法、文件存在、哈希与安装时一致;否则 null。 */
    fun verified(packageDir: File, path: String): ByteArray? {
        if (!PluginManifest.WASM.matches(path) || ".." in path) return null
        val expected = hashes(packageDir)[path] ?: return null
        val bytes = runCatching { File(packageDir, path).readBytes() }.getOrNull() ?: return null
        return bytes.takeIf { PluginScripts.hash(it) == expected }
    }
}
