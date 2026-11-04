package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.permission.Access
import com.wangzan101.mika.core.permission.PermissionCatalog
import com.wangzan101.mika.core.permission.PermissionId
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme

private data class PermissionGroup(val title: Int, val icon: ImageVector, val matches: (PermissionId) -> Boolean)

private val GROUPS = listOf(
    PermissionGroup(R.string.permissions_group_phone, MikaIcons.Phone) { it.value.startsWith("phone.") },
    PermissionGroup(R.string.permissions_group_web, MikaIcons.Globe) { it.value.startsWith("web.") },
    PermissionGroup(R.string.permissions_group_sandbox, MikaIcons.Terminal) { it.value.startsWith("sandbox.") },
    PermissionGroup(R.string.permissions_group_core, MikaIcons.Gear) {
        it.value.startsWith("core.") || it.value.startsWith("skill.") || it.value.startsWith("memory.") ||
            it.value.startsWith("schedule.") || it.value.startsWith("child.") || it.value.startsWith("settings.")
    },
    PermissionGroup(R.string.permissions_group_extension, MikaIcons.Link) {
        it.value == "mcp" || it.value == "plugin" || it.value == "model.egress"
    },
)

/** 权限预览:按分类看当前默认值,以及从旧设置迁移来的授权。Phase 2a 已执行明确拒绝,Ask 仍放行。 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as MikaApp
    val state by app.permissions.state.collectAsState()

    PageScaffold(stringResource(R.string.permissions_title), onBack) {
        FooterNote(stringResource(R.string.permissions_preview_note))
        if (!state.available) {
            FooterNote(stringResource(R.string.permissions_unavailable), color = MikaTheme.colors.warn)
        }

        GroupLabel(stringResource(R.string.permissions_group_defaults))
        GroupCard {
            var first = true
            for (group in GROUPS) {
                val specs = PermissionCatalog.all.filter { group.matches(it.id) }
                if (specs.isEmpty()) continue
                val allow = specs.count { it.defaultAccess == Access.Allow }
                val ask = specs.count { it.defaultAccess == Access.Ask }
                val deny = specs.count { it.defaultAccess == Access.Deny }
                ListRow(
                    group.icon, stringResource(group.title),
                    stringResource(R.string.permissions_group_summary, specs.size, allow, ask, deny),
                    first = first,
                )
                first = false
            }
        }

        GroupLabel(stringResource(R.string.permissions_migrated_group))
        val grants = state.global
        if (grants.isEmpty()) {
            FooterNote(stringResource(R.string.permissions_migrated_none))
        } else {
            GroupCard {
                grants.forEachIndexed { i, grant ->
                    ListRow(MikaIcons.Shield, grant.permission.value, grant.resource.label(), first = i == 0) {
                        RowValue(accessLabel(grant.access), accessTone(grant.access))
                    }
                }
            }
        }
    }
}

@Composable
private fun accessLabel(access: Access): String = stringResource(
    when (access) {
        Access.Allow -> R.string.permissions_access_allow
        Access.Ask -> R.string.permissions_access_ask
        Access.Deny -> R.string.permissions_access_deny
    },
)

private fun accessTone(access: Access): ValueTone = when (access) {
    Access.Allow -> ValueTone.Ok
    Access.Ask -> ValueTone.Warn
    Access.Deny -> ValueTone.Bad
}
