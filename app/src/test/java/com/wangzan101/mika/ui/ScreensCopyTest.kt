package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 「正在运行的虚拟屏」那一页的排序和文字(不碰界面,也不碰真任务)。 */
class ScreensCopyTest {
    @get:Rule val texts = ChineseTexts()

    private fun row(displayId: Int, key: String = "k$displayId") =
        ScreenRow(key, "c$displayId", "做点什么", displayId, TaskState.Running(0, "打开设置"))

    /** 建了屏的按屏号排在前;还没建屏(-1)的排在后面,彼此保持原来的先后。 */
    @Test fun screensAreOrderedByDisplayIdWithUnbuiltOnesLast() {
        val rows = listOf(row(-1, "a"), row(15), row(-1, "b"), row(12))
        assertEquals(listOf("k12", "k15", "a", "b"), orderedScreens(rows).map { it.key })
        assertEquals(emptyList<ScreenRow>(), orderedScreens(emptyList()))
    }

    /** 屏号:建了屏的说几号,没建的说「还没建屏」——不能显示成「屏幕 -1」。 */
    @Test fun screenLabelTellsAnUnbuiltScreenApart() {
        assertTrue(ScreensCopy.screen(12).contains("12"))
        assertEquals("还没建屏", ScreensCopy.screen(-1))
    }

    /** 每种状态都有一句;等着的、要你动手的和真在干活的分得开。 */
    @Test fun everyStateHasALabel() {
        // 在干活:沿用任务卡片那一套说法
        assertEquals("正在执行", ScreensCopy.state(TaskState.Running(1, "点了一下")))
        assertEquals("任务完成", ScreensCopy.state(TaskState.Succeeded("好了")))
        assertEquals("任务没有完成", ScreensCopy.state(TaskState.Failed("出错了", FailKind.Model)))
        assertEquals("任务已停止", ScreensCopy.state(TaskState.Cancelled))
        // 要你动手的几种说法一致
        val needsYou = listOf(
            TaskState.WaitingInput("密码是什么"),
            TaskState.WaitingTakeover("payment", "要你付款"),
        )
        assertEquals(listOf("等你处理", "等你处理"), needsYou.map(ScreensCopy::state))
        // 只是等条件(锁屏、助手没连上)的不算「要你处理」
        assertEquals("等待中", ScreensCopy.state(TaskState.Held(GateReason("locked", "锁屏了"))))
        assertEquals("等待中", ScreensCopy.state(TaskState.Idle))
    }

    /** 尺寸按屏自己的像素写;问不到时有一个说法,不留空。 */
    @Test fun sizeAndUnknownsHaveText() {
        val size = ScreensCopy.size(1280, 2856, 420)
        assertTrue(size, size.contains("1280"))
        assertTrue(size, size.contains("2856"))
        assertTrue(size, size.contains("420"))
        assertTrue(ScreensCopy.NO_SIZE.isNotBlank())
        assertTrue(ScreensCopy.UNKNOWN_APP.isNotBlank())
    }
}
