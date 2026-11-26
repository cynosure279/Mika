package com.wangzan101.mika.chat.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.connect
import com.wangzan101.mika.settings.ensureConfig
import com.wangzan101.mika.ui.theme.MikaTheme
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * 用量面板正文:选了主模型时显示当前模型和它服务商的余额,「查看 Token 用量」点掉既关面板又发跳转;
 * 本对话单独指定了主模型时显示的是这个对话自己的模型。
 */
@RunWith(AndroidJUnit4::class)
class ConversationUsageContentDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
    private val dismissed = AtomicBoolean(false)
    private val opened = AtomicBoolean(false)

    private fun store(): ProviderStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return ProviderStore(tmp.root, AesGcmKeyCipher { key })
    }

    /** 连上一家只挂一个模型的服务商;返回 (服务商, 模型配置 id)。 */
    private fun ProviderStore.oneModel(name: String, host: String, model: String): Pair<Provider, String> {
        val p = connect(name, ProviderConfig.Kind.OpenAiCompat, "https://$host.test/v1", "key", listOf(ListedModel(model)))
        return p to ensureConfig(p.id, model, caps)!!
    }

    private fun show(store: ProviderStore?) {
        compose.setContent {
            MikaTheme(dark = false) {
                ConversationUsageContent(null, onDismiss = { dismissed.set(true) }, onOpenUsage = { opened.set(true) }, store = store)
            }
        }
    }

    @Test
    fun selectedMainModelShowsItsProviderAllowanceAndTheLinkJumpsToUsage() {
        val s = store()
        val provider = s.connect("测试服务商", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "key-1",
            listOf(ListedModel("m-x")))
        assertTrue(s.use(ModelRole.Main, provider.id, "m-x", caps))
        show(s)
        compose.onNodeWithText("当前模型").assertIsDisplayed()
        compose.onNodeWithText("测试服务商").assertIsDisplayed()
        // 临时目录里的服务商地址接不上余额查询:如实标「未接入额度查询」,不猜一个数
        compose.onNodeWithText("未接入额度查询").assertIsDisplayed()
        compose.onNodeWithTag("conversation-usage-open-detail").assertIsDisplayed().performClick()
        assertTrue(dismissed.get())
        assertTrue(opened.get())
    }

    @Test
    fun conversationOverrideShowsTheConversationsOwnModelNotTheGlobalOne() {
        val s = store()
        val global = s.oneModel("全局服务商", "global", "m-x").first
        assertTrue(s.use(ModelRole.Main, global.id, "m-x", caps))
        val ownConfig = s.oneModel("本对话服务商", "own", "m-y").second
        val override = mutableStateOf<ModelChoice?>(null)
        compose.setContent {
            MikaTheme(dark = false) {
                ConversationUsageContent(null, onDismiss = {}, onOpenUsage = {}, store = s, mainOverride = override.value)
            }
        }

        // 跟随全局:显示全局主模型和它的服务商
        compose.onNodeWithText("m-x").assertIsDisplayed()
        compose.onNodeWithText("全局服务商").assertIsDisplayed()
        // 本对话在主模型入口里单独指定了 m-y:面板跟着换成它和它的服务商,全局那家不再出现
        compose.runOnIdle { override.value = ModelChoice.Model(ownConfig) }
        compose.onNodeWithText("m-y").assertIsDisplayed()
        compose.onNodeWithText("本对话服务商").assertIsDisplayed()
        compose.onNodeWithText("m-x").assertDoesNotExist()
        compose.onNodeWithText("全局服务商").assertDoesNotExist()
    }

    @Test
    fun withoutAMainModelTheSectionHidesButTheUsageLinkStays() {
        show(store())
        compose.onNodeWithText("当前模型").assertDoesNotExist()
        compose.onNodeWithTag("conversation-usage-open-detail").assertIsDisplayed()
    }
}
