package com.wangzan101.mika.settings

import com.wangzan101.mika.chat.WorkspaceChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 工作区全局允许列表。[skillsAll] / [mcpAll] 为真表示全部可用,包括以后新增的;
 * 用户改过任意一项后为假,集合里是明确选中的项(空集 = 全关)。
 * [skillsKnown] / [mcpKnown] 是用户第一次自定义时见过的项,用来把后来新增的标成「新」。
 */
data class WorkspaceAccess(
    val skillsAll: Boolean = true,
    val mcpAll: Boolean = true,
    val skills: Set<String> = emptySet(),
    val mcpServers: Set<String> = emptySet(),
    val skillsKnown: Set<String> = emptySet(),
    val mcpKnown: Set<String> = emptySet(),
)

/** 这个对话实际能用的技能名;null = 全部(包括以后新增)。 */
fun WorkspaceAccess.skillsFor(choice: WorkspaceChoice): Set<String>? = when (choice) {
    WorkspaceChoice.FollowGlobal -> if (skillsAll) null else skills
    WorkspaceChoice.All -> null
    is WorkspaceChoice.Only -> choice.ids
}

/** 这个对话实际能连的 MCP server 编号;null = 全部已启用项。 */
fun WorkspaceAccess.mcpFor(choice: WorkspaceChoice): Set<String>? = when (choice) {
    WorkspaceChoice.FollowGlobal -> if (mcpAll) null else mcpServers
    WorkspaceChoice.All -> null
    is WorkspaceChoice.Only -> choice.ids
}

/**
 * 全局允许列表,存成 `files/settings/workspace-access.json`;没有、读不懂按「全部可用」。
 * 先写临时文件再改名,写到一半断电不会留下坏文件。
 */
class WorkspaceAccessStore(private val file: File) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<WorkspaceAccess> = _state.asStateFlow()

    fun current(): WorkspaceAccess = _state.value

    /** 全选 / 全不选技能;全选包括以后新增的,全不选是明确的空集。[allNames] 用来记下此刻见过的项。 */
    @Synchronized fun setAllSkills(all: Boolean, allNames: Set<String> = emptySet()) = update { s ->
        s.copy(
            skillsAll = all, skills = emptySet(),
            skillsKnown = (s.skillsKnown + allNames).ifEmpty { s.skills },
        )
    }

    /** 关掉「全部技能」时转成当前全量的明确列表,让用户逐项取消;不会被误当成「一个都不要」。 */
    @Synchronized fun customizeSkills(allNames: Set<String>) = update { s ->
        s.copy(skillsAll = false, skills = allNames, skillsKnown = s.skillsKnown + allNames)
    }

    /** 勾选或取消一个技能;[allNames] 是此刻设置里能看到的全部技能,用来在「全部」状态下算出取消后的明确列表。 */
    @Synchronized fun setSkill(name: String, allowed: Boolean, allNames: Set<String>) = update { s ->
        val known = s.skillsKnown + allNames
        when {
            s.skillsAll && allowed -> s.copy(skillsKnown = known)
            s.skillsAll -> s.copy(skillsAll = false, skills = allNames - name, skillsKnown = known)
            allowed -> s.copy(skills = s.skills + name, skillsKnown = known)
            else -> s.copy(skills = s.skills - name, skillsKnown = known)
        }
    }

    @Synchronized fun setAllMcp(all: Boolean, allIds: Set<String> = emptySet()) = update { s ->
        s.copy(
            mcpAll = all, mcpServers = emptySet(),
            mcpKnown = (s.mcpKnown + allIds).ifEmpty { s.mcpServers },
        )
    }

    /** 关掉「全部 MCP server」时转成当前全量的明确列表。 */
    @Synchronized fun customizeMcp(allIds: Set<String>) = update { s ->
        s.copy(mcpAll = false, mcpServers = allIds, mcpKnown = s.mcpKnown + allIds)
    }

    @Synchronized fun setMcpServer(id: String, allowed: Boolean, allIds: Set<String>) = update { s ->
        val known = s.mcpKnown + allIds
        when {
            s.mcpAll && allowed -> s.copy(mcpKnown = known)
            s.mcpAll -> s.copy(mcpAll = false, mcpServers = allIds - id, mcpKnown = known)
            allowed -> s.copy(mcpServers = s.mcpServers + id, mcpKnown = known)
            else -> s.copy(mcpServers = s.mcpServers - id, mcpKnown = known)
        }
    }

    private fun update(change: (WorkspaceAccess) -> WorkspaceAccess) = persist(change(current()))

    private fun persist(next: WorkspaceAccess) {
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("version", 1)
            put("skillsAll", next.skillsAll)
            put("mcpAll", next.mcpAll)
            put("skills", strings(next.skills))
            put("mcpServers", strings(next.mcpServers))
            put("skillsKnown", strings(next.skillsKnown))
            put("mcpKnown", strings(next.mcpKnown))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        _state.value = next
    }

    private fun load(): WorkspaceAccess {
        if (!file.exists()) return WorkspaceAccess()
        return runCatching {
            val o = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return WorkspaceAccess()
            fun bool(key: String, default: Boolean) = (o[key] as? JsonPrimitive)?.booleanOrNull ?: default
            fun set(key: String) = (o[key] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty) }.toSet()
            WorkspaceAccess(
                skillsAll = bool("skillsAll", true), mcpAll = bool("mcpAll", true),
                skills = set("skills"), mcpServers = set("mcpServers"),
                skillsKnown = set("skillsKnown"), mcpKnown = set("mcpKnown"),
            )
        }.getOrDefault(WorkspaceAccess())
    }

    private fun strings(values: Set<String>) = JsonArray(values.sorted().map(::JsonPrimitive))
}
