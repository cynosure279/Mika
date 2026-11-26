package com.wangzan101.mika.capsule

import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.GestureTapTestActivity
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** 真实的两个浮层窗口:收起后无障碍节点消失还不够,系统画布上也不能留下半张卡片。 */
@RunWith(AndroidJUnit4::class)
class CapsuleWindowDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MikaApp
    private val flags = FeatureFlags.get(app)

    @Test fun closedCardLeavesNoPixelsBehind() {
        val service = DeviceSupport.service()
        val oldMode = flags.capsuleMode()
        val oldQuit = flags.quitRequested()
        val oldHideAtEdge = flags.capsuleHideAtEdge()
        try {
            flags.setQuitRequested(false)
            flags.setCapsuleHideAtEdge(false)
            flags.setCapsuleMode(CapsuleMode.Always)
            instrumentation.runOnMainSync { app.capsule.resume() } // 胶囊控制器只在主线程上动窗口
            DeviceSupport.startTestActivity(Display.DEFAULT_DISPLAY, GestureTapTestActivity::class.java)
            assertNotNull("test page did not open", pollUntil(5_000) { findText(service, "taps:0") })
            Thread.sleep(300) // 等页面进入动画结束,再记录用于判断残帧的静态基准
            assertNotNull("idle capsule did not appear", pollUntil(15_000) { find(service, CapsuleCopy.IDLE) })
            Thread.sleep(3_500) // 等胶囊入场动效(约 272ms)结束并走完空闲淡出(CapsuleFade.IDLE_MS=3s,alpha 1→0.4);否则基准是全不透明,而关卡片耗时>3s 时胶囊已淡出,会被误判成残帧
            val baseline = screenshot()

            assertTrue("idle capsule was not clickable", click(service, CapsuleCopy.IDLE))
            assertNotNull("card did not open", pollUntil(5_000) { find(service, CardCopy.COLLAPSE) })
            val opened = pollUntil(5_000) { changedFraction(baseline, screenshot()).takeIf { it > 0.002f } }
            assertNotNull("card did not visibly change the page", opened)

            assertTrue("card close button was not clickable", click(service, CardCopy.COLLAPSE))
            assertNotNull("card accessibility nodes did not close", pollUntil(5_000) {
                true.takeIf { find(service, CardCopy.COLLAPSE) == null }
            })
            val remaining = pollUntil(5_000) { changedFraction(baseline, screenshot()).takeIf { it <= 0.002f } }
            assertNotNull("card pixels remained after closing", remaining)
            Thread.sleep(300)
            assertTrue(
                "card pixels returned after closing",
                changedFraction(baseline, screenshot()) <= 0.002f,
            )
        } finally {
            find(service, CardCopy.COLLAPSE)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            flags.setCapsuleHideAtEdge(oldHideAtEdge)
            flags.setCapsuleMode(oldMode)
            flags.setQuitRequested(oldQuit)
            instrumentation.runOnMainSync { app.capsule.resume() } // 胶囊控制器只在主线程上动窗口
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /** 展开后的卡片要落到完整几何:整张在屏内、水平居中、高度到位,不停在半开的缩放上(#323 第 1 点)。 */
    @Test fun openedCardReachesFullCenteredGeometry() {
        val service = DeviceSupport.service()
        val oldMode = flags.capsuleMode()
        val oldQuit = flags.quitRequested()
        val oldHideAtEdge = flags.capsuleHideAtEdge()
        try {
            flags.setQuitRequested(false)
            flags.setCapsuleHideAtEdge(false)
            flags.setCapsuleMode(CapsuleMode.Always)
            instrumentation.runOnMainSync { app.capsule.resume() } // 胶囊控制器只在主线程上动窗口
            DeviceSupport.startTestActivity(Display.DEFAULT_DISPLAY, GestureTapTestActivity::class.java)
            assertNotNull("test page did not open", pollUntil(5_000) { findText(service, "taps:0") })
            assertNotNull("idle capsule did not appear", pollUntil(15_000) { find(service, CapsuleCopy.IDLE) })
            assertTrue("idle capsule was not clickable", click(service, CapsuleCopy.IDLE))
            assertNotNull("card did not open", pollUntil(5_000) { find(service, CardCopy.COLLAPSE) })

            // 参照 = 测试页窗口(铺满主屏可用区);卡片窗口 = 里面有收起按钮(×)的那个浮层窗口
            val screen = windowBounds(service) { it.text?.toString() == "taps:0" } ?: error("test page window not found")
            val card = pollUntil(5_000) { windowBounds(service) { it.contentDescription?.toString() == CardCopy.COLLAPSE } }
                ?: error("card window not found")
            assertTrue("card extends past the screen: $card vs $screen", card.left >= screen.left - 8 && card.right <= screen.right + 8)
            val drift = abs(card.centerX() - screen.centerX())
            assertTrue("card is not centered: drift ${drift}px of ${screen.width()}px", drift <= screen.width() * 0.06f)
            assertTrue(
                "card is shorter than a fully opened card: ${card.height()}px of ${screen.height()}px",
                card.height() >= screen.height() * 0.45f,
            )
        } finally {
            find(service, CardCopy.COLLAPSE)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            flags.setCapsuleHideAtEdge(oldHideAtEdge)
            flags.setCapsuleMode(oldMode)
            flags.setQuitRequested(oldQuit)
            instrumentation.runOnMainSync { app.capsule.resume() } // 胶囊控制器只在主线程上动窗口
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /** 开启「贴边自动隐藏」后,初次出现与熄屏唤醒重新出现都应直接保持贴边半圆与闲置透明度,不再先弹出完整图标 3 秒。 */
    @Test fun edgeAutoHideStaysTuckedOnAppearAndScreenWake() {
        val service = DeviceSupport.service()
        val oldMode = flags.capsuleMode()
        val oldQuit = flags.quitRequested()
        val oldHideAtEdge = flags.capsuleHideAtEdge()
        val oldOpacity = flags.capsuleIdleOpacity()
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val revealLabel = tr(R.string.capsule_edge_reveal)
        fun readTuckState(): Pair<Boolean, Float> {
            var hidden = false
            var currentAlpha = 1f
            instrumentation.runOnMainSync {
                hidden = app.capsule.edgeHidden
                currentAlpha = app.capsule.alpha
            }
            return hidden to currentAlpha
        }
        try {
            DeviceSupport.sh("input keyevent 224")
            DeviceSupport.sh("wm dismiss-keyguard")
            flags.setCapsuleMode(CapsuleMode.Off)
            assertNotNull("capsule did not hide before test", pollUntil(5_000) { true.takeIf { !app.capsule.showing.value } })
            DeviceSupport.startTestActivity(Display.DEFAULT_DISPLAY, GestureTapTestActivity::class.java)
            assertNotNull("test page did not open", pollUntil(5_000) { findText(service, "taps:0") })
            flags.setQuitRequested(false)
            flags.setCapsuleIdleOpacity(CapsuleIdleOpacity.P40)
            flags.setCapsuleHideAtEdge(true)
            flags.setCapsuleMode(CapsuleMode.Always)
            instrumentation.runOnMainSync { app.capsule.resume() }
            assertNotNull("capsule did not show on start", pollUntil(15_000) { true.takeIf { app.capsule.showing.value } })

            val (initialHidden, initialAlpha) = readTuckState()
            assertTrue("capsule should appear tucked at edge without waiting 3s", initialHidden)
            assertEquals(CapsuleIdleOpacity.P40.alpha, initialAlpha, 0.001f)
            val edgeBounds = pollUntil(5_000) { windowBounds(service) { it.contentDescription?.toString() == revealLabel } }
                ?: error("edge capsule reveal node not found on appear")
            assertTrue("edge window should be narrower than tall: $edgeBounds", edgeBounds.width() < edgeBounds.height())
            assertNull("full idle ball should not appear on start", find(service, CapsuleCopy.IDLE))

            assertTrue("edge capsule was not clickable", click(service, revealLabel))
            assertNotNull("idle ball did not reveal on tap", pollUntil(5_000) { find(service, CapsuleCopy.IDLE) })
            assertFalse("capsule should no longer be edge-hidden after tap", readTuckState().first)

            DeviceSupport.sh("input keyevent 223")
            assertNotNull("screen did not turn off", pollUntil(5_000) { true.takeIf { !power.isInteractive && !app.capsule.showing.value } })

            DeviceSupport.sh("input keyevent 224")
            DeviceSupport.sh("wm dismiss-keyguard")
            assertNotNull("capsule did not resume after wake", pollUntil(10_000) { true.takeIf { power.isInteractive && app.capsule.showing.value } })

            val (wakeHidden, wakeAlpha) = readTuckState()
            assertTrue("capsule should stay tucked on screen wake without waiting 3s", wakeHidden)
            assertEquals(CapsuleIdleOpacity.P40.alpha, wakeAlpha, 0.001f)
            assertNotNull("edge capsule reveal node not found after wake", pollUntil(5_000) { find(service, revealLabel) })
            assertNull("full idle ball should not pop out on screen wake", find(service, CapsuleCopy.IDLE))

            assertTrue("edge capsule was not clickable after wake", click(service, revealLabel))
            assertNotNull("idle ball did not reveal on tap after wake", pollUntil(5_000) { find(service, CapsuleCopy.IDLE) })
            assertNotNull("revealed ball did not tuck again after idle delay", pollUntil(6_000) {
                true.takeIf { readTuckState().first && find(service, revealLabel) != null }
            })
        } finally {
            DeviceSupport.sh("input keyevent 224")
            DeviceSupport.sh("wm dismiss-keyguard")
            flags.setCapsuleIdleOpacity(oldOpacity)
            flags.setCapsuleHideAtEdge(oldHideAtEdge)
            flags.setCapsuleMode(oldMode)
            flags.setQuitRequested(oldQuit)
            instrumentation.runOnMainSync { app.capsule.resume() }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /** 第一个有节点满足 [match] 的窗口的屏幕边界;没有为 null。浮层窗口不经 uiautomator,只有自己服务看得见。 */
    private fun windowBounds(service: ScreenAccessibility, match: (AccessibilityNodeInfo) -> Boolean): android.graphics.Rect? {
        for (window in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var hit = false
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && match(node)) {
                    hit = true
                    break
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
            if (hit) return android.graphics.Rect().also { window.getBoundsInScreen(it) }
        }
        return null
    }

    /** 自己服务的浮层也在窗口列表里;只找用户看得见的节点,不可见窗口里的旧 Compose 树不算。 */
    private fun find(service: ScreenAccessibility, description: String): AccessibilityNodeInfo? {
        for (window in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.contentDescription?.toString() == description) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    private fun findText(service: ScreenAccessibility, text: String): AccessibilityNodeInfo? {
        for (window in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.text?.toString() == text) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    private fun click(service: ScreenAccessibility, description: String): Boolean =
        find(service, description)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    /** 页面中段的像素;避开状态栏、顶部胶囊和底部导航条。 */
    private fun screenshot(): IntArray {
        val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        try {
            val x = bitmap.width / 10
            val y = bitmap.height / 3
            val width = bitmap.width * 8 / 10
            val height = bitmap.height / 3
            return IntArray(width * height).also { bitmap.getPixels(it, 0, width, x, y, width, height) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun changedFraction(before: IntArray, after: IntArray): Float {
        if (before.size != after.size) return 1f
        var changed = 0
        for (i in before.indices) {
            val a = before[i]
            val b = after[i]
            val difference = maxOf(
                abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
                abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)),
                abs((a and 0xff) - (b and 0xff)),
            )
            if (difference > 32) changed++
        }
        return changed.toFloat() / before.size
    }
}
