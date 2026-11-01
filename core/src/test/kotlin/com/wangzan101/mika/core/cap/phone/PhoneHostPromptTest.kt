package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ScreenHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手机那一段开头那句「这次在哪块屏上做」跟着 [ScreenHost] 变:后台虚拟屏照旧,主屏要说清用户看得见、这段时间用不了手机。 */
class PhoneHostPromptTest {
    private fun prompt(host: ScreenHost): String {
        val d = toolDispatcher(FakeScreen(), screenHost = host)
        // all = true:当各能力组都展开(手机那一段才会出现)
        return d.promptSections(d.definitions(), all = true).joinToString("\n")
    }

    @Test fun virtualKeepsTheOriginalWording() {
        val text = prompt(ScreenHost.Virtual)
        assertTrue(text, text.contains("dedicated virtual display"))
        assertTrue(text, text.contains("the user keeps using the"))
        assertFalse("虚拟屏不该说成用户眼前那块", text.contains("the user is looking at"))
    }

    @Test fun mainSaysTheUserIsWatchingAndCannotUseThePhone() {
        val text = prompt(ScreenHost.Main)
        assertTrue(text, text.contains("the user is looking at"))
        assertTrue("要说清这段时间用户用不了手机", text.contains("cannot use the phone"))
        assertFalse("主屏上不该再说是后台虚拟屏", text.contains("dedicated virtual display"))
    }

    /** 两个占位符都填上了:漏填会直接抛(见 Prompts.fill)。 */
    @Test fun noPlaceholderLeaks() {
        for (host in ScreenHost.entries) assertFalse(prompt(host).contains("{{"))
    }
}
