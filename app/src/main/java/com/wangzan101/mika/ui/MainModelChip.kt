package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 输入框上方的小胶囊:这个对话下一次任务用哪个主模型。
 * 「跟随设置」时带上设置里的主模型名;覆盖在设置里已经不可用(warn 色)时显示回退后的名字。
 * 任务在跑时旁边写明「下一件事生效」,点开的是 [MainModelSheet]。
 */
@Composable
internal fun MainModelChip(ui: MainModelUi, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val switch = stringResource(R.string.chat_model_switch)
    val text = when {
        ui.fallback && !ui.label.isNullOrEmpty() -> stringResource(R.string.chat_model_chip_fallback, ui.label)
        ui.fallback -> stringResource(R.string.chat_model_chip_follow)
        ui.override == null && !ui.label.isNullOrEmpty() -> stringResource(R.string.chat_model_chip_follow_named, ui.label)
        ui.override == null -> stringResource(R.string.chat_model_chip_follow)
        ui.group -> stringResource(R.string.chat_model_chip_group, ui.label.orEmpty())
        else -> stringResource(R.string.chat_model_chip_model, ui.label.orEmpty())
    }
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.height(30.dp)
                .background(c.ink.copy(alpha = 0.06f), RoundedCornerShape(50))
                .clickable(onClickLabel = switch, onClick = onClick)
                .padding(start = 12.dp, end = 4.dp)
                .testTag("main-model-chip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text, style = MikaType.Caption, color = if (ui.fallback) c.warn else c.ink2,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            RowChevron()
        }
        if (busy) Text(stringResource(R.string.chat_model_next_task), style = MikaType.Hint, color = c.ink3, maxLines = 1)
    }
}

/**
 * 点模型 chip 从底部升起:跟随设置、可用的模型组、具体模型,最后是「更多模型」跳设置。
 * 选完马上关;只写这个对话的下一次任务,正在跑的任务不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainModelSheet(
    ui: MainModelUi,
    onPick: (ModelChoice?) -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MikaTheme.colors
    val groups = ui.options.filter { it.group }
    val models = ui.options.filterNot { it.group }
    fun pick(choice: ModelChoice?) { onPick(choice); onDismiss() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(bottom = 8.dp).testTag("main-model-sheet"),
        ) {
            Text(
                stringResource(R.string.chat_model_sheet_title),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp), style = MikaType.Body, color = c.ink,
            )
            Text(
                stringResource(R.string.chat_model_sheet_note),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                style = MikaType.Caption, color = c.ink3,
            )
            if (ui.fallback) {
                Text(
                    stringResource(R.string.chat_model_unavailable),
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    style = MikaType.Caption, color = c.warn,
                )
            }
            RadioRow(
                stringResource(R.string.chat_model_chip_follow),
                selected = ui.override == null,
                subtitle = ui.globalLabel?.let { stringResource(R.string.chat_model_follow_note, it) }
                    ?: stringResource(R.string.chat_model_follow_none),
                first = true,
                onClick = { pick(null) },
            )
            if (groups.isNotEmpty()) {
                GroupLabel(stringResource(R.string.models_groups), Modifier.padding(horizontal = 16.dp))
                groups.forEach { o ->
                    RadioRow(
                        o.title, selected = ui.override == o.choice,
                        subtitle = stringResource(R.string.chat_model_group_members, o.memberCount),
                        onClick = { pick(o.choice) },
                    )
                }
            }
            if (models.isNotEmpty()) {
                GroupLabel(stringResource(R.string.models_models), Modifier.padding(horizontal = 16.dp))
                models.forEach { o ->
                    RadioRow(
                        o.title, selected = ui.override == o.choice, subtitle = o.subtitle,
                        onClick = { pick(o.choice) },
                    )
                }
            }
            ListRow(null, stringResource(R.string.chat_model_more), onClick = { onMore(); onDismiss() }, trailing = { RowChevron() })
        }
    }
}
