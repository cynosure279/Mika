package com.wangzan101.mika.ui

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.capsule.previewFrames
import com.wangzan101.mika.capsule.sameFrame
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

/**
 * 这一页列的一块虚拟屏:[displayId] 为 -1 是还没建屏(排队中,或者纯对话、还没打开过 App)。
 * [foreground] 是助手报表上最上面那个 App 的显示名;还没查到、或助手没连着时为 null。
 */
data class ScreenRow(
    val key: String,
    val conversationId: String,
    val goal: String,
    val displayId: Int,
    val state: TaskState,
    val foreground: String? = null,
)

/** 列表的顺序:建了屏的按屏号在前,还没建屏的排在后面(保持原来的先后)。 */
fun orderedScreens(rows: List<ScreenRow>): List<ScreenRow> =
    rows.filter { it.displayId >= 0 }.sortedBy { it.displayId } + rows.filter { it.displayId < 0 }

/**
 * 正在运行的虚拟屏:每个任务一块,列屏号、尺寸、前景 App、状态,能展开看实时画面。
 * 只读——停任务、挪 App 都在对话页和胶囊里做,这一页不重复那些入口。
 *
 * 数据取自各任务自己的流(屏号、状态、画面),不是服务对外的「显示中的任务」那一份:
 * 并行时没在显示的那几块屏照样是实的。
 */
@Composable
fun ScreensScreen(taskState: TaskCenterState, onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
    val context = LocalContext.current
    val runs by taskState.runs.collectAsState()
    var rows by remember { mutableStateOf<List<ScreenRow>>(emptyList()) }
    LaunchedEffect(runs) {
        val list = runs
        if (list.isEmpty()) {
            rows = emptyList()
            return@LaunchedEffect
        }
        // 每个任务的状态和屏号各有自己的流:合起来,哪一块屏变了都跟着重排
        combine(list.map { run -> combine(run.state, run.displayId) { s, id -> ScreenRow(run.key, run.conversationId, run.goal, id, s) } }) { it.toList() }
            .collect { rows = it }
    }

    val ids = remember(rows) { rows.filter { it.displayId >= 0 }.map { it.displayId } }
    var foreground by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var reload by remember { mutableStateOf(0L) }
    LaunchedEffect(ids, reload) { foreground = foregroundOf(context, ids) }

    ScreensContent(
        rows = orderedScreens(rows).map { r -> r.copy(foreground = foreground[r.displayId]) },
        onBack = onBack,
        onOpenConversation = onOpenConversation,
        onRefresh = { reload++ },
        frames = { key -> runs.firstOrNull { it.key == key }?.frames },
    )
}

/**
 * 不带状态的那一页,便于测试:[rows] 已经排好序、已经填好前景 App。
 * [frames] 按任务编号给出那块屏的画面;给了才在展开时订阅。
 */
@Composable
fun ScreensContent(
    rows: List<ScreenRow>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onRefresh: () -> Unit = {},
    frames: (String) -> StateFlow<Bitmap?>? = { null },
) {
    PageScaffold(ScreensCopy.TITLE, onBack) {
        if (rows.isEmpty()) {
            EmptyNote(ScreensCopy.EMPTY)
            return@PageScaffold
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GroupLabel(ScreensCopy.GROUP, Modifier.weight(1f))
            // 前景 App 是问一次记一次的:换了个 App 之后点它重问
            BarIconButton(MikaIcons.Refresh, ScreensCopy.REFRESH, onRefresh, modifier = Modifier.testTag("screens-refresh"))
        }
        rows.forEach { row -> ScreenCard(row, frames(row.key), onOpenConversation) }
        FooterNote(ScreensCopy.NOTE)
    }
}

/** 一块屏:屏号、目标、状态、尺寸、前景 App;点「看画面」展开这块屏的实时画面。 */
@Composable
private fun ScreenCard(row: ScreenRow, frames: StateFlow<Bitmap?>?, onOpenConversation: (String) -> Unit) {
    val c = MikaTheme.colors
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    GroupCard(Modifier.padding(top = 4.dp)) {
        ListRow(
            MikaIcons.Screens, ScreensCopy.screen(row.displayId), row.goal.oneLine(),
            first = true, onClick = { onOpenConversation(row.conversationId) },
        ) { RowValue(ScreensCopy.state(row.state)) }
        ListRow(null, ScreensCopy.SIZE) { RowValue(displaySize(LocalContext.current, row.displayId)) }
        if (row.displayId >= 0) {
            ListRow(null, ScreensCopy.FOREGROUND) { RowValue(row.foreground ?: ScreensCopy.UNKNOWN_APP) }
            Text(
                if (open) ScreensCopy.HIDE_PREVIEW else ScreensCopy.PREVIEW,
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    .clickable { open = !open }.testTag("screens-preview-${row.key}"),
                fontSize = 13.sp, color = c.accent,
            )
            if (open && frames != null) LiveFrame(frames)
        }
    }
}

/**
 * 这块屏的实时画面:每秒最多 5 张,和上一张一样就不换(见 [previewFrames])。收起时不订阅。
 * 点一下放大到整屏,可以捏合缩放、拖动——字小的界面在这一小块里看不清。
 */
@Composable
private fun LiveFrame(source: StateFlow<Bitmap?>) {
    val frames = remember(source) { source.filterNotNull().previewFrames(same = ::sameFrame) }
    val frame by frames.collectAsState(initial = null)
    val bmp = frame ?: return
    var zoom by remember { mutableStateOf(false) }
    Image(
        bmp.asImageBitmap(), stringResource(R.string.screens_zoom),
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .height(260.dp).clip(RoundedCornerShape(10.dp))
            .clickable { zoom = true }.testTag("screens-zoom"),
        contentScale = ContentScale.Fit,
    )
    if (zoom) FrameZoom(bmp) { zoom = false }
}

/** 放大的那块屏:整屏、捏合缩放(1–6 倍)、拖动看边角;点外面或按返回关掉。 */
@Composable
private fun FrameZoom(frame: Bitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(MikaTheme.colors.bg.copy(alpha = 0.96f)).clickable { onDismiss() }
                .testTag("screens-zoom-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                frame.asImageBitmap(), null,
                Modifier.fillMaxWidth().transformable(state)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** 这块屏现在的像素尺寸;问不到(屏刚拆、系统不给看)时返回 null 的展示。 */
private fun displaySize(context: Context, displayId: Int): String {
    if (displayId < 0) return ScreensCopy.NO_SIZE
    val m = DisplayMetrics()
    val display: Display? = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
    if (display == null) return ScreensCopy.NO_SIZE
    runCatching { display.getRealMetrics(m) }
    return if (m.widthPixels > 0) ScreensCopy.size(m.widthPixels, m.heightPixels, m.densityDpi) else ScreensCopy.NO_SIZE
}

/** 问内置助手每块屏最上面是哪个 App,再换成显示名。助手没连着就都不查(这只影响前景那一行)。 */
private suspend fun foregroundOf(context: Context, ids: List<Int>): Map<Int, String> {
    if (ids.isEmpty()) return emptyMap()
    val hub = runCatching { PhoneServices.of(context).hub }.getOrNull() ?: return emptyMap()
    return withContext(Dispatchers.IO) {
        ids.mapNotNull { id ->
            val pkg = runCatching {
                val shell = hub.shell.value ?: return@runCatching null
                val task = shell.topTaskId(id)
                if (task < 0) null else shell.packageOfTask(task)
            }.getOrNull()
            pkg?.let { id to ConfirmationNotifier.appLabel(context, it) }
        }.toMap()
    }
}

/** 目标在列表里只占一行:换行和连续空白并成一个空格。 */
private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

/** 页面上的全部文字。 */
object ScreensCopy {
    val TITLE: String get() = tr(R.string.screens_title)
    val GROUP: String get() = tr(R.string.screens_group)
    val EMPTY: String get() = tr(R.string.screens_empty)
    val NOTE: String get() = tr(R.string.screens_note)
    val REFRESH: String get() = tr(R.string.refresh)
    val SIZE: String get() = tr(R.string.screens_size)
    val FOREGROUND: String get() = tr(R.string.screens_foreground)
    val UNKNOWN_APP: String get() = tr(R.string.screens_unknown_app)
    val NO_SIZE: String get() = tr(R.string.screens_no_size)
    val PREVIEW: String get() = tr(R.string.screens_preview)
    val HIDE_PREVIEW: String get() = tr(R.string.screens_hide_preview)

    fun screen(displayId: Int) = if (displayId < 0) tr(R.string.screens_no_display) else tr(R.string.screens_display, displayId)
    fun size(width: Int, height: Int, dpi: Int) = tr(R.string.screens_size_value, width, height, dpi)

    fun state(state: TaskState): String = when (state) {
        TaskState.Idle -> tr(R.string.screens_waiting)
        is TaskState.Running -> tr(R.string.task_running)
        is TaskState.Held -> tr(R.string.screens_waiting)
        is TaskState.WaitingTakeover, is TaskState.WaitingInput, is TaskState.WaitingConfirmation -> tr(R.string.screens_needs_you)
        is TaskState.Succeeded -> tr(R.string.task_done)
        is TaskState.Failed -> tr(R.string.task_not_done)
        TaskState.Cancelled -> tr(R.string.task_stopped)
    }
}
