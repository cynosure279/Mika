package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 全局允许列表:默认「全部」(包括以后新增的);关掉「全部」时转成当前全量的明确列表,可逐项取消。
 * 另给「全选 / 全不选」两个动作;后来新增的项不在 [known] 里,标「新」。
 * 技能页和 MCP 页共用,避免两处语义分叉。
 */
@Composable
internal fun AllowListSection(
    title: String,
    allLabel: String,
    note: String,
    all: Boolean,
    items: List<WorkspaceItem>,
    selected: Set<String>,
    known: Set<String>,
    onAll: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onItem: (String, Boolean) -> Unit,
) {
    GroupLabel(title)
    GroupCard {
        ListRow(null, allLabel, subtitle = stringResource(R.string.workspace_all_note), first = true) {
            MikaSwitch(all, onAll)
        }
        if (!all) {
            ListRow(null, stringResource(R.string.workspace_select_all), onClick = onSelectAll)
            ListRow(null, stringResource(R.string.workspace_select_none), onClick = onSelectNone)
            items.forEach { item ->
                val on = item.id in selected
                ListRow(
                    null, item.label,
                    detail = stringResource(R.string.workspace_new).takeIf { item.id !in known },
                    onClick = { onItem(item.id, !on) },
                ) {
                    Checkbox(on, onCheckedChange = { onItem(item.id, it) })
                }
            }
        }
    }
    Text(
        note, Modifier.padding(horizontal = 16.dp),
        style = MikaType.Hint, color = MikaTheme.colors.ink3,
    )
}
