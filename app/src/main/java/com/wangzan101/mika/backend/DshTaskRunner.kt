package com.wangzan101.mika.backend

import android.net.Uri
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.task.ExternalTaskRun
import com.wangzan101.mika.task.ExternalNotifyPolicy
import com.wangzan101.mika.task.TaskService
import io.github.jackwener.dsh.core.engine.DshEngineCallbacks
import io.github.jackwener.dsh.core.engine.DshEngineGateway
import io.github.jackwener.dsh.core.engine.DshEngineRoute
import io.github.jackwener.dsh.core.engine.DshLaunchConfig
import io.github.jackwener.dsh.core.engine.JsonRpcException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs DSH conversations while keeping Mika's conversation and task center as the visible source
 * of truth. DSH owns its SDK history; each Mika conversation id is also its DSH session id.
 *
 * The current DSH SDK has no per-session cancellation. All root DSH tasks therefore share one
 * engine process and are serialized; stopping one intentionally stops the process.
 */
class DshTaskRunner(private val app: MikaApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val execution = Mutex()
    private val gateway = DshEngineGateway(app)
    private val deviceBridge = DshDeviceCapabilityBridge(app)
    private val active = ConcurrentHashMap<String, RunContext>()
    private val approvals = ConcurrentHashMap<String, PendingApproval>()
    private val questions = ConcurrentHashMap<String, PendingQuestion>()
    /** The root task that currently owns the single DSH engine. Null means all roots are queued. */
    @Volatile private var executingConversationId: String? = null

    fun start(goal: String, conversationId: String) {
        if (active.containsKey(conversationId)) return
        val run = ExternalTaskRun("dsh-$conversationId", conversationId, goal, ExecutionBackend.DeepSeekHarness)
        val context = RunContext(run, goal)
        active[conversationId] = context
        app.taskState.publishExternal(run, TaskState.Running(0, "DeepSeek Harness 已排队"))
        TaskService.attachExternal(app, run)
        scope.launch { execute(context) }
    }

    fun cancel(conversationId: String) {
        val context = active[conversationId] ?: return
        context.cancelled.set(true)
        deviceBridge.cancelSession(conversationId)
        context.finish(TaskState.Cancelled)
        app.taskState.updateExternal(conversationId, TaskState.Cancelled)
        // Cancelling a queued root must not stop the different root that currently owns the engine.
        if (executingConversationId != conversationId) return
        scope.launch {
            runCatching { gateway.stop() }.onFailure { Log.w(TAG, "could not stop DSH engine", it) }
            runCatching { deviceBridge.releaseDevice() }.onFailure { Log.w(TAG, "could not release DSH device", it) }
        }
    }

    /**
     * DSH has no mid-turn steer SDK method. Queue the message as another prompt in the same root
     * task; it is sent immediately after the current turn ends, before the task is marked complete.
     */
    fun steer(conversationId: String, text: String) {
        val context = active[conversationId] ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        context.pendingSteers += trimmed
        publish(
            context,
            conversationId,
            TaskState.Running(context.steps, "已收到补充，本轮结束后继续：${trimmed.take(120)}"),
        )
    }

    fun approve(conversationId: String, requestKey: Int?) {
        val context = active[conversationId] ?: return
        val requestId = context.approvalRequest ?: return
        if (requestKey != null && context.approvalKey != requestKey) return
        approvals.remove(requestId)?.decision?.complete("allowed-once")
        context.approvalRequest = null
        context.approvalKey = null
        publish(context, conversationId, TaskState.Running(context.steps, context.lastLine.ifBlank { "继续执行" }))
    }

    /** 用户从通知点了「交回」或「跳过」:完成对应的 handover deferred,引擎拿到结果后自己读屏核验。 */
    fun handBack(conversationId: String, outcome: String = "complete") {
        deviceBridge.completeHandover(conversationId, outcome)
        val context = active[conversationId] ?: return
        val message = if (outcome == "complete") "已交回，DeepSeek Harness 正在读屏核验" else "已跳过接管，DeepSeek Harness 正在继续"
        publish(context, conversationId, TaskState.Running(context.steps, message))
    }

    fun decline(conversationId: String, requestKey: Int?) {
        val context = active[conversationId] ?: return
        val requestId = context.approvalRequest ?: return
        if (requestKey != null && context.approvalKey != requestKey) return
        approvals.remove(requestId)?.decision?.complete("rejected")
        context.approvalRequest = null
        context.approvalKey = null
        publish(context, conversationId, TaskState.Running(context.steps, context.lastLine.ifBlank { "已拒绝操作" }))
    }

    fun answer(conversationId: String, text: String) {
        val context = active[conversationId] ?: return
        val requestId = context.questionRequest ?: return
        questions.remove(requestId)?.let { pending ->
            val trimmed = text.trim()
            val selected = pending.options.firstOrNull { it.equals(trimmed, ignoreCase = true) }
            pending.answer.complete(
                buildJsonObject {
                    putJsonArray("answers") {
                        addJsonObject {
                            put("id", pending.questionId)
                            if (selected != null) {
                                putJsonArray("selected") { add(selected) }
                            } else {
                                putJsonArray("selected") { }
                                put("custom", trimmed)
                            }
                        }
                    }
                },
            )
        }
        context.questionRequest = null
        publish(context, conversationId, TaskState.Running(context.steps, context.lastLine.ifBlank { "继续执行" }))
    }

    private suspend fun execute(context: RunContext) = execution.withLock {
        val conversationId = context.run.conversationId
        executingConversationId = conversationId
        if (context.cancelled.get()) {
            finish(context, TaskState.Cancelled)
            return@withLock
        }
        publish(context, conversationId, TaskState.Running(0, "正在读取模型配置"))
        val launch = try {
            launchConfig()
        } catch (e: Exception) {
            fail(context, e.message ?: e.javaClass.simpleName)
            return@withLock
        }
        try {
            publish(context, conversationId, TaskState.Running(0, "正在启动 DeepSeek Harness"))
            deviceBridge.connectSession(conversationId)
            val engine = gateway.connect(
                launch,
                deviceBridge.callbacks().copy(
                    onSdkRequest = { method, params -> onServerRequest(context, method, params) },
                    onSdkNotification = { method, params, _ -> onNotification(context, method, params) },
                    onClosed = {
                        if (!context.cancelled.get() && !context.terminal.isCompleted) {
                            context.finish(
                                TaskState.Failed("DeepSeek Harness 引擎进程已退出", FailKind.Environment),
                            )
                        }
                    },
                ),
            )
            runCatching {
                engine.sdk.request("session/resume", buildJsonObject { put("sessionId", conversationId) })
            }
            runCatching {
                engine.sdk.request(
                    "android/session/permission",
                    buildJsonObject {
                        put("sessionId", conversationId)
                        put("preset", "workspace-write")
                    },
                )
            }
            var prompt = context.goal
            while (true) {
                publish(context, conversationId, TaskState.Running(context.steps, "正在思考"))
                context.startTurn()
                // 审批超时观察协程:本轮结束就停,不阻塞主流程。
                val approvalWatch = scope.launch { awaitApprovalTimeout(context) }
                engine.sdk.request(
                    "session/prompt",
                    buildJsonObject {
                        put("sessionId", conversationId)
                        putJsonArray("contentBlocks") {
                            addJsonObject {
                                put("type", "text")
                                put("text", prompt)
                            }
                        }
                    },
                )
                val end = context.turn.await()
                approvalWatch.cancel()
                val next = if (end is TaskState.Succeeded) context.drainSteers() else emptyList()
                if (next.isEmpty()) {
                    context.finish(end)
                    finish(context, end)
                    break
                }
                prompt = next.joinToString("\n\n")
            }
        } catch (e: Exception) {
            if (context.cancelled.get()) {
                context.finish(TaskState.Cancelled)
                finish(context, TaskState.Cancelled)
            } else {
                fail(context, (e as? JsonRpcException)?.message ?: e.message ?: e.javaClass.simpleName)
            }
        } finally {
            deviceBridge.cancelSession(conversationId)
            approvals.values.removeIf { it.conversationId == conversationId }
            questions.values.removeIf { it.conversationId == conversationId }
            active.remove(conversationId)
            runCatching { deviceBridge.releaseDevice() }
            if (executingConversationId == conversationId) executingConversationId = null
        }
    }

    private suspend fun onServerRequest(context: RunContext, method: String, params: JsonObject): JsonElement =
        when (method) {
            "approval/request" -> {
                val requestId = params.text("requestId") ?: params.text("callId") ?: "approval-${System.nanoTime()}"
                val tool = params.text("toolName") ?: "unknown_tool"
                val reason = params.text("reason") ?: "DeepSeek Harness 请求执行这一步。"
                val decision = CompletableDeferred<String>()
                approvals[requestId] = PendingApproval(context.run.conversationId, decision)
                context.approvalRequest = requestId
                val state = TaskState.WaitingConfirmation(
                    dshConfirmationRequest(
                        tool = tool,
                        reason = reason,
                        packageName = app.packageName,
                    ),
                )
                context.approvalKey = ConfirmationNotifier.requestKey(state)
                context.approvalPostedAtMs = System.currentTimeMillis()
                publish(
                    context,
                    context.run.conversationId,
                    state,
                )
                val outcome = buildJsonObject { put("outcome", decision.await()) }
                context.approvalRequest = null
                context.approvalKey = null
                outcome
            }

            "user-question/ask" -> {
                val requestId = params.text("requestId") ?: "question-${System.nanoTime()}"
                val first = (params["questions"] as? JsonArray)?.firstOrNull() as? JsonObject
                val questionId = first?.text("id") ?: "answer"
                val prompt = first?.text("question") ?: first?.text("text") ?: "请补充信息"
                val options = (first?.get("options") as? JsonArray).orEmpty().mapNotNull { option ->
                    when (option) {
                        is JsonPrimitive -> option.contentOrNull
                        is JsonObject -> option.text("label")
                        else -> null
                    }
                }
                val answer = CompletableDeferred<JsonElement>()
                questions[requestId] = PendingQuestion(context.run.conversationId, questionId, options, answer)
                context.questionRequest = requestId
                publish(
                    context,
                    context.run.conversationId,
                    TaskState.WaitingInput(prompt, options.takeIf { it.isNotEmpty() }),
                )
                answer.await()
            }

            else -> throw JsonRpcException(-32601, "unsupported $method")
        }

    /** WaitingConfirmation 超过 ExternalNotifyPolicy.CONFIRM_TIMEOUT_MS 没人处理就按拒绝完成。 */
    private suspend fun awaitApprovalTimeout(context: RunContext) {
        val requestId = context.approvalRequest ?: return
        val postedAt = context.approvalPostedAtMs
        val remaining = ExternalNotifyPolicy.CONFIRM_TIMEOUT_MS - (System.currentTimeMillis() - postedAt)
        withTimeoutOrNull(remaining.coerceAtLeast(0)) {
            while (approvals.containsKey(requestId)) delay(1_000)
        } ?: run {
            if (context.approvalRequest != requestId) return@run
            approvals.remove(requestId)?.decision?.complete("rejected")
            context.approvalRequest = null
            context.approvalKey = null
            publish(context, context.run.conversationId, TaskState.Running(context.steps, "确认超时，已按拒绝处理"))
        }
    }

    private fun onNotification(context: RunContext, method: String, params: JsonObject) {
        if (method == "user-question.cancelled") {
            val requestId = params.text("requestId")
            if (requestId != null && context.questionRequest == requestId) {
                questions.remove(requestId)?.answer?.completeExceptionally(
                    JsonRpcException(-32000, "the question was cancelled"),
                )
                context.questionRequest = null
            }
            return
        }
        if (method == "session.status") {
            if (params.text("status") == "idle" && !context.turn.isCompleted) {
                context.completeTurn(
                    TaskState.Succeeded(
                        context.lastAnswer.ifBlank { "DeepSeek Harness 已完成任务，但没有返回文字结果。" },
                        verified = false,
                        reviewed = false,
                    ),
                )
            }
            return
        }
        if (method != "session.event") return
        val event = params["event"] as? JsonObject ?: return
        when (event.text("type")) {
            "session/title" -> {
                val data = event["data"] as? JsonObject
                data?.text("title")?.takeIf { it.isNotBlank() }?.let {
                    app.conversations.setTitle(context.run.conversationId, it)
                }
            }

            "assistant/message" -> {
                val data = event["data"] as? JsonObject
                val content = (data?.get("message") as? JsonObject)?.get("content")
                textOf(content).takeIf { it.isNotBlank() }?.let { text ->
                    context.lastAnswer = text
                    context.lastLine = text.lineSequence().firstOrNull().orEmpty().take(160)
                    publish(
                        context,
                        context.run.conversationId,
                        TaskState.Running(context.steps, context.lastLine, preview = text),
                    )
                }
            }

            "turn/start" -> {
                context.lastAnswer = ""
                context.lastLine = "正在思考"
            }

            "tool/call" -> {
                context.steps += 1
                val data = event["data"] as? JsonObject
                val tool = data?.text("name") ?: "工具"
                if (tool == "hand_to_user") {
                    context.lastLine = "等待用户在手机上处理"
                    publish(
                        context,
                        context.run.conversationId,
                        TaskState.WaitingTakeover(
                            reason = data?.text("reason")?.takeIf { it.isNotBlank() }?.let { reason ->
                                when {
                                    reason.contains("login", true) || reason.contains("登录") -> "login"
                                    reason.contains("captcha", true) || reason.contains("验证码") -> "captcha"
                                    reason.contains("pay", true) || reason.contains("支付") -> "payment"
                                    else -> "decision"
                                }
                            } ?: "decision",
                            message = data?.text("reason") ?: "这一步需要你在手机上亲自处理。",
                        ),
                    )
                } else {
                    context.lastLine = "正在执行 $tool"
                    publish(
                        context,
                        context.run.conversationId,
                        TaskState.Running(context.steps, context.lastLine, tool = tool),
                    )
                }
            }

            "turn/end" -> {
                val data = event["data"] as? JsonObject
                val reason = data?.get("reason") as? JsonObject
                when (reason?.text("kind")) {
                    "completed" -> context.completeTurn(
                        TaskState.Succeeded(
                            context.lastAnswer.ifBlank { "DeepSeek Harness 已完成任务，但没有返回文字结果。" },
                            verified = false,
                            reviewed = false,
                        ),
                    )
                    "error" -> {
                        val message = (reason["error"] as? JsonObject)?.text("message") ?: "模型请求失败"
                        context.completeTurn(TaskState.Failed(message, FailKind.Model))
                    }
                    "max-tokens" -> context.completeTurn(
                        TaskState.Failed("模型达到最大输出长度，任务未完整结束", FailKind.Model),
                    )
                    else -> context.completeTurn(
                        TaskState.Failed(
                            "DeepSeek Harness 已停止本轮：${reason?.text("kind") ?: "unknown"}",
                            FailKind.Model,
                        ),
                    )
                }
            }
        }
        Unit
    }

    private suspend fun finish(context: RunContext, end: TaskState) {
        val conversationId = context.run.conversationId
        val final = when (end) {
            is TaskState.Succeeded -> end
            is TaskState.Failed -> end
            TaskState.Cancelled -> end
            else -> {
                val answer = context.lastAnswer.ifBlank { "DeepSeek Harness 已完成任务，但没有返回文字结果。" }
                TaskState.Succeeded(answer, verified = false, reviewed = false)
            }
        }
        publish(context, conversationId, final)
        val turn = when (final) {
            is TaskState.Succeeded -> Turn(context.goal, final.answer, "succeeded", runId = context.run.key)
            is TaskState.Failed -> Turn(context.goal, final.reason, "failed", runId = context.run.key)
            TaskState.Cancelled -> Turn(
                context.goal,
                com.wangzan101.mika.core.agent.Conversation.CANCELLED_OUTCOME,
                "cancelled",
                runId = context.run.key,
            )
            else -> return
        }
        runCatching { app.conversations.append(conversationId, turn) }
            .onFailure { Log.w(TAG, "could not save DSH turn", it) }
        app.taskState.removeExternal(conversationId)
        TaskService.detachExternal(app, context.run)
    }

    private fun publish(context: RunContext, conversationId: String, state: TaskState) {
        app.taskState.updateExternal(conversationId, state)
        TaskService.notifyExternalState(app, context.run, state)
        when (state) {
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> Unit
            else -> TaskService.refreshExternal(app, context.run)
        }
    }

    private suspend fun fail(context: RunContext, message: String) {
        val end = TaskState.Failed(message, FailKind.Model)
        context.finish(end)
        finish(context, end)
    }

    private fun launchConfig(): DshLaunchConfig {
        val cfg = app.providers.configFor(ModelRole.Main)
            ?: error("请先在“模型”中为主对话选择一个支持工具调用的模型")
        val key = app.providers.apiKey(cfg.id)
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val deviceBudget = DshLaunchConfig(
            route = DshEngineRoute("", ""),
            totalRamMb = memInfo.totalMem / (1024 * 1024),
            isLowRam = memInfo.lowMemory,
        )
        return when {
            cfg.kind == ProviderConfig.Kind.OpenAiCompat &&
                Uri.parse(cfg.baseUrl).host == "api.deepseek.com" -> DshLaunchConfig(
                route = DshEngineRoute("deepseek-official", cfg.model),
                deepSeekApiKey = key,
                profilePatch = "[]",
                totalRamMb = deviceBudget.totalRamMb,
                isLowRam = deviceBudget.isLowRam,
            )

            cfg.kind == ProviderConfig.Kind.OpenAiCompat -> {
                val route = "mika-main"
                val envKey = "DSH_ANDROID_KEY_MIKA_MAIN"
                val providerJson = buildJsonObject {
                    putJsonObject("providers") {
                        putJsonObject(route) {
                            put("displayName", cfg.name.ifBlank { "Mika main model" })
                            put("interfaceType", "openai-chat-completions")
                            put("baseURL", cfg.baseUrl)
                            if (!key.isNullOrBlank()) put("apiKeyEnv", envKey)
                            put("includeUsage", true)
                            putJsonArray("models") {
                                addJsonObject {
                                    put("id", cfg.model)
                                    put("contextWindow", cfg.capabilities.contextTokens)
                                    putJsonObject("defaults") { put("maxTokens", 16_384) }
                                }
                            }
                        }
                    }
                }
                val compactionJson = buildJsonObject {
                    putJsonArray("modelPolicies") {
                        addJsonObject {
                            put("provider", route)
                            put("model", cfg.model)
                            put("headroomTokens", 16_384)
                        }
                    }
                }
                DshLaunchConfig(
                    route = DshEngineRoute(route, cfg.model),
                    profilePatch = """
                        - id: llm-service-lite
                          config: $providerJson
                        - id: compaction-basic
                          config: $compactionJson
                    """.trimIndent(),
                    environment = if (key.isNullOrBlank()) emptyMap() else mapOf(envKey to key),
                    totalRamMb = deviceBudget.totalRamMb,
                    isLowRam = deviceBudget.isLowRam,
                )
            }

            else -> error("DeepSeek Harness 当前支持 DeepSeek 和 OpenAI 兼容接口；请为主模型选择这两类服务商")
        }
    }

    private fun textOf(content: JsonElement?): String =
        (content as? JsonArray)?.mapNotNull { block ->
            (block as? JsonObject)?.takeIf { it.text("type") == "text" }?.text("text")
        }?.joinToString("\n")?.trim()
            ?: ((content as? JsonPrimitive)?.contentOrNull ?: "")

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private data class PendingApproval(
        val conversationId: String,
        val decision: CompletableDeferred<String>,
    )

    private data class PendingQuestion(
        val conversationId: String,
        val questionId: String,
        val options: List<String>,
        val answer: CompletableDeferred<JsonElement>,
    )

    private class RunContext(
        val run: ExternalTaskRun,
        val goal: String,
    ) {
        val cancelled = AtomicBoolean(false)
        val terminal = CompletableDeferred<TaskState>()
        @Volatile var turn = CompletableDeferred<TaskState>()
        val pendingSteers = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var steps: Int = 0
        var lastLine: String = ""
        var lastAnswer: String = ""
        var approvalRequest: String? = null
        var approvalKey: Int? = null
        @Volatile var approvalPostedAtMs: Long = 0L
        var questionRequest: String? = null

        fun startTurn() {
            turn = CompletableDeferred()
        }

        fun completeTurn(state: TaskState) {
            turn.complete(state)
        }

        fun drainSteers(): List<String> = buildList {
            while (true) add(pendingSteers.poll() ?: break)
        }

        fun finish(state: TaskState) {
            terminal.complete(state)
            if (!turn.isCompleted) turn.complete(state)
        }
    }

    private companion object {
        const val TAG = "DshTaskRunner"
    }
}
