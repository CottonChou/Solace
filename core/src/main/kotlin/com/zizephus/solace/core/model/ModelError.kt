package com.zizephus.solace.core.model

sealed class ModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth : ModelError("API Key 无效或没有权限")
    class QuotaExceeded : ModelError("额度已用完")
    class RateLimited(val retryAfterMs: Long?) : ModelError("请求太频繁,请稍后再试")
    class ContextTooLong : ModelError("对话内容超出模型上下文长度")
    class ModelNotFound : ModelError("找不到该模型")
    class Network(cause: Throwable? = null) : ModelError("网络连接失败", cause)
    class Server(val status: Int) : ModelError("模型服务出错(HTTP $status)")
    class ContentRefused : ModelError("模型拒绝处理该内容")
    /** ChatGPT 套餐给其他 App 用的额度到了上限:不重试,等额度恢复或换模型。 */
    class ChatGptLimitReached : ModelError("ChatGPT 额度用完了")
    /** 这个 ChatGPT 账号不能在其他 App 里用套餐额度(套餐不符、地区或账号策略不允许):不重试。 */
    class ChatGptNotEligible(val detail: String = "") :
        ModelError(if (detail.isEmpty()) "这个账号不能在其他 App 里用 ChatGPT 额度" else "这个账号不能在其他 App 里用 ChatGPT 额度: $detail")
    /** 登录已失效(换新令牌被拒,或用户在账号里断开了这个 App),要重新登录。 */
    class SignInExpired : ModelError("登录已失效,请重新登录")
    class BadResponse(detail: String = "", cause: Throwable? = null) :
        ModelError(if (detail.isEmpty()) "模型返回了无法解析的内容" else "模型返回了无法解析的内容: $detail", cause)
}
