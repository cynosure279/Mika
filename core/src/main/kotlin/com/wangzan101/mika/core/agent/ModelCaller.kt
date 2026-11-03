package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.trace.RunTrace
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelNeed
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.parseArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一次模型调用:历史只追加,估算越过触发点才压缩一次([ContextCompressor],完整历史不动)、主模型一时的故障按 [retry] 重试、上下文超长时压得更狠再发一次,
 * lightSteps 打开时按 [router] 在大小模型之间分工(轻量模型出错不重试,这一步直接改问主模型)。每个任务开始时 [reset]。
 *
 * 主模型、轻量模型都可以是模型组([ModelPool]):一次调用按成员能力和策略选一个,失败按 [FailoverPolicy] 换下一个;
 * 每个成员各有一份压缩状态、用量锚点,换成员时用 [viewFor] 去掉别家模型的私有数据。
 */
internal class ModelCaller(
    private val model: ChatModel,
    private val tools: ToolDispatcher,
    private val router: ModelRouter?,
    private val flags: SpeedFlags,
    private val retry: RetryPolicy,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
    private val autonomous: Boolean = false,
    private val trace: RunTrace? = null,
) {
    /** 每个模型一份压缩状态:各自的上下文、各自发出的那份历史,轻量模型那次压缩不连累主模型的请求前缀。 */
    private val compressors = java.util.IdentityHashMap<ChatModel, ContextCompressor>()
    private fun compressorFor(m: ChatModel) =
        compressors.getOrPut(m) { ContextCompressor(tools.index, structured = flags.structuredCompaction,
            diffScreens = tools.inlineScreen, triggerOverride = m.capabilities.compactionTriggerTokens) }
    private val compactions = java.util.IdentityHashMap<ChatModel, CompactionState>()
    /** 本任务里已为重试等过的时间(对着 [RetryPolicy.taskWaitBudgetMs])。 */
    private var taskRetryWaitMs = 0L

    /** 本任务的系统提示词:开工时按发给模型的同一份工具列表拼([SystemPrompt.assemble]),任务内不变。 */
    var systemPrompt: String = ""

    /** 最近一次调用在成功那次请求之前花掉的时间(失败的请求、退避、换成员)。 */
    var lastRetryWaitMs = 0L
        private set

    /** 本任务每条 assistant 消息是谁给的;发给另一个模型时要去掉那人给的私有数据。 */
    private val producers = java.util.IdentityHashMap<Message, ChatModel>()

    /** 带真实用量的回复(按消息身份)和给出它的模型,是量请求大小的锚点([UsageMeter]);压缩改写前缀后清空。 */
    private val anchors = java.util.IdentityHashMap<Message, Pair<ChatModel, UsageMeter.Anchor>>()
    /** 每个模型最近一次请求的固定开销估算;回复进历史时和用量一起记进锚点。 */
    private val lastOverhead = java.util.IdentityHashMap<ChatModel, Int>()

    fun reset(prompt: String) {
        systemPrompt = prompt
        compactions.clear()
        taskRetryWaitMs = 0
        compressors.values.forEach { it.forget() }
        producers.clear()
        anchors.clear()
        lastOverhead.clear()
    }

    /** 回复进了历史:记下是谁给的;有真实 usage 的记成上下文大小的锚点。 */
    fun noteReply(m: Message, used: ChatModel, usage: Usage?) {
        if (m.role == Role.Assistant) producers[m] = used
        val overhead = lastOverhead[used]
        if (usage != null && overhead != null) anchors[m] = used to UsageMeter.Anchor(usage, overhead)
    }

    /** 发给 [using] 的整份请求有多大:最近一条它给的、带真实用量的回复作锚点,没有就全量估算(见 [UsageMeter])。 */
    private fun requestTokens(messages: List<Message>, using: ChatModel, overhead: Int, scale: Double): Int =
        UsageMeter.of(messages, overhead, scale) { m -> anchors[m]?.takeIf { it.first === using }?.second }

    /** 本任务里分工是否在起作用:开关打开、轻量模型能接手机步、没因不可恢复的错误停用。 */
    private val routing: Boolean get() = flags.lightSteps && router?.active == true

    /** 开了分工就在运行记录里记每次调用的模型和角色(停用之后也记,便于对比)。 */
    val recordRoles: Boolean get() = flags.lightSteps && router != null

    fun roleOf(m: ChatModel) = if (router?.isLight(m) == true) ROLE_LIGHT else ROLE_MAIN

    /** 这次调用用的成员属于哪个模型组;单模型为 null。子任务没有 router 时回退到主池。 */
    fun groupOf(m: ChatModel): String? {
        router?.groupOf(m)?.let { return it }
        val mainPool = model.asPool()
        return if (mainPool === m || mainPool.owns(m)) mainPool.groupName else null
    }

    /**
     * 按 [router] 选模型。轻量模型出错、没调工具(不带调用的回复是最终回答,只由主模型给),或要放弃、确认、接管、追问、做不可逆的事,
     * 它的回复不执行(作为 [ROLE_LIGHT_REJECTED] 记下用量),这一步改问主模型,改问前用 [restarted] 报新的起问时刻。
     * 返回回复和实际用的模型。
     */
    suspend fun routed(
        history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
        onText: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel> {
        val r = router?.takeIf { routing } ?: return askRouted(history, specs, step, onText = onText)
        val picked = r.pick(track.context(step, history.any { m -> m.parts.any { it is Part.Image } }))
        if (!r.isLight(picked)) return askRouted(history, specs, step, onText = onText)
        // 轻量模型的上下文可能更小:它报超长、它那次折叠多少,都不连累主模型(压缩状态按模型分开记)
        val asked = now()
        val light = try {
            complete(history, specs, picked.asPool(), RetryPolicy.NONE, step, onText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Key、额度、模型名、请求本身不对,重试也没用:本任务余下的步都用主模型
            if (e is ModelError.Auth || e is ModelError.QuotaExceeded || e is ModelError.ModelNotFound ||
                e is ModelError.ModelNotAvailable || e is ModelError.ToolsUnsupported ||
                e is ModelError.ChatGptLimitReached || e is ModelError.ChatGptNotEligible || e is ModelError.SignInExpired || e is ModelError.AccountPlanNotAllowed ||
                e is ModelError.GroupUnavailable || e is ModelError.GroupExhausted ||
                (e is ModelError.Server && e.status in 400..499)) r.disable()
            null
        }
        val first = light?.first?.parts?.filterIsInstance<Part.ToolCall>()?.firstOrNull()
        if (light != null && first != null && !needsMain(first)) return light
        r.noteTrouble()
        val reply = light?.first
        val used = light?.second
        val text = reply?.parts?.filterIsInstance<Part.Text>()?.joinToString("") { it.text }?.trim().orEmpty()
        emit(RunEvent.ModelReply(
            now(), step, RunEvent.clip(Redactor.text(text)), first?.name, first?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = true) },
            reply?.usage?.inputTokens, reply?.usage?.outputTokens, reply?.usage?.cachedInputTokens, now() - asked,
            used?.name ?: picked.name, ROLE_LIGHT_REJECTED, group = r.groupOf(picked),
        ))
        emit(RunEvent.State(now(), "light_to_main", first?.name ?: if (light == null) "light_error" else "no_tool"))
        onText?.invoke("")
        restarted(now())
        return askRouted(history, specs, step, onText = onText)
    }

    private fun needsMain(call: Part.ToolCall): Boolean =
        ModelRouter.mainOnly(call.name, tools.verb(call.name)) ||
            (!autonomous && ConfirmDecision.mayBeIrreversible(tools, call.name, parseArgs(call.argsJson)))

    /** 问 [using](默认主模型):发出的是给它看的那份历史([viewFor])压缩后的副本。 */
    suspend fun ask(history: List<Message>, specs: List<ToolSpec>, step: Int, using: ChatModel = model, onText: ((String) -> Unit)? = null): ModelReply =
        askRouted(history, specs, step, using, onText).first

    /** 同 [ask],返回实际回答的模型(组里可能是任一个成员)。 */
    suspend fun askRouted(
        history: List<Message>, specs: List<ToolSpec>, step: Int, using: ChatModel = model, onText: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel> = complete(history, specs, using.asPool(), retry, step, onText)

    /**
     * 发给 [to] 的历史:别的模型给出的回复去掉思考内容和调用上的私有数据(这些只能原样回给产生它的那个模型)。
     * 只看字段在不在,不分服务商;单模型时没有别家,原样返回。
     */
    private fun viewFor(history: List<Message>, to: ChatModel): List<Message> {
        if (producers.isEmpty()) return history
        return history.map { m ->
            val from = producers[m] ?: return@map m
            if (m.role != Role.Assistant || belongsTo(to, from)) return@map m
            if (m.parts.none { it is Part.Thinking || (it is Part.ToolCall && it.opaque != null) }) return@map m
            m.copy(parts = m.parts.mapNotNull { p ->
                when {
                    p is Part.Thinking -> null
                    p is Part.ToolCall && p.opaque != null -> p.copy(opaque = null)
                    else -> p
                }
            })
        }
    }

    /** [from] 是不是 [to] 这个池里的成员;单模型按对象相同。 */
    private fun belongsTo(to: ChatModel, from: ChatModel): Boolean =
        (to as? ModelPool)?.owns(from) ?: (to === from)

    /**
     * 一次调用:按策略排好成员,逐个尝试;成功返回回复和实际用的模型。
     * 组里有多个候选时,同一成员最多只重试一次,把机会留给下一个;单模型保持原来的重试次数。
     */
    private suspend fun complete(
        history: List<Message>, specs: List<ToolSpec>, pool: ModelPool, policy: RetryPolicy, step: Int,
        onText: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel> {
        val began = now()
        lastRetryWaitMs = 0
        // 单模型池不看选择条件:不必为它整份估算历史(长对话下这一步很贵)
        val candidates = if (pool is SingleMemberPool) pool.members else pool.ordered(needOf(history, specs))
        if (candidates.isEmpty()) throw ModelError.GroupUnavailable(pool.groupName ?: "", "没有可用成员")
        var last: Throwable? = null
        for ((index, member) in candidates.withIndex()) {
            val using = member.model
            val memberBegan = now()
            try {
                val reply = attemptMember(
                    viewFor(history, using), specs, using, policy, step, onText, began,
                    maxRetries = if (candidates.size > 1) minOf(policy.maxRetries, 1) else policy.maxRetries,
                )
                pool.noteResult(member, now() - memberBegan, null)
                return reply to using
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelError) {
                last = e
                pool.noteResult(member, now() - memberBegan, e)
                if (!FailoverPolicy.canFailover(e) || index == candidates.lastIndex) throw e
                onText?.invoke("")
                val next = candidates.getOrNull(index + 1)
                emit(RunEvent.State(
                    now(), "model_route",
                    "${pool.groupName ?: "group"} ${member.label} -> ${next?.label ?: "-"} ${RetryPolicy.label(e)}",
                ))
            } catch (e: Exception) {
                last = e
                pool.noteResult(member, now() - memberBegan, e)
                if (index == candidates.lastIndex) throw e
                onText?.invoke("")
            }
        }
        throw last ?: ModelError.GroupUnavailable(pool.groupName ?: "")
    }

    /** 对 [using] 的一次完整尝试:压缩、发请求、同成员重试、超长再压。 */
    private suspend fun attemptMember(
        history: List<Message>, specs: List<ToolSpec>, using: ChatModel, policy: RetryPolicy, step: Int,
        onText: ((String) -> Unit)?, began: Long, maxRetries: Int,
    ): ModelReply {
        var retries = 0
        val overhead = TokenEstimate.of(systemPrompt) + specs.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        val contextTokens = using.capabilities.contextTokens
        val compressor = compressorFor(using)
        var compaction = compactions[using] ?: CompactionState()
        var overflowRetried = false
        var overflowRewrote = false
        while (true) {
            val current = ContextRecovery.ensure(history, compressor.current(history, contextTokens))
            val currentTokens = requestTokens(current, using, overhead, compressor.scale)
            val raw = compressor.compress(history, contextTokens, currentTokens, compaction.aggressive, overhead, using.capabilities.maxImages)
            if (raw.rewritten) anchors.clear()
            val restored = ContextRecovery.ensure(history, raw.messages)
            val sent = if (restored === raw.messages) raw else raw.copy(messages = restored, estimate = TokenEstimate.of(restored))
            val (next, report) = CompactionPolicy.sent(compaction, sent.foldedSteps)
            if (report) emit(RunEvent.Compressed(now(), sent.foldedSteps))
            compaction = next
            compactions[using] = compaction
            try {
                // 差异的 base 被裁掉或折叠时,下一次写操作要发全量
                if (tools.inlineScreen) tools.setVisibleObservations(visibleReads(sent.messages))
                // 这一次之前花掉的(失败的请求、退避、换成员)都算重试等待
                lastRetryWaitMs = now() - began
                suspend fun send(): ModelReply = if (onText == null) using.complete(systemPrompt, sent.messages, specs)
                    else if (using is StreamingChatModel) using.completeStreaming(systemPrompt, sent.messages, specs, onText)
                    else using.complete(systemPrompt, sent.messages, specs).also { result ->
                        result.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                            .takeIf(String::isNotEmpty)?.let(onText)
                    }
                val reply = if (trace == null) send() else {
                    val change = when {
                        overflowRewrote -> "overflow_compaction folded=" + raw.foldedSteps
                        raw.rewritten && current != sent.messages -> "history_rewritten folded=" + raw.foldedSteps
                        else -> null
                    }
                    overflowRewrote = false
                    trace.modelCall(step, using, tools::verb, change, sent.messages) { send() }
                }
                return reply.also { r ->
                    lastOverhead[using] = overhead
                    r.usage?.let { compressor.calibrate(sent.estimate + overhead, it.inputTokens) }
                }
            } catch (e: ModelError.ContextTooLong) {
                onText?.invoke("")
                val (after, again) = CompactionPolicy.overflow(compaction, overflowRetried) {
                    compressor.overflow(history, contextTokens, overhead)
                }
                compaction = after
                compactions[using] = compaction
                overflowRetried = true
                if (again) { anchors.clear(); overflowRewrote = true }
                if (!again) throw e
            } catch (e: ModelError) {
                onText?.invoke("")
                val wait = policy.delayFor(e, retries, taskRetryWaitMs, maxRetries) ?: throw e
                retries += 1
                taskRetryWaitMs += wait
                emit(RunEvent.State(now(), "model_retry", "${roleOf(using)} ${RetryPolicy.label(e)} #$retries ${wait}ms"))
                // 等待中取消任务就此结束
                delay(wait)
            }
        }
    }

    /** 这次请求对成员的要求:要不要工具、有没有图、估算多大。 */
    private fun needOf(history: List<Message>, specs: List<ToolSpec>): ModelNeed {
        val images = history.sumOf { m -> m.parts.count { it is Part.Image } }
        val overhead = TokenEstimate.of(systemPrompt) + specs.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        return ModelNeed(
            toolCalls = specs.isNotEmpty(), vision = images > 0, images = images,
            estimatedTokens = TokenEstimate.of(history) + overhead,
        )
    }

    companion object {
        /** [RunEvent.ModelReply.role] 的取值;分工没起作用时为 null。 */
        const val ROLE_MAIN = "main"
        const val ROLE_LIGHT = "light"
        /** 轻量模型的回复没执行,这一步改问了主模型;仍是一次模型调用,用量照记。 */
        const val ROLE_LIGHT_REJECTED = "light_rejected"
    }
}

/** 发出的历史里还完整可见的读取(按编号):带元素列表的读取结果与带 screen 对象的动作结果。 */
internal fun visibleReads(messages: List<Message>): Set<String> {
    val out = HashSet<String>()
    for (m in messages) for (p in m.parts) {
        if (p !is Part.ToolResult) continue
        val obj = try { Json.parseToJsonElement(p.content) as? JsonObject } catch (e: kotlinx.serialization.SerializationException) { null } ?: continue
        val shown = (obj["screen"] as? JsonObject) ?: obj.takeIf { it["elements"] is JsonArray } ?: continue
        (shown["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let(out::add)
    }
    return out
}
