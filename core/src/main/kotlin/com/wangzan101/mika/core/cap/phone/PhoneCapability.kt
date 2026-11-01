package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.Paced
import com.wangzan101.mika.core.uistate.DwellStop
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.uistate.PlacePace
import com.wangzan101.mika.core.uistate.dwellStopped
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.uistate.Surface
import com.wangzan101.mika.core.observation.Look
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.uistate.ScreenHost
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.EffectKind
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.sessions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 手机:手机那一套屏幕工具、打开 App 与链接。
 * [pacing] 是在 App 里一步一步操作的节奏(见 [AppPacing]):改动屏幕的动作之前补足停留,限制浏览快慢的 App 屏数有上限;
 * [appPace] 按包名给出 App 知识对浏览快慢的说法;[idle] 给了时新的一屏上的停留里轻轻滚几下(见 [IdleScroll])。
 */
internal class PhoneCapability(
    val screen: ScreenPort,
    /** 提速开关 deepLinks:提供打开链接的工具。 */
    val deepLinks: Boolean,
    pacing: AppPacing = AppPacing.Off,
    appPace: (String) -> PlacePace = { PlacePace.NORMAL },
    idle: IdleScroll? = null,
    /** 这次任务在哪块屏上操作:虚拟屏还是用户的主屏。只影响提示词那一段怎么写。 */
    private val host: ScreenHost = ScreenHost.Virtual,
) : Capability {
    val dwell = AppDwell(pacing, appPace, idle)

    override val id = PHONE
    private val tools = PhoneGroup.tools

    override val folds = true

    override fun status(): CapabilityStatus = CapabilityStatus.Available

    override fun tools(): List<AgentTool> = tools

    override val family = PHONE_FAMILY

    override val surface = Surface(PHONE_FAMILY, screen, declaredTarget = { app ->
        if (!PACKAGE_NAME_RE.matches(app)) throw ArgError("app", "must be an Android package name")
        app
    }, targetNote = "the exact package name of the app")

    /** 手机一段跟着打开、列出 App 的工具走;手机那一组收着时不给。 */
    override fun sections(env: PromptEnv): List<String> {
        if (!env.isOpen(id) || tools.none { it.verb in SECTION_VERBS && it.name in env.offered }) return emptyList()
        return listOf(env.gate(Prompts.capability(PHONE).replace(HOST_PLACEHOLDER, hostNote(host))))
    }

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
        dwell.onIdleScroll = { ctx.sessions[id]?.movedByMika() }
    }

    /** 用户答过话:限制浏览快慢的 App 的屏数重新算(用户答应了接着看)。 */
    override fun onUserAnswered(ctx: ToolContext) = dwell.userAnswered()

    /** App 里的对外动作做成之后,这个 App 不再设屏数上限(成批联系、投递做到 App 自己的上限为止)。 */
    override fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) = dwell.outwardEnd(site, done)

    /**
     * 改动屏幕的动作之前按 App 里的节奏停:此刻在哪个 App、这一屏多少字按模型最近读到的那一屏算。
     * 在 App 里换屏的动作(点、滑、返回、按键、手势)在限制浏览快慢的 App 里到了屏数上限就不做,报 page_limit;打字、清空、滚动、
     * 拨滚轮、打开别的 App 和对外动作本身不拦。接下来的动作不按屏上的位置做(返回、按键、按方向滚)时,停留里可以轻轻滚几下。
     */
    override suspend fun beforeWrite(ctx: ToolContext, verb: Verb?, outward: Boolean): Paced {
        if (!dwell.paced) return Paced.NONE
        val shown = ctx.sessions[id]?.lastSnapshot
        return try {
            val d = dwell.before(shown?.packageName, shown?.let(::textChars) ?: 0, gate = !outward && verb in BROWSES,
                port = screen.takeIf { verb in IDLE_BEFORE })
            Paced(d.waitMs, d.idleScrolls)
        } catch (e: DwellStop) {
            Paced(stop = dwellStopped(e))
        }
    }

    /** 动作做完:界面大变或换了 App 算换到了新的一屏(往下滚读同一页余下的内容不算进屏数)。 */
    override fun afterWrite(ctx: ToolContext, verb: Verb?, result: JsonObject) {
        if (!dwell.paced) return
        val effect = result["effect"] as? JsonObject
        val kind = EffectKind.of((effect?.get("kind") as? JsonPrimitive)?.contentOrNull)
        val newScreen = kind == EffectKind.Done || kind == EffectKind.PlaceChanged
        val app = (effect?.get("to") as? JsonPrimitive)?.contentOrNull ?: ctx.sessions[id]?.lastSnapshot?.packageName
        dwell.after(app, newScreen, counts = verb != Verb.Scroll)
    }

    /** 再看一眼:此刻手机上那个 App 的界面状态。 */
    override suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? = ctx.sessions[id]?.freshLook(withImage)

    /** 系统命令连着失败了几次;成功一次清零。 */
    var commandFailures = 0

    /** 本任务已提醒过系统命令换回专门工具:之后再做那几类事的命令不执行。 */
    var commandSteered = false

    companion object {
        val KEY = CapabilityKey<PhoneCapability>(PHONE)
        private val SECTION_VERBS = setOf(Verb.LaunchApp, Verb.ListApps)

        /** 手机那一段开头那句「这次在哪块屏上做」的占位符。 */
        private const val HOST_PLACEHOLDER = "{{host}}"

        /** 后台虚拟屏(默认):用户同时在主屏上用手机。和从前一字不差。 */
        private const val HOST_VIRTUAL =
            "Your actions run on a dedicated virtual display; the user keeps using the
phone on the main screen at the same time."

        /**
         * 用户的主屏:动作就在用户眼前发生,这段时间手机归 Mika 用。要求模型改得少、交代得清楚,
         * 结束后把屏留在能看到结果的地方(不能像后台屏那样「反正用户看不见」)。
         */
        private const val HOST_MAIN =
            "Your actions run on the phone's own screen, the one the user is looking at. The user cannot use the phone while " +
                "this runs, so keep it short: open only what the task needs, change nothing else, and leave the screen showing " +
                "what the task produced. Anything you do is visible to the user as it happens."

        private fun hostNote(host: ScreenHost): String = if (host == ScreenHost.Main) HOST_MAIN else HOST_VIRTUAL

        /** 在 App 里换屏的动作:受限制浏览快慢的 App 的屏数上限拦。 */
        private val BROWSES = setOf(Verb.Tap, Verb.Swipe, Verb.Back, Verb.PressKey, Verb.PhoneGesture)

        /** 不按屏上的位置做的动作:之前的停留里轻轻滚几下不会让它点偏。 */
        private val IDLE_BEFORE = setOf(Verb.Back, Verb.PressKey, Verb.Scroll)

        /** 一屏上给人看的字数(输入框里的字、提示语不算)。 */
        internal fun textChars(s: ScreenSnapshot): Int =
            s.elements.filter { !it.editable && !it.hint }.sumOf { it.name.codePointCount(0, it.name.length) }

        /** 手机一组可能提供的全部工具。 */
        fun tools(): List<AgentTool> = PhoneGroup.tools
    }
}

/** 本任务的手机能力。 */
internal val ToolContext.phone: PhoneCapability get() = this[PhoneCapability.KEY]!!
