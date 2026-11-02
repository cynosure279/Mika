package com.wangzan101.mika.plugin

import com.wangzan101.mika.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 插件 manifest(Schema 1);P1b 接受技能和工具,页面与设置留给 P2/P3。 */
@Serializable
data class PluginManifest(
    val schema: Int = 0,
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val kind: String = KIND_CAPABILITY,
    val minApp: String? = null,
    val author: String? = null,
    val description: String? = null,
    val permissions: List<String> = emptyList(),
    val tools: List<PluginToolDecl> = emptyList(),
    val skills: List<PluginSkillDecl> = emptyList(),
    val pages: List<PluginPageDecl> = emptyList(),
    val settings: List<PluginSettingDecl> = emptyList(),
    /** 连接级成本上限;工具自己的 maxCostUnits 是单次调用。 */
    val maxCostUnits: Int? = null,
) {
    /**
     * 校验 manifest;返回问题编码(空表示通过)。P2 认技能、工具、页面和非密设置:
     * 未知权限、工具缺 `sandbox.run`、页面缺 `page`、设置缺 `settings.plugin` 都拒绝安装。
     * [currentAppVersion] 用于 `minApp` 比较。
     */
    fun validate(currentAppVersion: String = BuildConfig.VERSION_NAME): List<String> = buildList {
        if (schema != SCHEMA) add(ERR_SCHEMA)
        if (!ID.matches(id)) add(ERR_ID)
        if (name.isBlank()) add(ERR_NAME)
        if (!SemVer.isValid(version)) add(ERR_VERSION)
        if (kind != KIND_CAPABILITY) add(ERR_KIND)
        minApp?.let {
            if (!SemVer.isValid(it)) add(ERR_MIN_APP)
            else if (SemVer.compare(it, currentAppVersion) > 0) add(ERR_MIN_APP_TOO_HIGH)
        }
        // 权限逐项 fail closed;P2 认工具、页面、非密设置用到的权限
        if (permissions.distinct().size != permissions.size || permissions.any { it !in PERMISSIONS }) add(ERR_PERMISSIONS)
        if (tools.isNotEmpty() && "sandbox.run" !in permissions) add(ERR_TOOLS_NEED_SANDBOX)
        if (pages.isNotEmpty() && "page" !in permissions) add(ERR_PAGES_NEED_PAGE)
        if (settings.isNotEmpty() && "settings.plugin" !in permissions) add(ERR_SETTINGS_NEED_PERMISSION)
        if (maxCostUnits != null && maxCostUnits < 0) add(ERR_TOOL_COST)
        val toolNames = mutableSetOf<String>()
        for (t in tools) {
            if (!TOOL_NAME.matches(t.name) || !toolNames.add(t.name)) add(ERR_TOOL)
            val script = t.script
            if (script == null || !SCRIPT.matches(script) || ".." in script || script.endsWith("/")) add(ERR_TOOL_SCRIPT)
            if (t.timeoutSec !in 1..600) add(ERR_TOOL_TIMEOUT)
            if (t.maxCostUnits != null && t.maxCostUnits < 0) add(ERR_TOOL_COST)
            val schema = t.inputSchema
            if (schema != null && schema["type"]?.jsonPrimitive?.contentOrNull != "object") add(ERR_TOOL_SCHEMA)
        }
        val seen = mutableSetOf<String>()
        for (s in skills) {
            if (!SKILL_NAME.matches(s.name) || !seen.add(s.name)) add(ERR_SKILL)
            // 技能目录只能是包内 skills/<name>,不允许 ..、绝对路径或别名
            if (s.path != "skills/${s.name}" || s.path.contains("..")) add(ERR_SKILL_PATH)
        }
        val pageIds = mutableSetOf<String>()
        for (p in pages) {
            if (!PAGE_ID.matches(p.id) || !pageIds.add(p.id)) add(ERR_PAGE)
            if (!PAGE_ENTRY.matches(p.entry) || ".." in p.entry || !p.entry.endsWith(".html")) add(ERR_PAGE_PATH)
        }
        val settingKeys = mutableSetOf<String>()
        for (s in settings) {
            if (!SETTING_KEY.matches(s.key) || !settingKeys.add(s.key)) add(ERR_SETTING)
            if (s.type !in SETTING_TYPES) add(if (s.type == "secret") ERR_SETTING_SECRET else ERR_SETTING)
        }
    }

    companion object {
        const val SCHEMA = 1
        const val KIND_CAPABILITY = "capability"

        /** 全局唯一的插件 id;同时用作 `files/plugins/<id>/` 目录名,正则保证拼不出路径。 */
        val ID = Regex("^[a-z0-9]+(\\.[a-z0-9_-]+)+$")
        val SKILL_NAME = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val TOOL_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
        val PAGE_ID = Regex("^[a-z][a-z0-9_-]{0,31}$")
        val PAGE_ENTRY = Regex("^pages/[A-Za-z0-9._/-]+\\.html$")
        val SETTING_KEY = Regex("^[a-z][a-z0-9_]{0,63}$")
        /** 包内脚本相对路径;只允许 scripts/ 下,不允许反斜杠、盘符和 ..。 */
        val SCRIPT = Regex("^scripts/[A-Za-z0-9._/-]+$")

        /** P2 认的权限;P3 的 phone.apps 还不能装。 */
        val PERMISSIONS = setOf("sandbox.run", "workspace.own", "settings.plugin", "network", "page")
        /** P2 只存非密设置;secret 等 P3。 */
        val SETTING_TYPES = setOf("string", "bool", "number")

        const val ERR_SCHEMA = "schema_unsupported"
        const val ERR_ID = "invalid_id"
        const val ERR_NAME = "missing_name"
        const val ERR_VERSION = "invalid_version"
        const val ERR_KIND = "unsupported_kind"
        const val ERR_MIN_APP = "invalid_min_app"
        const val ERR_MIN_APP_TOO_HIGH = "app_too_old"
        const val ERR_PERMISSIONS = "permissions_unsupported"
        const val ERR_TOOLS_NEED_SANDBOX = "tools_need_sandbox_run"
        const val ERR_PAGES_NEED_PAGE = "pages_need_page_permission"
        const val ERR_PAGE = "invalid_page"
        const val ERR_PAGE_PATH = "invalid_page_path"
        const val ERR_SETTINGS_NEED_PERMISSION = "settings_need_permission"
        const val ERR_SETTING = "invalid_setting"
        const val ERR_SETTING_SECRET = "setting_secret_unsupported"
        const val ERR_TOOL = "invalid_tool"
        const val ERR_TOOL_SCRIPT = "invalid_tool_script"
        const val ERR_TOOL_TIMEOUT = "invalid_tool_timeout"
        const val ERR_TOOL_SCHEMA = "invalid_tool_schema"
        const val ERR_TOOL_COST = "invalid_tool_cost"
        const val ERR_SKILL = "invalid_skill"
        const val ERR_SKILL_PATH = "invalid_skill_path"

        private val json = Json { ignoreUnknownKeys = true }

        /** 未知字段忽略(向前兼容);格式错误抛 [kotlinx.serialization.SerializationException]。 */
        fun parse(text: String): PluginManifest = json.decodeFromString(text)
    }
}

@Serializable
data class PluginToolDecl(
    val name: String = "",
    val description: String = "",
    val script: String? = null,
    val timeoutSec: Int = 60,
    val risk: String? = null,
    val annotations: Map<String, Boolean> = emptyMap(),
    val maxCostUnits: Int? = null,
    val inputSchema: JsonObject? = null,
)

@Serializable
data class PluginSkillDecl(val name: String = "", val path: String = "", val display: String? = null)

@Serializable
data class PluginPageDecl(val id: String = "", val entry: String = "", val title: String? = null)

@Serializable
data class PluginSettingDecl(val key: String = "", val type: String = "string", val label: String? = null)

/** 注册表里的一条;manifest 是内容真源,这里存启用状态与安装信息。 */
@Serializable
data class PluginRecord(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean = true,
    val installedAt: Long = 0,
    val skillNames: List<String> = emptyList(),
    val description: String? = null,
    val permissions: List<String> = emptyList(),
)

/** 语义化版本的解析与比较(只用于 `minApp`)。 */
internal object SemVer {
    private val RE = Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?" +
            "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
    )

    fun isValid(version: String): Boolean = RE.matches(version)

    /** -1/0/1;构建元数据不参与比较,预发布版本低于同版本正式版。 */
    fun compare(a: String, b: String): Int {
        val (ac, ap) = parts(a) ?: return a.compareTo(b)
        val (bc, bp) = parts(b) ?: return a.compareTo(b)
        for (i in 0 until 3) ac[i].compareTo(bc[i]).let { if (it != 0) return it.sign() }
        if (ap == null && bp == null) return 0
        if (ap == null) return 1
        if (bp == null) return -1
        val ai = ap.split('.')
        val bi = bp.split('.')
        for (i in 0 until minOf(ai.size, bi.size)) {
            val x = ai[i]
            val y = bi[i]
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val c = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c.sign()
        }
        return ai.size.compareTo(bi.size).sign()
    }

    private fun parts(version: String): Pair<List<Int>, String?>? {
        val m = RE.matchEntire(version) ?: return null
        return listOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) to
            m.groupValues[4].takeIf { it.isNotEmpty() }
    }

    private fun Int.sign(): Int = if (this < 0) -1 else if (this > 0) 1 else 0
}
