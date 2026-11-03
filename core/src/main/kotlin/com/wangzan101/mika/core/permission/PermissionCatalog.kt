package com.wangzan101.mika.core.permission

import com.wangzan101.mika.core.tool.ToolInfo
import com.wangzan101.mika.core.tool.Verb

/**
 * 工具到权限的映射。按「能力 + 动作」而不是工具名,改名不用跟着改;核心工具没有能力,按动作分。
 * 新增工具时 [PermissionCatalogTest] 的穷尽性测试会失败,提醒来这里登记。
 *
 * 能力 id 写成字符串而不是 import 各能力的常量:核心包不能引用能力包(见 PackageDependencyTest)。
 * 能力改了 id 时,穷尽性测试会因为映射不到而失败。
 */
object PermissionCatalog {
    private const val PHONE_CAPABILITY = "phone"
    private const val WEB_CAPABILITY = "web"
    private const val SANDBOX_CAPABILITY = "sandbox"

    // 手机
    val PHONE_OBSERVE = PermissionId("phone.observe")
    val PHONE_ACT = PermissionId("phone.act")
    val PHONE_APP = PermissionId("phone.app")
    val PHONE_SHARE = PermissionId("phone.share")
    val PHONE_SYSTEM = PermissionId("phone.system")
    val PHONE_APPS = PermissionId("phone.apps")
    val PHONE_ALARM = PermissionId("phone.alarm")
    val PHONE_INSTALL = PermissionId("phone.install")
    val PHONE_UNLOCK = PermissionId("phone.unlock")
    val PHONE_SETTINGS = PermissionId("phone.settings")

    // 网页
    val WEB_READ = PermissionId("web.read")
    val WEB_SCRIPT = PermissionId("web.script")
    val WEB_TRANSFER = PermissionId("web.transfer")

    // 沙箱
    val SANDBOX_EXEC = PermissionId("sandbox.exec")
    val SANDBOX_FILES = PermissionId("sandbox.files")
    val SANDBOX_NETWORK = PermissionId("sandbox.network")
    val SANDBOX_MOUNT = PermissionId("sandbox.mount")

    // 核心
    val CORE_INTERACT = PermissionId("core.interact")
    val CORE_KNOWLEDGE = PermissionId("core.knowledge")
    val SKILL_WRITE = PermissionId("skill.write")
    val MEMORY_READ = PermissionId("memory.read")
    val MEMORY_WRITE = PermissionId("memory.write")
    val SCHEDULE_WRITE = PermissionId("schedule.write")
    val CHILD_SPAWN = PermissionId("child.spawn")
    val SETTINGS_SELF = PermissionId("settings.self")

    // 扩展
    val MCP = PermissionId("mcp")
    val PLUGIN = PermissionId("plugin")
    val MODEL_EGRESS = PermissionId("model.egress")

    val all: List<PermissionSpec> = listOf(
        PermissionSpec(PHONE_OBSERVE, PermissionRisk.Read, Access.Allow, description = "Read the phone screen"),
        PermissionSpec(PHONE_ACT, PermissionRisk.Change, Access.Allow, description = "Tap, type and navigate on the phone"),
        PermissionSpec(PHONE_APP, PermissionRisk.Change, Access.Allow, ResourceKind.AppPackage, "Use one app"),
        PermissionSpec(PHONE_SHARE, PermissionRisk.External, Access.Ask, ResourceKind.AppPackage, "Share text to one app"),
        PermissionSpec(PHONE_SYSTEM, PermissionRisk.External, Access.Ask, description = "Run a system command on the phone"),
        PermissionSpec(PHONE_APPS, PermissionRisk.Read, Access.Allow, description = "List installed apps"),
        PermissionSpec(PHONE_ALARM, PermissionRisk.Change, Access.Allow, description = "Set alarms, timers and calendar events"),
        PermissionSpec(PHONE_INSTALL, PermissionRisk.Irreversible, Access.Deny, description = "Install or uninstall apps"),
        PermissionSpec(PHONE_UNLOCK, PermissionRisk.Irreversible, Access.Deny, description = "Unlock the phone"),
        PermissionSpec(PHONE_SETTINGS, PermissionRisk.Irreversible, Access.Ask, description = "Change system settings"),

        PermissionSpec(WEB_READ, PermissionRisk.Read, Access.Allow, description = "Read pages in the built-in browser"),
        PermissionSpec(WEB_SCRIPT, PermissionRisk.Change, Access.Allow, description = "Run scripts or interact with a page"),
        PermissionSpec(WEB_TRANSFER, PermissionRisk.External, Access.Ask, description = "Upload or download files"),

        PermissionSpec(SANDBOX_EXEC, PermissionRisk.Change, Access.Allow, description = "Run commands in the sandbox"),
        PermissionSpec(SANDBOX_FILES, PermissionRisk.Change, Access.Allow, ResourceKind.Directory, "Read and write sandbox files"),
        PermissionSpec(SANDBOX_NETWORK, PermissionRisk.External, Access.Ask, description = "Let the sandbox reach the network"),
        PermissionSpec(SANDBOX_MOUNT, PermissionRisk.Irreversible, Access.Ask, ResourceKind.Directory, "Mount an existing directory"),

        PermissionSpec(CORE_INTERACT, PermissionRisk.Change, Access.Allow, description = "Ask the user, wait, or hand over"),
        PermissionSpec(CORE_KNOWLEDGE, PermissionRisk.Read, Access.Allow, description = "Read knowledge and skills"),
        PermissionSpec(SKILL_WRITE, PermissionRisk.Change, Access.Allow, description = "Save a personal skill"),
        PermissionSpec(MEMORY_READ, PermissionRisk.Read, Access.Allow, description = "Read memory and past runs"),
        PermissionSpec(MEMORY_WRITE, PermissionRisk.Change, Access.Allow, description = "Write memory or the profile"),
        PermissionSpec(SCHEDULE_WRITE, PermissionRisk.Change, Access.Allow, description = "Create a scheduled task"),
        PermissionSpec(CHILD_SPAWN, PermissionRisk.Change, Access.Allow, description = "Start a child task"),
        PermissionSpec(SETTINGS_SELF, PermissionRisk.Change, Access.Allow, description = "Read and change Mika's own settings"),

        PermissionSpec(MCP, PermissionRisk.External, Access.Allow, ResourceKind.McpServer, "Use an MCP server"),
        PermissionSpec(PLUGIN, PermissionRisk.External, Access.Allow, ResourceKind.Plugin, "Use a plugin"),
        PermissionSpec(MODEL_EGRESS, PermissionRisk.External, Access.Allow, description = "Send content to a model provider"),
    )

    private val byId: Map<PermissionId, PermissionSpec> = all.associateBy { it.id }

    fun spec(id: PermissionId): PermissionSpec? = byId[id]

    /**
     * 一个工具属于哪个权限。不认识的工具返回 null;调用方按拒绝处理,不要默认放行。
     */
    fun permissionFor(tool: ToolInfo): PermissionId? = when (val capability = tool.capability) {
        PHONE_CAPABILITY -> phonePermission(tool.verb)
        WEB_CAPABILITY -> webPermission(tool.verb)
        SANDBOX_CAPABILITY -> sandboxPermission(tool.verb)
        null -> corePermission(tool.verb)
        else -> if (capability.startsWith("mcp:")) MCP else if (capability.startsWith("plugin:")) PLUGIN else null
    }

    /** 一次工具调用的资源:目前只有 MCP 能按 server 区分,其余是全局。手机按 App 的资源要知道执行时的前台包名,Phase 2 再传进来。 */
    fun resourceFor(tool: ToolInfo): PermissionResource {
        val capability = tool.capability ?: return PermissionResource.Global
        if (capability.startsWith("mcp:")) return PermissionResource.McpServer(capability.removePrefix("mcp:"))
        if (capability.startsWith("plugin:")) return PermissionResource.Plugin(capability.removePrefix("plugin:"))
        return PermissionResource.Global
    }

    /**
     * 观察模式下的结论:按清单默认值算出「本会怎么判」,不改执行。没有映射的工具返回 null(穷尽性测试应拦住)。
     */
    fun observe(tool: ToolInfo): PermissionObservation? {
        val permission = permissionFor(tool) ?: return null
        val spec = spec(permission) ?: return null
        return PermissionObservation(permission, resourceFor(tool), decisionOf(spec.defaultAccess), spec.description)
    }

    private fun decisionOf(access: Access): String = when (access) {
        Access.Allow -> "would_allow"
        Access.Ask -> "would_ask"
        Access.Deny -> "would_deny"
    }

    private fun phonePermission(verb: Verb): PermissionId? = when (verb) {
        Verb.Observe, Verb.ListElements, Verb.Screenshot, Verb.Describe, Verb.FindVisually, Verb.Diagnose,
        Verb.Query, Verb.Collect, Verb.PhoneFind, Verb.PhoneExtract, Verb.PhoneEvent, Verb.WaitFor ->
            PHONE_OBSERVE

        Verb.Tap, Verb.Scroll, Verb.Swipe, Verb.TypeText, Verb.ClearText, Verb.Back, Verb.PressKey,
        Verb.LaunchApp, Verb.OpenLink, Verb.SetWheel, Verb.Interact, Verb.AppAfterTask,
        Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneRun ->
            PHONE_ACT

        Verb.PhoneCommand -> PHONE_SYSTEM
        Verb.ShareText -> PHONE_SHARE
        Verb.ListApps -> PHONE_APPS
        Verb.SetAlarm, Verb.SetTimer, Verb.ShowAlarms, Verb.AddCalendarEvent -> PHONE_ALARM
        else -> null
    }

    private fun webPermission(verb: Verb): PermissionId? = when (verb) {
        Verb.Observe, Verb.ListElements, Verb.Screenshot, Verb.Describe, Verb.FindVisually, Verb.Diagnose,
        Verb.Navigate, Verb.ReadPage, Verb.SiteCommand, Verb.Tabs, Verb.Capture, Verb.WaitFor,
        Verb.KeepOpen, Verb.SiteMode, Verb.Back ->
            WEB_READ

        Verb.Tap, Verb.Scroll, Verb.Swipe, Verb.TypeText, Verb.ClearText, Verb.PressKey,
        Verb.EvaluateJs, Verb.Script, Verb.Interact ->
            WEB_SCRIPT

        Verb.Upload, Verb.Downloads -> WEB_TRANSFER
        else -> null
    }

    private fun sandboxPermission(verb: Verb): PermissionId? = when (verb) {
        Verb.RunCommand, Verb.RunBundledScript -> SANDBOX_EXEC
        Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile -> SANDBOX_FILES
        else -> null
    }

    private fun corePermission(verb: Verb): PermissionId? = when (verb) {
        Verb.Wait, Verb.ViewPastScreens, Verb.ViewUserScreen, Verb.AskUser, Verb.RequestConfirmation,
        Verb.HandToUser, Verb.HandleObstacle, Verb.EndNotDone ->
            CORE_INTERACT

        Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead -> CORE_KNOWLEDGE
        Verb.SkillSave -> SKILL_WRITE
        Verb.MemorySearch, Verb.HistoryRead -> MEMORY_READ
        Verb.MemorySave, Verb.ProfileEdit -> MEMORY_WRITE
        Verb.ScheduleTask -> SCHEDULE_WRITE
        Verb.SpawnAgent -> CHILD_SPAWN
        Verb.SettingsList, Verb.SettingsSet -> SETTINGS_SELF
        else -> null
    }
}
