package com.wangzan101.mika.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.currentModelAllowance
import com.wangzan101.mika.core.model.usage.ConversationUsage
import com.wangzan101.mika.settings.AllowanceRoute
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.ui.allowanceText
import com.wangzan101.mika.settings.ui.rememberAllowanceQuery
import com.wangzan101.mika.settings.ui.UsageCopy
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationUsageSheet(
    id: String?, onDismiss: () -> Unit,
    /** 「查看 Token 用量」:跳到设置里的 Token 用量页;不传时这行照常显示,点了只关面板。 */
    onOpenUsage: () -> Unit = {},
    /** 依赖注入留给设备测试;不传用应用里那份。 */
    store: ProviderStore? = null,
    /** 本对话自己存的主模型选择(输入框上方的主模型入口);不传按全局主模型算。 */
    mainOverride: ModelChoice? = null,
) {
    val c = MikaTheme.colors
    BackHandler(onBack = onDismiss)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        ConversationUsageContent(id, onDismiss, onOpenUsage, store, mainOverride)
    }
}

/** 面板正文:面板本身只是 ModalBottomSheet 的薄壳,正文单独一层让设备测试不进对话框也能测。 */
@Composable
internal fun ConversationUsageContent(
    id: String?, onDismiss: () -> Unit, onOpenUsage: () -> Unit, store: ProviderStore?, mainOverride: ModelChoice? = null,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val providers = remember(context, store) { store ?: ProviderStore.get(context) }
    val updates = remember(id, context) {
        val usage = if (id == null) flowOf(ConversationUsage()) else ConversationStore.shared(context).observeUsage(id)
        usage.map { Result.success(it) }.catch { emit(Result.failure(it)) }
    }
    val result by updates.collectAsState(initial = null)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp).testTag("conversation-usage-sheet")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chat_usage_title), Modifier.weight(1f), style = MikaType.Title, color = c.ink)
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("close-conversation-usage")) {
                Icon(MikaIcons.Close, stringResource(R.string.report_close), tint = c.ink2)
            }
        }
        val usage = result?.getOrNull()
        when {
            usage != null -> {
                ConversationTokens(usage)
                CurrentModelSection(providers, mainOverride)
                RequestContextDetails(usage)
            }
            result == null -> CircularProgressIndicator(Modifier.padding(top = 12.dp).size(24.dp))
            else -> Text(stringResource(R.string.usage_allowance_unavailable), color = c.ink2)
        }
        // 明细、历史和各家全量额度都留在「Token 用量」页,面板只给一个就近入口
        UsageDetailLink { onDismiss(); onOpenUsage() }
    }
}

@Composable
private fun ConversationTokens(usage: ConversationUsage) {
    val c = MikaTheme.colors
    val total = "${UsageCopy.compactTokens(usage.input + usage.output)} ${UsageCopy.UNIT}"
    Text(stringResource(if (usage.partial) R.string.chat_conversation_tokens_partial else R.string.chat_conversation_tokens, total),
        Modifier.padding(top = 16.dp), style = MikaType.BodyStrong, color = c.ink)
    CountRow(stringResource(R.string.metrics_input_tokens), UsageCopy.compactTokens(usage.input))
    CountRow(stringResource(R.string.metrics_output_tokens), UsageCopy.compactTokens(usage.output))
    CountRow(stringResource(R.string.chat_cache_rate), usage.cacheRate?.let(UsageCopy::percent) ?: stringResource(R.string.trace_unknown))
    if (usage.cacheRate != null) Text(stringResource(R.string.chat_cache_coverage, UsageCopy.compactTokens(usage.cached),
        UsageCopy.compactTokens(usage.cacheReportedInput)), style = MikaType.Hint, color = c.ink2)
}

/** 本对话的主模型和它服务商的余额:面板打开时现查,不缓存旧值;没有可用的主模型时整个区不显示。 */
@Composable
private fun CurrentModelSection(store: ProviderStore, mainOverride: ModelChoice?) {
    val roles by store.roles.collectAsState()
    val configs by store.configs.collectAsState()
    val groups by store.groups.collectAsState()
    val providers by store.providers.collectAsState()
    val revisions by store.providerKeyRevisions.collectAsState()
    // 收集这四个流只为在全局选择、配置、组、服务商变化时重算;取值与解析覆盖走 store 同一刻的快照
    val current = remember(roles, configs, groups, providers, mainOverride) { currentModelAllowance(store, mainOverride) }
        ?: return
    GroupLabel(stringResource(R.string.chat_current_model), Modifier.padding(top = 12.dp))
    Text(current.modelLabel, Modifier.padding(top = 2.dp), style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
    current.providers.forEach { provider ->
        AllowanceLine(provider, store, revisions[provider.id] ?: 0L)
    }
}

@Composable
private fun AllowanceLine(provider: Provider, store: ProviderStore, revision: Long) {
    val route = AllowanceRoute.forProvider(provider)
    val query = rememberAllowanceQuery(provider, route.source, store, revision)
    val current = query.value
    val value = when {
        query.loading -> stringResource(R.string.usage_allowance_loading)
        current != null -> allowanceText(current)
        route.source != null -> stringResource(R.string.usage_allowance_unavailable)
        route.selfHosted -> stringResource(R.string.usage_allowance_self_hosted)
        else -> stringResource(R.string.usage_allowance_unsupported)
    }
    CountRow(provider.name, value)
}

@Composable
private fun UsageDetailLink(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_usage_view_details)) { onClick() }
            .padding(vertical = 8.dp, horizontal = 2.dp)
            .testTag("conversation-usage-open-detail"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.chat_usage_view_details), Modifier.weight(1f), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        RowChevron()
    }
}

@Composable
private fun RequestContextDetails(usage: ConversationUsage) {
    val c = MikaTheme.colors
    GroupLabel(stringResource(R.string.chat_request_context), Modifier.padding(top = 12.dp))
    val snapshot = usage.latest
    if (snapshot == null) {
        Text(stringResource(R.string.chat_no_context), style = MikaType.Caption, color = c.ink2)
        return
    }
    Text(stringResource(R.string.chat_request_size, snapshot.model, snapshot.step, UsageCopy.compactTokens(snapshot.total),
        UsageCopy.compactTokens(snapshot.window.toLong())), style = MikaType.Caption, color = c.ink2)
    val parts = listOf(R.string.chat_context_instructions to snapshot.instructions, R.string.chat_context_dialogue to snapshot.dialogue,
        R.string.chat_context_references to snapshot.references, R.string.chat_context_tools to snapshot.toolsAndMedia,
        R.string.chat_context_runtime to snapshot.runtime)
    val colors = c.series + listOf(c.seriesOther, c.ink)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(8.dp).clip(CircleShape)) {
        parts.forEachIndexed { index, (_, amount) ->
            if (amount > 0) Spacer(Modifier.fillMaxHeight().weight(amount.toFloat()).background(colors[index]))
        }
    }
    parts.forEachIndexed { index, (label, amount) ->
        val share = if (snapshot.total == 0L) 0.0 else amount.toDouble() / snapshot.total
        CountRow(stringResource(label), "${UsageCopy.compactTokens(amount)} · ${UsageCopy.percent(share)}", colors[index])
    }
}

@Composable
private fun CountRow(label: String, value: String, color: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (color != null) Spacer(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, Modifier.weight(1f), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        Text(value, style = MikaType.Label, color = MikaTheme.colors.ink)
    }
}
