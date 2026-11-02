package com.zizephus.solace.settings

import com.zizephus.solace.core.model.Capabilities
import com.zizephus.solace.core.model.PromptCaching

/**
 * 添加模型时的常用服务商模板:只预填接口类型、Base URL 和能力的初始勾选,
 * 模型名和 Key 由用户填,能力也可以改。
 * [sessionHeader] 是服务商要求放对话会话号的请求头名(见 [com.zizephus.solace.core.model.ModelSession]);不要的为 null。
 */
data class ProviderPreset(
    val label: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val capabilities: Capabilities,
    val sessionHeader: String? = null,
)

object ProviderPresets {
    private fun caps(vision: Boolean = false, context: Int = 64_000, caching: PromptCaching = PromptCaching.Implicit) =
        Capabilities(toolCalls = true, vision = vision, contextTokens = context, reasoning = false, promptCaching = caching)

    /** Base URL 对得上的模板(添加时选了别的模板、或测试里直接给地址时,按地址补上能力的初始值);没有为 null。 */
    fun forBaseUrl(url: String): ProviderPreset? = all.firstOrNull { it.baseUrl.isNotEmpty() && url.trimEnd('/') == it.baseUrl.trimEnd('/') }

    val all: List<ProviderPreset> = listOf(
        ProviderPreset("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", caps()),
        // 这家的 OpenAI 兼容接口上,不少模型(如 qwen3.5 系列)只有显式缓存:不标就从不复用
        ProviderPreset("通义千问", ProviderConfig.Kind.OpenAiCompat, "https://dashscope.aliyuncs.com/compatible-mode/v1", caps(caching = PromptCaching.Explicit)),
        ProviderPreset("Kimi", ProviderConfig.Kind.OpenAiCompat, "https://api.moonshot.cn/v1", caps()),
        ProviderPreset("智谱", ProviderConfig.Kind.OpenAiCompat, "https://open.bigmodel.cn/api/paas/v4", caps()),
        ProviderPreset("OpenRouter", ProviderConfig.Kind.OpenAiCompat, "https://openrouter.ai/api/v1", caps()),
        // 订阅制,一个 Key 用多家模型;各模型走哪种协议、哪个地址由模型列表逐个写明,这里的接口类型只是没写明时的退路。
        // 每个请求都要带对话的会话号,不带就拒绝(HTTP 400)
        ProviderPreset("OpenCode Go", ProviderConfig.Kind.OpenAiCompat, "https://opencode.ai/zen/go/v1", caps(), sessionHeader = "x-opencode-session"),
        ProviderPreset("Anthropic", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", caps(vision = true, context = 200_000)),
        ProviderPreset("Gemini", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com", caps(vision = true, context = 128_000)),
        ProviderPreset("自定义", ProviderConfig.Kind.OpenAiCompat, "", caps()),
    )

    val custom: ProviderPreset get() = all.last()
}
