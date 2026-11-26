package com.wangzan101.mika.ui.components

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PageScrollsTest {
    private enum class Page { Home, Advanced, Sandbox }

    // 首页离开组合去了子页(其间取过子页的状态),回来拿到的还是原来那一份,位置没动
    @Test fun aPageKeepsItsScrollPositionWhileAnotherPageIsShown() {
        val scrolls = PageScrolls()
        val home = scrolls.of(Page.Home)
        home.dispatchRawDelta(600f)
        val sandbox = scrolls.of(Page.Sandbox)
        sandbox.dispatchRawDelta(150f)

        assertSame(home, scrolls.of(Page.Home))
        assertEquals(600, scrolls.of(Page.Home).value)
        assertEquals(150, sandbox.value)
    }

    // 页面重建:存下来的是取过的各页的位置,取回来接上;没存过的页从 0 开始
    @Test fun savedPositionsComeBackAfterRecreation() {
        val scrolls = PageScrolls()
        scrolls.of(Page.Home).dispatchRawDelta(600f)
        scrolls.of(Page.Advanced).dispatchRawDelta(120f)

        val saved = with(PageScrolls.Saver) { with(AnyValue) { save(scrolls) } }
        val restored = PageScrolls.Saver.restore(saved!!)!!

        assertEquals(600, restored.of(Page.Home).value)
        assertEquals(120, restored.of(Page.Advanced).value)
        assertEquals(0, restored.of(Page.Sandbox).value)
    }

    private object AnyValue : SaverScope {
        override fun canBeSaved(value: Any) = true
    }
}
