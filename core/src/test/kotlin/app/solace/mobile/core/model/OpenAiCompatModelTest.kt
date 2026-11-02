package app.solace.mobile.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun caps(vision: Boolean = false) = Capabilities(true, vision, 32000, false)
    private fun model(vision: Boolean = false, key: String? = "sk-x") =
        OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), key, "m", caps(vision), OkHttpClient())
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Solace))
    private fun ok(body: String) = MockResponse().setResponseData(body)
    private fun MockResponse.setResponseData(b: String) = setHeader("Content-Type", "application/json").setBody(b)

    private fun body(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun parsesToolCalls() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"phone_tap","arguments":"{\"ref\":\"a1\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":4}}}"""))
        val r = model().complete("sys", user, listOf(ToolSpec("phone_tap", "d", """{"type":"object"}""")))
        assertTrue(r.parts.contains(Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""")))
        assertEquals(StopReason.ToolUse, r.stopReason)
        assertEquals(Usage(10, 3, 4), r.usage)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-x", req.getHeader("Authorization"))
        val j = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("system", j["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("function", j["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun omitsToolsAndAuthWhenEmpty() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"length","message":{"content":"x"}}]}"""))
        val r = model(key = null).complete("s", user, emptyList())
        assertEquals(StopReason.MaxTokens, r.stopReason)
        assertNull(r.usage)
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertFalse(req.body.readUtf8().contains("\"tools\""))
    }

    @Test fun readsReasoningContent() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok","reasoning_content":"想一想"}}]}"""))
        val r = model().complete("s", user, emptyList())
        assertTrue(r.parts.contains(Part.Thinking("想一想")))
        assertTrue(r.parts.contains(Part.Text("ok")))
        assertEquals(StopReason.EndTurn, r.stopReason)
    }

    private val withImage = listOf(Message(Role.User, listOf(Part.Text("look"), Part.Image("QUJD")), Author.Solace))

    @Test fun sendsImagesOnlyWhenVision() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model(vision = false).complete("s", withImage, emptyList())
        val off = server.takeRequest().body.readUtf8()
        assertFalse(off.contains("image_url"))
        assertTrue(off.contains("[截图已省略:当前模型不支持看图]"))

        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model(vision = true).complete("s", withImage, emptyList())
        assertTrue(server.takeRequest().body.readUtf8().contains("data:image/png;base64,QUJD"))
    }

    @Test fun sendsJpegMime() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val jpeg = listOf(Message(Role.User, listOf(Part.Image("QUJD", mime = "image/jpeg")), Author.Solace))
        model(vision = true).complete("s", jpeg, emptyList())
        assertTrue(server.takeRequest().body.readUtf8().contains("data:image/jpeg;base64,QUJD"))
    }

    private suspend fun errorFor(resp: MockResponse): ModelError {
        server.enqueue(resp)
        return try { model().complete("s", user, emptyList()); throw AssertionError("no error") } catch (e: ModelError) { e }
    }

    @Test fun mapsErrors() = runTest {
        assertTrue(errorFor(MockResponse().setResponseCode(401)) is ModelError.Auth)
        assertTrue(errorFor(MockResponse().setResponseCode(402)) is ModelError.QuotaExceeded)
        assertTrue(errorFor(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"insufficient_quota"}}""")) is ModelError.QuotaExceeded)
        val rl = errorFor(MockResponse().setResponseCode(429).setHeader("retry-after", "2")) as ModelError.RateLimited
        assertEquals(2000L, rl.retryAfterMs)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("context_length_exceeded")) is ModelError.ContextTooLong)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("exceeds the maximum context")) is ModelError.ContextTooLong)
        assertTrue(errorFor(MockResponse().setResponseCode(404)) is ModelError.ModelNotFound)
        assertEquals(503, (errorFor(MockResponse().setResponseCode(503)) as ModelError.Server).status)
        assertTrue(errorFor(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) is ModelError.Network)
    }

    @Test fun badResponses() = runTest {
        assertTrue(errorFor(ok("not json")) is ModelError.BadResponse)
        assertTrue(errorFor(ok("""{"choices":[]}""")) is ModelError.BadResponse)
        assertTrue(errorFor(ok("""{"foo":1}""")) is ModelError.BadResponse)
    }

    @Test fun toolResultRoundTrip() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val hist = listOf(
            Message(Role.User, listOf(Part.Text("go")), Author.Solace),
            Message(Role.Assistant, listOf(Part.Thinking("secret"), Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""")), Author.Solace),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "done", false)), Author.Solace),
        )
        model().complete("s", hist, emptyList())
        val raw = server.takeRequest().body.readUtf8()
        assertFalse(raw.contains("secret"))
        val msgs: JsonArray = Json.parseToJsonElement(raw).jsonObject["messages"]!!.jsonArray
        val asst = msgs[2].jsonObject
        assertEquals("assistant", asst["role"]!!.jsonPrimitive.content)
        assertEquals("c1", asst["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("""{"ref":"a1"}""", asst["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        val tool = msgs[3].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("c1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("done", tool["content"]!!.jsonPrimitive.content)
    }

    @Test fun deepSeekReplaysReasoningForToolRequests() = runTest {
        val m = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "deepseek-v4-pro", caps(), OkHttpClient())
        val tools = listOf(ToolSpec("phone_tap", "d", """{"type":"object"}"""))
        server.enqueue(ok("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"reasoning_content":"先找入口","tool_calls":[{"id":"c1","type":"function","function":{"name":"phone_tap","arguments":"{}"}}]}}]}"""))
        val first = m.complete("s", user, tools)
        assertTrue(first.parts.contains(Part.Thinking("先找入口")))
        server.takeRequest()

        val history = user + listOf(
            Message(Role.Assistant, first.parts, Author.Solace),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "done", false)), Author.Solace),
            Message(Role.Assistant, listOf(Part.Thinking("接着核对"), Part.Text("已打开")), Author.Solace),
            Message(Role.User, listOf(Part.Text("再看一下")), Author.User),
        )
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("s", history, tools)
        val messages = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("先找入口", messages[2]["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("接着核对", messages[4]["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, messages[2]["content"])
        assertEquals("c1", messages[2]["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)

        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("s", history, emptyList())
        val withoutTools = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertFalse("reasoning_content" in withoutTools[2])
        assertFalse("reasoning_content" in withoutTools[4])
    }

    @Test fun otherCompatibleModelsDoNotReplayReasoningWithTools() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val history = user + Message(Role.Assistant, listOf(Part.Thinking("private"), Part.Text("next")), Author.Solace)
        model().complete("s", history, listOf(ToolSpec("phone_tap", "d", """{"type":"object"}""")))
        val messages = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertFalse("reasoning_content" in messages[2])
    }

    @Test fun modelsListedWithReasoningContentFieldReplayItWithTools() = runTest {
        val m = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "kimi-k3", caps(), OkHttpClient(), "reasoning_content")
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val history = user + Message(Role.Assistant, listOf(Part.Thinking("先想"), Part.Text("next")), Author.Solace)
        m.complete("s", history, listOf(ToolSpec("phone_tap", "d", """{"type":"object"}""")))
        assertEquals("先想", body()["messages"]!!.jsonArray[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test fun readsPromptCacheHitTokens() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":900,"completion_tokens":10,"prompt_cache_hit_tokens":640,"prompt_cache_miss_tokens":260}}"""))
        assertEquals(Usage(900, 10, 640), model().complete("sys", user, emptyList()).usage)
        // 缺 prompt_tokens 时用命中 + 未命中之和
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"completion_tokens":10,"prompt_cache_hit_tokens":640,"prompt_cache_miss_tokens":260}}"""))
        assertEquals(Usage(900, 10, 640), model().complete("sys", user, emptyList()).usage)
    }

    @Test fun readsCachedTokensDetails() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":500,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":128}}}"""))
        assertEquals(Usage(500, 7, 128), model().complete("sys", user, emptyList()).usage)
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":500,"completion_tokens":7}}"""))
        assertEquals(Usage(500, 7, 0), model().complete("sys", user, emptyList()).usage)
    }

    @Test fun cachedTakesLargerFieldClampedToInput() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":900,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":100},"prompt_cache_hit_tokens":640}}"""))
        assertEquals(Usage(900, 1, 640), model().complete("sys", user, emptyList()).usage)
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":900,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":1000}}}"""))
        assertEquals(Usage(900, 1, 900), model().complete("sys", user, emptyList()).usage)
    }

    // 显式缓存:系统提示词和最后一条能标的消息上带 cache_control;前面的消息原样,两次请求的开头逐字相同
    @Test fun explicitCachingMarksSystemAndLastMessage() = runTest {
        val m = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m",
            Capabilities(true, true, 32000, false, promptCaching = PromptCaching.Explicit), OkHttpClient())
        val history = listOf(
            Message(Role.User, listOf(Part.Text("任务")), Author.Solace),
            Message(Role.Assistant, listOf(Part.ToolCall("c1", "phone_observe", "{}")), Author.Solace),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "屏幕", false)), Author.Solace),
        )
        val reply = """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}],"usage":{"prompt_tokens":3000,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":2048,"cache_creation_input_tokens":900}}}"""
        server.enqueue(ok(reply)); server.enqueue(ok(reply))
        val r = m.complete("SYS", history, emptyList())
        assertEquals(Usage(3000, 5, 2048), r.usage)
        val first = body()["messages"]!!.jsonArray.map { it.jsonObject }
        fun marks(msgs: List<kotlinx.serialization.json.JsonObject>) = msgs.mapIndexedNotNull { i, msg ->
            (msg["content"] as? JsonArray)?.takeIf { c -> c.any { "cache_control" in it.jsonObject } }?.let { i }
        }
        assertEquals(listOf(0, 3), marks(first))
        assertEquals("ephemeral", first[0]["content"]!!.jsonArray.last().jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("屏幕", first[3]["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("tool", first[3]["role"]!!.jsonPrimitive.content)

        // 下一步:多了一轮;标记挪到新的最后一条,之前那条恢复原样(服务端按内容找开头,标记本身不算内容)
        m.complete("SYS", history + Message(Role.Assistant, listOf(Part.ToolCall("c2", "phone_tap", "{}")), Author.Solace) +
            Message(Role.Tool, listOf(Part.ToolResult("c2", "点了", false)), Author.Solace), emptyList())
        val second = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(0, 5), marks(second))
        assertEquals(first.subList(1, 3), second.subList(1, 3))
    }

    @Test fun implicitCachingSendsNoMarks() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model().complete("SYS", user, emptyList())
        assertFalse(body().toString().contains("cache_control"))
    }
}
