package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.wangzan101.mika.ui.components.GlassSurface
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 输入框上方的主模型入口；有任务在跑时明确说明改动从下一件开始。 */
@Composable
fun MainModelChip(ui: MainModelUi, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!ui.available) return
    val c = MikaTheme.colors
    Column(modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GlassSurface(modifier = Modifier.testTag("main-model-chip").clickable(onClick = onClick)) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(MikaIcons.Spark, stringResource(R.string.chat_model_switch), Modifier.size(13.dp), tint = c.ink2)
                Text(
                    when {
                        ui.fallback -> ui.label?.let { stringResource(R.string.chat_model_chip_fallback, it) }
                        ui.override == null -> ui.label?.let { stringResource(R.string.chat_model_chip_follow_named, it) }
                            ?: stringResource(R.string.chat_model_chip_follow)
                        ui.group -> ui.label?.let { stringResource(R.string.chat_model_chip_group, it) }
                        else -> ui.label?.let { stringResource(R.string.chat_model_chip_model, it) }
                    } ?: stringResource(R.string.chat_model_chip_follow),
                    style = MikaType.Hint,
                    color = c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(MikaIcons.Chevron, null, Modifier.size(11.dp), tint = c.ink3)
            }
        }
        if (busy) {
            Text(stringResource(R.string.chat_model_next_task), Modifier.padding(top = 3.dp), style = MikaType.Hint, color = c.ink3)
        }
    }
}

/** 本对话的主模型选择；失效选择留在对话文件里，但这里只列当前能解析的项。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainModelSheet(
    ui: MainModelUi,
    onPick: (ModelChoice?) -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MikaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = MikaShapes.SheetTop,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("main-model-sheet")) {
            Text(stringResource(R.string.chat_model_sheet_title), Modifier.padding(horizontal = 16.dp), style = MikaType.BodyStrong, color = c.ink)
            Text(stringResource(R.string.chat_model_sheet_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MikaType.Caption, color = c.ink3)
            if (ui.fallback) {
                Text(stringResource(R.string.chat_model_unavailable), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MikaType.Caption, color = c.warn)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                item(key = "follow") {
                    RadioRow(
                        title = stringResource(R.string.chat_model_chip_follow),
                        subtitle = ui.globalLabel?.let { stringResource(R.string.chat_model_follow_note, it) }
                            ?: stringResource(R.string.chat_model_follow_none),
                        selected = ui.override == null,
                        first = true,
                        onClick = { onPick(null); onDismiss() },
                    )
                }
                items(ui.options, key = { it.choice.toString() }) { option ->
                    RadioRow(
                        title = option.title,
                        subtitle = listOfNotNull(
                            option.subtitle,
                            if (option.group) stringResource(R.string.chat_model_group_members, option.memberCount) else null,
                        ).joinToString(" · ").ifEmpty { null },
                        selected = option.choice == ui.override,
                        onClick = { onPick(option.choice); onDismiss() },
                    )
                }
                item(key = "more") {
                    RadioRow(title = stringResource(R.string.chat_model_more), selected = false, onClick = { onMore(); onDismiss() })
                }
            }
        }
    }
}
