package com.wangzan101.mika.ui.components

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSpace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 子页面的骨架:页面底 + 带返回的顶栏 + 一列可滚动的内容(左右留 [MikaSpace.Page],避开导航栏)。
 * 内容里直接放 [GroupLabel] / [GroupCard] / [FooterNote]。
 * [scrollState] 不给时用本页自己组合里记的,本页一离开组合就丢;在一处切页显示、翻回来要停在原位的页面,
 * 由切页的一方经 [PageScrolls] 给进来。
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    viewportModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = scrollState ?: rememberScrollState()
    // 带着位置重新进入组合(切页回来)时,首帧布局里晚一帧才出来的内容(简约列表的卡片脚注,见 rememberCardNote)还没占位,
    // 可滚动上限偏小,带着的位置会被截短:等上限长回来、这期间没人动过,再放回原来的位置。
    // 带进来的位置要在组合里读:LaunchedEffect 的协程是派发后才跑的,那时首帧布局可能已经把它截短了
    val wanted = remember(scroll) { scroll.value }
    val placed = remember(scroll) { CompletableDeferred<Unit>() }
    if (scrollState != null) LaunchedEffect(scroll) {
        placed.await()
        val clippedTo = scroll.value
        if (wanted > clippedTo && clippedTo == scroll.maxValue) {
            withTimeoutOrNull(CLIPPED_RESTORE_MS) { snapshotFlow { scroll.maxValue }.first { it >= wanted } }
            if (scroll.value == clippedTo && scroll.maxValue >= wanted) scroll.scrollTo(wanted)
        }
    }
    PageBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(
                Modifier.weight(1f).fillMaxWidth().then(viewportModifier)
                    .onPlaced { placed.complete(Unit) }
                    .verticalScroll(scroll)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = MikaSpace.Page),
            ) {
                Spacer(Modifier.height(MikaSpace.S4))
                content()
                Spacer(Modifier.height(MikaSpace.S24))
            }
        }
    }
}

/** 带着的位置被首帧截短后,最多等这么久让晚一帧的内容出来;再久就当内容确实变短了。 */
private const val CLIPPED_RESTORE_MS = 500L

/**
 * 按页记着的滚动位置,给在一处切页显示的 [PageScaffold] 页面用:切到别的页时当前页离开组合,
 * 它自己 [rememberScrollState] 记的位置随之丢掉,切回来就回到顶部。位置放在切页的那一位
 * (翻页期间一直在组合里),各页用 [of] 取自己的交给 [PageScaffold] 的 `scrollState`;
 * 页面重建(换界面语言、进程被回收后恢复)时经 [Saver] 带回来。存下来的位置按枚举名记,一处翻页的页面要在同一个枚举里。
 */
class PageScrolls(saved: Map<String, Any?> = emptyMap()) {
    /** 重建前各页存下来的位置,那一页第一次被取到时顶上。 */
    private val pending = saved.mapValues { (_, position) -> (position as? Number)?.toInt() ?: 0 }.toMutableMap()
    private val states = HashMap<Enum<*>, ScrollState>()

    /** [page] 这一页的滚动状态,第一次取时新建。 */
    fun of(page: Enum<*>): ScrollState = states.getOrPut(page) {
        ScrollState(0).also { it.dispatchRawDelta((pending.remove(page.name) ?: 0).toFloat()) }
    }

    companion object {
        val Saver: Saver<PageScrolls, Any> = mapSaver(
            save = { scrolls -> scrolls.states.mapKeys { (page, _) -> page.name }.mapValues { (_, state) -> state.value } },
            restore = { PageScrolls(it) },
        )
    }
}
