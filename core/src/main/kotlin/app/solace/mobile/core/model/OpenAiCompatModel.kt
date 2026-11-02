package app.solace.mobile.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Chat Completions 兼容端点(DeepSeek/Qwen/Kimi/GLM/OpenRouter/本地服务等)。差异只放在 [capabilities]。
 * [reasoningField] 是模型列表里写明的思考内容字段;为 `reasoning_content` 时,带工具的请求回传此前的思考。
 */
class OpenAiCompatModel(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    reasoningField: String? = null,
    /** 每个请求都带的头(客户端标识、服务商要的会话号等,见 [ClientHeaders]);Key 的头由适配器自己加。 */
    private val headers: Map<String, String> = emptyMap(),
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
    private val replayReasoningContent = reasoningField == "reasoning_content" ||
        model.startsWith("deepseek-", ignoreCase = true) || baseUrl.toHttpUrlOrNull()?.host == "api.deepseek.com"
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        val MARKABLE_ROLES = setOf("system", "user", "tool")
        val EPHEMERAL = buildJsonObject { put("type", "ephemeral") }
    }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = HttpSupport.execute(http, request(system, messages, tools, stream = false))
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var stream = OpenAiStream(onText, ::parseUsage)
        var response = HttpSupport.stream(http, request(system, messages, tools, stream = true), stream::accept)
        if (response.status == 400 && response.body.contains("stream_options")) {
            // 一些兼容端点能流式生成，却没有用量选项。
            stream = OpenAiStream(onText, ::parseUsage)
            response = HttpSupport.stream(http, request(system, messages, tools, stream = true, includeUsage = false), stream::accept)
        }
        val (status, retryAfter, text) = response
        if (status !in 200..299) {
            // 不支持流式传输的兼容端点仍可按原接口完成任务。
            if (status == 400 && text.contains("stream", ignoreCase = true)) return complete(system, messages, tools).also {
                it.parts.filterIsInstance<Part.Text>().joinToString("") { part -> part.text }.takeIf(String::isNotEmpty)?.let(onText)
            }
            throw mapError(status, retryAfter, text)
        }
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    private fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean, includeUsage: Boolean = true): Request {
        val payload = buildJsonObject {
            put("model", model)
            val replayReasoning = replayReasoningContent && tools.isNotEmpty()
            val wire = listOf(buildJsonObject { put("role", "system"); put("content", system) }) +
                messages.flatMap { serialize(it, replayReasoning) }
            put("messages", JsonArray(if (capabilities.promptCaching == PromptCaching.Explicit) withCacheMarks(wire) else wire))
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(toolJson(it)) } }
            if (stream) {
                put("stream", true)
                if (includeUsage) putJsonObject("stream_options") { put("include_usage", true) }
            }
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * 显式缓存:标在系统提示词上(每次都一样),再标在最后一条能标的消息上(缓存到这次为止的整段对话,
     * 下一次请求的开头与它相同时就从缓存取)。服务端从标记往回找相同的开头,所以较早的消息不能被改写。
     * 内容是字符串的换成一段文字块再标;助手消息(可能只有工具调用)不标。
     */
    private fun withCacheMarks(wire: List<JsonObject>): List<JsonObject> {
        val last = wire.indexOfLast { (it["role"] as? JsonPrimitive)?.contentOrNull in MARKABLE_ROLES && it["content"] !is JsonNull }
        return wire.mapIndexed { i, m -> if (i == 0 || i == last) marked(m) else m }
    }

    private fun marked(m: JsonObject): JsonObject {
        val blocks = when (val c = m["content"]) {
            is JsonPrimitive -> listOf(buildJsonObject { put("type", "text"); put("text", c.content) })
            is JsonArray -> c.map { it.jsonObject }
            else -> return m
        }
        if (blocks.isEmpty()) return m
        val withMark = blocks.dropLast(1) + JsonObject(blocks.last() + ("cache_control" to EPHEMERAL))
        return JsonObject(m + ("content" to JsonArray(withMark)))
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = when {
        status == 401 -> ModelError.Auth()
        status == 402 || body.contains("insufficient_quota") -> ModelError.QuotaExceeded()
        status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
        status == 400 && (body.contains("context_length") || body.contains("maximum context")) -> ModelError.ContextTooLong()
        status == 404 -> ModelError.ModelNotFound()
        status in 500..599 -> ModelError.Server(status)
        status == 403 -> ModelError.Auth()
        else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
    }

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", t.name)
            put("description", t.description)
            put("parameters", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
                throw IllegalArgumentException("工具 ${t.name} 的参数 schema 不是合法 JSON", e)
            })
        }
    }

    /** 一条内部消息可能展开成多条线上消息(tool 结果各占一条,图片只能放 user 消息)。 */
    private fun serialize(m: Message, replayReasoning: Boolean): List<JsonObject> {
        if (m.role == Role.Assistant) return listOf(assistantJson(m, replayReasoning))
        val out = mutableListOf<JsonObject>()
        m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
            out += buildJsonObject {
                put("role", "tool"); put("tool_call_id", r.callId); put("content", r.content)
            }
        }
        val rest = m.parts.filter { it is Part.Text || it is Part.Image }
        if (rest.isNotEmpty()) {
            val role = if (m.role == Role.System) "system" else "user"
            val hasImage = rest.any { it is Part.Image } && capabilities.vision
            out += buildJsonObject {
                put("role", role)
                if (!hasImage) {
                    put("content", rest.joinToString("\n") { textOf(it) })
                } else {
                    putJsonArray("content") {
                        rest.forEach { p ->
                            add(buildJsonObject {
                                if (p is Part.Image) {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", "data:${p.mime};base64,${p.pngBase64}") }
                                } else {
                                    put("type", "text"); put("text", textOf(p))
                                }
                            })
                        }
                    }
                }
            }
        }
        return out
    }

    private fun textOf(p: Part): String = when (p) {
        is Part.Text -> p.text
        is Part.Image -> "[截图已省略:当前模型不支持看图]"
        else -> ""
    }

    // DeepSeek(以及列表里说思考放在 reasoning_content 的模型)的带工具请求要原样回传此前助手消息的 reasoning_content。
    private fun assistantJson(m: Message, replayReasoning: Boolean): JsonObject {
        val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        val thinking = m.parts.filterIsInstance<Part.Thinking>().joinToString("") { it.text }
        val calls = m.parts.filterIsInstance<Part.ToolCall>()
        return buildJsonObject {
            put("role", "assistant")
            if (text.isEmpty() && calls.isNotEmpty()) put("content", JsonNull) else put("content", text)
            if (replayReasoning && thinking.isNotEmpty()) put("reasoning_content", thinking)
            if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                calls.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id); put("type", "function")
                        putJsonObject("function") { put("name", c.name); put("arguments", c.argsJson) }
                    })
                }
            }
        }
    }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ModelError.BadResponse("缺少 choices")
            val msg = choice["message"]?.jsonObject ?: throw ModelError.BadResponse("缺少 message")
            val parts = mutableListOf<Part>()
            (msg["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?.let { parts += Part.Thinking(it) }
            (msg["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?.let { parts += Part.Text(it) }
            (msg["tool_calls"] as? JsonArray)?.forEach { el ->
                val c = el.jsonObject
                val fn = c["function"]!!.jsonObject
                parts += Part.ToolCall(
                    c["id"]!!.jsonPrimitive.content,
                    fn["name"]!!.jsonPrimitive.content,
                    fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                )
            }
            val stop = when ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull) {
                "stop" -> StopReason.EndTurn
                "tool_calls", "function_call" -> StopReason.ToolUse
                "length" -> StopReason.MaxTokens
                else -> if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.Other
            }
            return ModelReply(parts, stop, parseUsage(root["usage"]))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull
        // 兼容接口有两种命中字段名;按字段是否存在取值
        val hit = int(u, "prompt_cache_hit_tokens")
        val miss = int(u, "prompt_cache_miss_tokens")
        val inT = int(u, "prompt_tokens") ?: if (hit != null && miss != null) hit + miss else return null
        val outT = int(u, "completion_tokens") ?: 0
        val cached = maxOf(int(u["prompt_tokens_details"] as? JsonObject, "cached_tokens") ?: 0, hit ?: 0).coerceAtMost(inT)
        return Usage(inT, outT, cached)
    }
}

/** Chat Completions 的 delta 可以把同一个工具的名称与参数拆成多帧。 */
private class OpenAiStream(
    private val onText: (String) -> Unit,
    private val usageOf: (JsonElement?) -> Usage?,
) {
    private data class Call(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder())

    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val thinking = StringBuilder()
    private val calls = sortedMapOf<Int, Call>()
    private var usage: Usage? = null
    private var reason: String? = null
    private var done = false
    private var chunks = 0

    fun accept(data: String) {
        if (data == "[DONE]") { done = true; return }
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("流式响应不是合法 JSON", e) }
        if (root["error"] != null) throw ModelError.BadResponse(root["error"].toString().take(200))
        chunks++
        usageOf(root["usage"])?.let { usage = it }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { reason = it }
        val delta = choice["delta"] as? JsonObject ?: return
        (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.let { thinking.append(it) }
        (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            visible.append(it)
            onText(visible.toString())
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            val call = calls.getOrPut(index, ::Call)
            (obj["id"] as? JsonPrimitive)?.contentOrNull?.let { call.id.append(it) }
            val fn = obj["function"] as? JsonObject
            (fn?.get("name") as? JsonPrimitive)?.contentOrNull?.let { call.name.append(it) }
            (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { call.args.append(it) }
        }
    }

    fun finish(): ModelReply {
        if (!done || chunks == 0) throw ModelError.BadResponse("流式响应提前结束")
        val parts = buildList<Part> {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking.toString()))
            if (visible.isNotEmpty()) add(Part.Text(visible.toString()))
            calls.values.forEach { c ->
                if (c.name.isEmpty() || c.id.isEmpty()) throw ModelError.BadResponse("工具调用流缺少名称或编号")
                add(Part.ToolCall(c.id.toString(), c.name.toString(), c.args.toString().ifEmpty { "{}" }))
            }
        }
        val stop = when (reason) {
            "stop" -> StopReason.EndTurn
            "tool_calls", "function_call" -> StopReason.ToolUse
            "length" -> StopReason.MaxTokens
            else -> if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.Other
        }
        return ModelReply(parts, stop, usage)
    }
}
