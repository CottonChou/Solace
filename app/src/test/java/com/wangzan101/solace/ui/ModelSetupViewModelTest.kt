package com.wangzan101.solace.ui

import com.wangzan101.solace.i18n.ChineseTexts
import com.wangzan101.solace.core.model.Capabilities
import com.wangzan101.solace.core.model.ChatModel
import com.wangzan101.solace.core.model.ListFailure
import com.wangzan101.solace.core.model.ListedModel
import com.wangzan101.solace.core.model.Message
import com.wangzan101.solace.core.model.ModelError
import com.wangzan101.solace.core.model.ModelListResult
import com.wangzan101.solace.core.model.ModelReply
import com.wangzan101.solace.core.model.ModelRole
import com.wangzan101.solace.core.model.Part
import com.wangzan101.solace.core.model.StopReason
import com.wangzan101.solace.core.model.ToolSpec
import com.wangzan101.solace.settings.AesGcmKeyCipher
import com.wangzan101.solace.settings.ClientSetup
import com.wangzan101.solace.settings.ProviderConfig
import com.wangzan101.solace.settings.ProviderPresets
import com.wangzan101.solace.settings.ProviderStore
import com.wangzan101.solace.settings.RoleChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSetupViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    /** 按地址给出列模型的结果;没写的地址列出空表。 */
    private val listing = mutableMapOf<String, ModelListResult>()
    private val listCalls = mutableListOf<Triple<ProviderConfig.Kind, String, String?>>()
    private var ids = 0

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        newId = { "id${++ids}" },
        userAgent = "Solace/1.2.3",
        modelFactory = { cfg, _, _, setup -> built += cfg.model to setup; Fake(cfg.capabilities) { reply() } },
        modelLister = { kind, url, apiKey, _ -> listCalls += Triple(kind, url, apiKey); listing[url] ?: ModelListResult.Ok(emptyList()) },
    )

    /** 建过的模型(模型名、请求头等);「测试并保存」的试调用回什么由 [reply] 定,默认回一句。 */
    private val built = mutableListOf<Pair<String, ClientSetup>>()
    private var reply: suspend () -> ModelReply = { ModelReply(listOf(Part.Text("pong")), StopReason.EndTurn, null) }
    private val probes = mutableListOf<Triple<String, List<Message>, List<ToolSpec>>>()

    private inner class Fake(override val capabilities: Capabilities, private val answer: suspend () -> ModelReply) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            probes += Triple(system, messages, tools)
            return answer()
        }
    }

    private fun TestScope.vm(s: ProviderStore = store()) =
        ModelSetupViewModel(s, backgroundScope, io = StandardTestDispatcher(testScheduler))

    private val deepseekUrl = "https://api.deepseek.com"
    private val qwenUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val deepseek = ModelListResult.Ok(
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-reasoner"), ListedModel("deepseek-coder", toolCalls = false)),
    )

    @Test fun testAndSaveStoresProviderAndPicksFirstModelAsMain() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        runCurrent()
        assertTrue("还没有服务商时表单展开", vm.state.value.showForm)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.canNext)
        vm.setApiKey("sk-1")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave()
        assertEquals(ListState.Running, vm.state.value.form.test)
        runCurrent()
        assertEquals(Triple(ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk-1"), listCalls.single())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals(3, p.models?.size)
        assertEquals("sk-1", store.providerKey(p.id))
        // 自动选第一个模型为主模型(只存这一条配置)
        assertEquals(listOf("deepseek-chat"), store.list().map { it.model })
        assertEquals(store.list().single().id, store.roles.value.main)
        val s = vm.state.value
        assertEquals(listOf(p), s.providers)
        assertFalse("保存后表单收起", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertTrue(s.canNext)
    }

    @Test fun failedTestShowsReasonAndSavesNothing() = runTest {
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Auth)
        val store = store()
        val vm = vm(store)
        vm.setApiKey("bad"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
        vm.setApiKey("good")
        assertEquals(ListState.Idle, vm.state.value.form.test)
        assertEquals("good", vm.state.value.form.apiKey)
    }

    /** 列出模型后让会当主模型的那个回一句:不带工具、回复限短、带客户端标识;成功才存。 */
    @Test fun testAndSaveSendsOneTinyRequestToTheMainCandidate() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val (model, setup) = built.first()
        assertEquals("deepseek-chat", model)
        assertEquals(16, setup.maxOutputTokens)
        assertEquals("Solace/1.2.3", setup.headers["User-Agent"])
        assertEquals("DeepSeek 没声明会话头", setOf("User-Agent"), setup.headers.keys)
        val (_, messages, tools) = probes.single()
        assertEquals(1, messages.size)
        assertTrue(tools.isEmpty())
        assertEquals(1, store.providers.value.size)
    }

    /** 试调用失败(例如服务商要的请求头没带):服务端的报错原样给人看,什么都不存。 */
    @Test fun failedTinyRequestShowsTheServerErrorAndSavesNothing() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { throw ModelError.BadResponse("HTTP 400 {\"error\":{\"type\":\"MissingSessionID\"}}") }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("MissingSessionID"))
        assertTrue(failed.message.contains("deepseek-chat"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertTrue(vm.state.value.showForm)
    }

    @Test fun tinyRequestThatNeverAnswersTimesOut() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { awaitCancellation() }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Running, vm.state.value.form.test)
        advanceTimeBy(ModelSetupViewModel.PROBE_TIMEOUT_MS + 1); runCurrent()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("20"))
        assertTrue(store.providers.value.isEmpty())
    }

    /** 预设声明了会话头的(OpenCode Go):试调用就带上,存下的服务商也记着它。 */
    @Test fun presetSessionHeaderIsUsedForTheTestAndRemembered() = runTest {
        val go = ProviderPresets.all.first { it.sessionHeader != null }
        listing[go.baseUrl] = ModelListResult.Ok(listOf(ListedModel("glm-x", toolCalls = true)))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(go.label); vm.setApiKey("k"); vm.testAndSave(); runCurrent()
        val setup = built.first().second
        assertTrue(setup.headers[go.sessionHeader!!].orEmpty().isNotEmpty())
        assertEquals(go.sessionHeader, store.providers.value.single().sessionHeader)
    }

    /** 服务不提供列表(没有可试的模型):不试,照旧存下。 */
    @Test fun noListedModelsMeansNoTinyRequest() = runTest {
        val url = "http://192.168.1.5:8000/v1"
        listing[url] = ModelListResult.Failed(ListFailure.NotSupported)
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.testAndSave(); runCurrent()
        assertTrue(probes.isEmpty())
        assertEquals(1, store.providers.value.size)
    }

    /** 明文 http:本机、局域网的不提示;别的地址提醒一句,照样能测试。 */
    @Test fun plainHttpToARemoteHostWarnsButDoesNotBlock() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://192.168.1.10:11434/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("http://models.example.com/v1")
        assertEquals("这个地址不加密，API Key 和对话内容可能被同一网络里的人看到。", vm.state.value.form.unencryptedWarning)
        vm.setApiKey("k")
        assertTrue(vm.state.value.form.canTest)
    }

    @Test fun addingTwoProvidersInARowKeepsTheFirstMain() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-vl", vision = true), ListedModel("qwen-max")))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val main = store.roles.value.main
        vm.openForm()
        assertTrue(vm.state.value.showForm)
        vm.selectPreset("通义千问")
        vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("DeepSeek", "通义千问"), vm.state.value.providers.map { it.name })
        assertEquals(listOf("sk", "qk"), store.providers.value.map { store.providerKey(it.id) })
        assertEquals("第二家不改主模型", main, store.roles.value.main)
        assertEquals(1, store.list().size)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun visionCanComeFromAnotherProvider() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-max"), ListedModel("qwen-plain", toolCalls = false)))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        vm.openForm(); vm.selectPreset("通义千问"); vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        val qwen = vm.state.value.providers.first { it.name == "通义千问" }
        // 选择页列出所有服务商的全部模型,不按能力过滤
        assertEquals(listOf(3, 2), vm.state.value.providers.map { vm.state.value.options(it).size })
        vm.pick(ModelRole.Vision, qwen.id, "qwen-plain"); runCurrent()
        val vision = store.configFor(ModelRole.Vision)!!
        assertEquals("qwen-plain", vision.model)
        assertEquals(qwen.id, vision.providerId)
        assertTrue("选了看图就记看图", vision.capabilities.vision)
        assertEquals("qk", store.apiKey(vision.id))
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(listOf(ModelRole.Vision), vm.state.value.rolesOf(qwen))
        // 用主模型:看图这一路用主模型,不再有单独的看图配置
        vm.choose(ModelRole.Vision, RoleChoice.SameAsMain); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Vision)?.model)
        assertFalse(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(1, store.list().size)
        assertEquals(emptyList<ModelRole>(), vm.state.value.rolesOf(qwen))
        // 不用
        vm.choose(ModelRole.Vision, RoleChoice.Off); runCurrent()
        assertNull(store.configFor(ModelRole.Vision))
        assertEquals(RoleChoice.Off, vm.state.value.roles.vision)
    }

    @Test fun pickingMainMarksToolCallsAndSwitchesProvider() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        vm.pick(ModelRole.Main, p.id, "deepseek-coder"); runCurrent()
        val main = store.configFor(ModelRole.Main)!!
        assertEquals("deepseek-coder", main.model)
        assertTrue(main.capabilities.toolCalls)
        vm.pick(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertEquals("没人用的配置删掉", 1, store.list().size)
    }

    @Test fun nextNeedsAMainModel() = runTest {
        val store = store()
        val vm = vm(store)
        vm.selectPreset("自定义")
        vm.setBaseUrl("http://192.168.1.5:8000/v1")
        assertTrue("自定义的 OpenAI 兼容接口可以不填 Key", vm.state.value.form.canTest)
        listing["http://192.168.1.5:8000/v1"] = ModelListResult.Failed(ListFailure.NotSupported)
        vm.testAndSave(); runCurrent()
        // 服务不提供列表:照样连上、存下,但没有模型可自动选
        val p = store.providers.value.single()
        assertEquals("192.168.1.5", p.name)
        assertEquals(emptyList<ListedModel>(), p.models)
        assertFalse(vm.state.value.canNext)
        vm.pick(ModelRole.Main, p.id, " qwen3 "); runCurrent()
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.canNext)
        assertEquals(listOf("qwen3"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
    }

    @Test fun pickerListsProvidersWithoutRememberedListOnce() = runTest {
        val store = store()
        store.save(ProviderConfig("old", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", "claude-x",
            Capabilities(true, true, 200_000, false), "Anthropic"), "ak")
        store.choose(ModelRole.Main, RoleChoice.Model("old"))
        listing["https://api.anthropic.com"] = ModelListResult.Ok(listOf(ListedModel("claude-x"), ListedModel("claude-y")))
        val vm = vm(store)
        runCurrent()
        val p = vm.state.value.providers.single()
        assertNull(p.models)
        assertEquals(listOf("claude-x"), vm.state.value.options(p).map { it.id })
        vm.ensureListed(); runCurrent()
        vm.ensureListed(); runCurrent()
        assertEquals(1, listCalls.size)
        assertEquals("ak", listCalls.single().third)
        assertEquals(listOf("claude-x", "claude-y"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
        assertTrue("已有的配置保留它的能力", vm.state.value.options(vm.state.value.providers.single()).first().capabilities.vision)
    }

    @Test fun refreshFailureIsShownAndRemovingProviderDropsItsRoles() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Network)
        vm.refresh(p.id); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Network.text), vm.state.value.listing[p.id])
        assertEquals("失败时保留原来的列表", 3, store.providers.value.single().models?.size)
        vm.changeKey(p.id, "sk2"); runCurrent()
        assertEquals("sk2", store.providerKey(p.id))
        vm.removeProvider(p.id); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertEquals(com.wangzan101.solace.settings.RoleChoices(), store.roles.value)
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
    }

    @Test fun copyTagsMarkGuessedCapabilities() {
        val o = com.wangzan101.solace.settings.ModelOptions.merge(
            listOf(ListedModel("a"), ListedModel("b", toolCalls = true, contextTokens = 64_000), ListedModel("c", toolCalls = false)),
            Capabilities(true, false, 64_000, false),
        )
        assertEquals(listOf("工具调用 · 未确认" to false), ModelSetupCopy.tags(o[0]))
        assertEquals(listOf("工具调用" to true, "64K" to false), ModelSetupCopy.tags(o[1]))
        assertEquals(listOf("不支持工具调用" to false), ModelSetupCopy.tags(o[2]))
    }
}
