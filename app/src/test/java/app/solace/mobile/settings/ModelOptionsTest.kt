package app.solace.mobile.settings

import app.solace.mobile.core.model.Capabilities
import app.solace.mobile.core.model.ListedModel
import app.solace.mobile.core.model.ModelEndpoint
import app.solace.mobile.core.model.ModelProtocol
import app.solace.mobile.core.model.PromptCaching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOptionsTest {
    private val base = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Explicit)

    @Test fun unknownCapabilitiesKeepPresetValues() {
        val o = ModelOptions.merge(listOf(ListedModel("m")), base).single()
        assertEquals(base, o.capabilities)
        assertFalse(o.toolCallsKnown)
        assertFalse(o.visionKnown)
        assertFalse(o.contextKnown)
    }

    @Test fun knownCapabilitiesOverridePresetButKeepCaching() {
        val o = ModelOptions.merge(listOf(ListedModel("m", toolCalls = false, vision = true, contextTokens = 1_000_000, reasoning = true)), base).single()
        assertEquals(Capabilities(false, true, 1_000_000, true, promptCaching = PromptCaching.Explicit), o.capabilities)
        assertTrue(o.toolCallsKnown && o.visionKnown && o.contextKnown)
        assertFalse(o.canBeMain)
    }

    @Test fun toolCapableFirstAndDefaultMainPrefersConfirmed() {
        val listed = listOf(ListedModel("embed", toolCalls = false), ListedModel("guess"), ListedModel("sure", toolCalls = true))
        val options = ModelOptions.merge(listed, base)
        assertEquals(listOf("guess", "sure", "embed"), options.map { it.id })
        assertEquals("sure", ModelOptions.defaultMain(options))
        assertEquals("guess", ModelOptions.defaultMain(ModelOptions.merge(listOf(ListedModel("guess")), base)))
        assertEquals(null, ModelOptions.defaultMain(ModelOptions.merge(listOf(ListedModel("embed", toolCalls = false)), base)))
    }

    @Test fun modelsOnlyReachableByUnsupportedProtocolsAreNotOffered() {
        val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://gw.test/openai/v1")
        val listed = listOf(
            ListedModel("realtime-only", toolCalls = true, unsupportedApis = listOf("realtime")),
            ListedModel("chat", toolCalls = true, endpoint = chat),
            ListedModel("responses", toolCalls = true, endpoint = ModelEndpoint(ModelProtocol.Responses, "https://gw.test/openai/v1")),
            ListedModel("plain"),
        )
        val options = ModelOptions.merge(listed, base)
        assertEquals(listOf("chat", "responses", "plain"), options.map { it.id })
        assertEquals("chat", ModelOptions.defaultMain(options))
    }

    @Test fun openCodeGoPresetListsFromItsCatalogAddress() {
        val p = ProviderPresets.all.single { it.label == "OpenCode Go" }
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals("https://opencode.ai/zen/go/v1", p.baseUrl)
        assertEquals(p, ProviderPresets.forBaseUrl("https://opencode.ai/zen/go/v1/"))
    }

    @Test fun contextLabels() {
        assertEquals("64K", ModelOptions.contextLabel(64_000))
        assertEquals("128K", ModelOptions.contextLabel(131_072))
        assertEquals("200K", ModelOptions.contextLabel(200_000))
        assertEquals("1M", ModelOptions.contextLabel(1_048_576))
        assertEquals("2M", ModelOptions.contextLabel(2_000_000))
    }

    @Test fun sameProviderKeyIgnoresTrailingSlash() {
        assertEquals(
            ProviderConnection.keyOf(ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "DeepSeek"),
            ProviderConnection.keyOf(ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com/", "DeepSeek "),
        )
        val local = ProviderConfig("3", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "local", base)
        assertEquals("192.168.1.5", ProviderConnection.displayName(local))
    }

    @Test fun providerOptionsAreRememberedListPlusSavedConfigs() {
        val p = Provider("p", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", listOf(ListedModel("a"), ListedModel("b")))
        val savedCaps = base.copy(vision = true)
        val configs = listOf(
            ProviderConfig("1", p.kind, p.baseUrl, "b", savedCaps, p.name, "p"),
            ProviderConfig("2", p.kind, p.baseUrl, "typed", base, p.name, "p"),
            ProviderConfig("3", p.kind, p.baseUrl, "other", base, p.name, "q"),
        )
        val options = ModelOptions.ofProvider(p, configs)
        assertEquals(listOf("a", "b", "typed"), options.map { it.id })
        assertTrue("已有配置的用配置里的能力", options.first { it.id == "b" }.capabilities.vision)
        assertEquals(emptyList<String>(), ModelOptions.ofProvider(p.copy(models = null), emptyList()).map { it.id })
    }
}
