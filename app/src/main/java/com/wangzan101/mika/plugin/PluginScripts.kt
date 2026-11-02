package com.wangzan101.mika.plugin

import com.wangzan101.mika.cap.sandbox.SandboxDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID

class PluginScriptException(val code: String, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: code, cause)

object PluginScriptErrors {
    const val CHANGED = "plugin_script_changed"
    const val UNSAFE = "plugin_script_unsafe"
    const val SYNC = "plugin_script_sync_failed"
}

/**
 * 包内脚本与安装时记录的 SHA-256 清单。清单在安装时写进包目录的 `scripts.sha256`,
 * 运行前按它复核;沙箱里只用同步过去的副本,不直接挂载包目录。
 */
object PluginScripts {
    const val HASH_FILE = "scripts.sha256"
    private val PATH = Regex("^scripts/[A-Za-z0-9._/-]+$")

    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 安装时把 scripts/ 下的普通文件全部记下来(相对包根的路径,和 manifest 里的写法一致)。 */
    fun collect(packageDir: File): Map<String, String> {
        val scripts = File(packageDir, "scripts")
        if (!scripts.isDirectory) return emptyMap()
        val out = sortedMapOf<String, String>()
        Files.walk(scripts.toPath()).use { paths ->
            for (p in paths) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    throw PluginScriptException(PluginScriptErrors.UNSAFE, "scripts contain a non-regular file")
                }
                val rel = "scripts/" + scripts.toPath().relativize(p).joinToString("/")
                out[rel] = hash(Files.readAllBytes(p))
            }
        }
        return out
    }

    fun writeHashes(packageDir: File, hashes: Map<String, String>) {
        File(packageDir, HASH_FILE).writeText(
            buildJsonObject { hashes.toSortedMap().forEach { (k, v) -> put(k, v) } }.toString(),
        )
    }

    fun hashes(packageDir: File): Map<String, String> = runCatching {
        val obj = Json.parseToJsonElement(File(packageDir, HASH_FILE).readText()) as? JsonObject ?: return emptyMap()
        obj.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { k to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** 复核某个脚本:路径合法、文件存在、哈希与安装时一致;否则 null。 */
    fun verified(packageDir: File, script: String): ByteArray? {
        if (!PATH.matches(script) || ".." in script) return null
        val expected = hashes(packageDir)[script] ?: return null
        val bytes = runCatching { File(packageDir, script).readBytes() }.getOrNull() ?: return null
        return bytes.takeIf { hash(it) == expected }
    }
}

/**
 * 把包里的 scripts/ 同步到沙箱旁的副本(绑定成 `/opt/mika/plugins/<id>`),只复制普通文件,
 * 先写临时目录再整体换上;每次运行前都会核对哈希,脚本被改过就拒绝。
 */
object PluginScriptsSync {
    suspend fun ensure(dirs: SandboxDirs, pluginId: String, packageDir: File) {
        val hashes = PluginScripts.hashes(packageDir)
        if (hashes.isEmpty()) return
        for (script in hashes.keys) {
            PluginScripts.verified(packageDir, script)
                ?: throw PluginScriptException(PluginScriptErrors.CHANGED, "plugin script changed: $script")
        }
        dirs.tree.shared {
            withContext(Dispatchers.IO) {
                dirs.plugins.mkdirs()
                val target = dirs.pluginScripts(pluginId)
                if (same(File(packageDir, "scripts"), File(target, "scripts"))) return@withContext
                val staging = File(dirs.plugins, "${target.name}.new-${UUID.randomUUID()}")
                try {
                    val copied = copyTree(File(packageDir, "scripts"), File(staging, "scripts"))
                    if (copied != hashes.size) {
                        throw PluginScriptException(PluginScriptErrors.CHANGED, "plugin scripts changed during sync")
                    }
                    try {
                        dirs.gate.deleteTree(dirs.plugins, target.name)
                    } catch (e: UnsupportedOperationException) {
                        // JVM 单测的 NioFdSys 在 Windows 上没有 unix 视图;手机上走关口
                        target.deleteRecursively()
                    }
                    if (!staging.renameTo(target)) {
                        throw PluginScriptException(PluginScriptErrors.SYNC, "could not move synced plugin scripts into place")
                    }
                } finally {
                    if (staging.exists()) staging.deleteRecursively()
                }
            }
        }
    }

    /** 源目录和目标目录的文件集合、内容一致才为 true;有链接或非普通文件按不一致处理。 */
    internal fun same(source: File, target: File): Boolean {
        val left = files(source) ?: return false
        val right = files(target) ?: return false
        return left.keys == right.keys && left.all { (k, v) -> right[k]?.contentEquals(v) == true }
    }

    /** 复制普通文件,保留相对路径;返回复制的文件数。符号链接一律拒绝。 */
    internal fun copyTree(source: File, target: File): Int {
        if (!source.isDirectory) return 0
        var count = 0
        Files.walk(source.toPath()).use { paths ->
            for (p in paths) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    throw PluginScriptException(PluginScriptErrors.UNSAFE, "scripts contain a non-regular file")
                }
                val rel = source.toPath().relativize(p).joinToString("/")
                val out = File(target, rel).canonicalFile
                if (!out.path.startsWith(target.canonicalPath + File.separator)) {
                    throw PluginScriptException(PluginScriptErrors.UNSAFE, "script escapes the package")
                }
                out.parentFile?.mkdirs()
                Files.copy(p, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                count++
            }
        }
        return count
    }

    private fun files(root: File): Map<String, ByteArray>? = runCatching {
        if (!root.isDirectory) return null
        val out = sortedMapOf<String, ByteArray>()
        Files.walk(root.toPath()).use { paths ->
            for (p in paths) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null
                out[root.toPath().relativize(p).joinToString("/")] = Files.readAllBytes(p)
            }
        }
        out
    }.getOrNull()
}
