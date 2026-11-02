package com.zizephus.solace.ui

import com.zizephus.solace.R
import com.zizephus.solace.i18n.tr
import com.zizephus.solace.core.model.ListFailure
import com.zizephus.solace.core.model.ListedModel
import com.zizephus.solace.core.model.ModelListResult
import com.zizephus.solace.core.model.ModelRole
import com.zizephus.solace.settings.LocalHosts
import com.zizephus.solace.settings.ModelOption
import com.zizephus.solace.settings.ModelOptions
import com.zizephus.solace.settings.Provider
import com.zizephus.solace.settings.ProviderConfig
import com.zizephus.solace.settings.ProviderConnection
import com.zizephus.solace.settings.ProviderPreset
import com.zizephus.solace.settings.ProviderPresets
import com.zizephus.solace.settings.ProviderStore
import com.zizephus.solace.settings.RoleChoice
import com.zizephus.solace.settings.RoleChoices
import com.zizephus.solace.core.chatgpt.ChatGptAuth
import com.zizephus.solace.core.chatgpt.SignInException
import com.zizephus.solace.core.chatgpt.SignInFailure
import com.zizephus.solace.core.chatgpt.signInWithChatGpt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 列模型(「测试并保存」或刷新一个服务商)的进度。 */
sealed interface ListState {
    data object Idle : ListState
    data object Running : ListState
    data class Failed(val message: String) : ListState
}

/** 用 ChatGPT 登录的账号:[email] 来自核对过的 ID 令牌(可能为空);退出登录后 [signedIn] 为假。 */
data class ChatGptAccount(val email: String, val signedIn: Boolean)

/**
 * 「用 ChatGPT 登录」要用到的系统能力:在浏览器里打开授权页、登录完回到 Solace、回调后浏览器里显示的网页。
 * 界面给出(要 Context);测试里不给,就不能登录。
 */
interface ChatGptBrowser {
    fun open(url: String)
    fun returnToApp()
    /** [params] 里有 `error` 表示用户在授权页取消了。 */
    fun page(params: Map<String, String>): String
}

/** 「添加服务商」表单。 */
data class AddForm(
    val preset: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val apiKey: String,
    val test: ListState = ListState.Idle,
) {
    val isCustom: Boolean get() = preset == ProviderPresets.custom.label

    val urlProblem: String?
        get() = when {
            baseUrl.isBlank() -> tr(R.string.model_need_url)
            !baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://") -> tr(R.string.model_url_scheme)
            else -> null
        }

    /** 明文 http 又不在本机、局域网的地址:提醒一句,不拦着(见 [LocalHosts])。 */
    val unencryptedWarning: String?
        get() = if (urlProblem == null && LocalHosts.unencryptedRemote(baseUrl)) tr(R.string.model_url_unencrypted) else null

    /** 自定义的 OpenAI 兼容接口(本地服务)可以不填 Key,其余都要。 */
    val keyRequired: Boolean get() = !(isCustom && kind == ProviderConfig.Kind.OpenAiCompat)

    val canTest: Boolean get() = urlProblem == null && (!keyRequired || apiKey.isNotBlank()) && test != ListState.Running

    /** 存成服务商时用的名字:预设用预设名,自定义用地址的主机名。 */
    val providerName: String get() = if (isCustom) ProviderConnection.nameForUrl(baseUrl) else preset

    /** 这家要放会话号的请求头名:选的预设声明了的用它;自定义的地址对得上某个预设也用那个预设的。 */
    val sessionHeader: String? get() = presetFor(preset).sessionHeader ?: ProviderPresets.forBaseUrl(baseUrl.trim())?.sessionHeader

    companion object {
        fun of(p: ProviderPreset) = AddForm(p.label, p.kind, p.baseUrl, "")
        fun presetFor(label: String): ProviderPreset = ProviderPresets.all.firstOrNull { it.label == label } ?: ProviderPresets.custom
    }
}

/**
 * 配置模型(上手时)和设置 › 模型共用的全部状态:已连接的服务商、添加服务商表单、三个用途。
 * [providers]、[configs]、[roles] 是 [ProviderStore] 的快照,[keyed] 是存了 Key 的服务商 id;
 * [listing] 是各服务商刷新列表的进度(按服务商 id)。
 */
data class ModelSetupUi(
    val form: AddForm,
    val formOpen: Boolean = false,
    val providers: List<Provider> = emptyList(),
    val configs: List<ProviderConfig> = emptyList(),
    val roles: RoleChoices = RoleChoices(),
    val keyed: Set<String> = emptySet(),
    val listing: Map<String, ListState> = emptyMap(),
    /** ChatGPT 服务商的账号;没有这家时为 null。 */
    val chatGptAccount: ChatGptAccount? = null,
    /** 用 ChatGPT 登录的进度。 */
    val chatGptSignIn: ListState = ListState.Idle,
    /** 第一次登录成功后说明一次「在用你的 ChatGPT 套餐」,点「知道了」收起。 */
    val chatGptWelcome: Boolean = false,
    /** 退出登录时 ChatGPT 那边没确认注销(本机令牌已清)。 */
    val chatGptRevokeUnconfirmed: Boolean = false,
) {
    /** 还没有服务商时表单总是展开。 */
    val showForm: Boolean get() = formOpen || providers.isEmpty()

    /** 选好了主模型就能「下一步」。 */
    val canNext: Boolean get() = roles.main != null

    /** 这个用途实际用的配置(「用主模型」时是主模型的)。 */
    fun configFor(role: ModelRole): ProviderConfig? = roles.resolve(role)?.let { id -> configs.firstOrNull { it.id == id } }

    fun providerOf(cfg: ProviderConfig): Provider? = providers.firstOrNull { it.id == cfg.providerId }

    /** 一个服务商可选的全部模型(记住的列表加已有配置)。 */
    fun options(p: Provider): List<ModelOption> = ModelOptions.ofProvider(p, configs)

    /** 一个服务商的全部模型,在用的排前面,其余保持原来的顺序。 */
    fun sortedOptions(p: Provider): List<ModelOption> = options(p).sortedBy { if (rolesOf(p.id, it.id).isEmpty()) 1 else 0 }

    /** 实际在用这个模型的用途(含「用主模型」跟过来的),按主模型、看图、轻量的顺序。 */
    fun rolesOf(providerId: String, model: String): List<ModelRole> =
        ModelRole.entries.filter { role -> configFor(role)?.let { it.providerId == providerId && it.model == model } == true }

    /** 实际在用这个服务商的用途。 */
    fun rolesOf(p: Provider): List<ModelRole> = ModelRole.entries.filter { configFor(it)?.providerId == p.id }

    /** 这个用途明确选的是不是这个模型(「用主模型」不算)。 */
    fun picked(role: ModelRole, providerId: String, model: String): Boolean {
        val id = (roles[role] as? RoleChoice.Model)?.configId ?: return false
        return configs.firstOrNull { it.id == id }?.let { it.providerId == providerId && it.model == model } == true
    }
}

/**
 * 配置模型的状态与动作。添加服务商:选预设 → 填 Key →「测试并保存」(列模型,再让会当主模型的那个回一句话,都成了才存下这家和列表,不成说原因、不存);
 * 改了服务商、地址或 Key,进行中的测试作废。用途:从所有已连接服务商记住的模型里选,选中即生效;
 * 看图还可以「用主模型」「不用」,轻量可以「用主模型」。连上第一家(还没有主模型)时自动选它的一个模型为主模型。
 * 首次上手和设置 › 模型共用。
 */
class ModelSetupViewModel(
    private val store: ProviderStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val chatGptBrowser: ChatGptBrowser? = null,
) {
    private val _state = MutableStateFlow(snapshot(ModelSetupUi(AddForm.of(ProviderPresets.all.first()))))
    val state: StateFlow<ModelSetupUi> = _state.asStateFlow()

    private var testJob: Job? = null
    private var signInJob: Job? = null
    private val refreshJobs = mutableMapOf<String, Job>()

    init {
        // 别处(开发者选项等)改了存储时跟着更新
        scope.launch { merge(store.providers, store.configs, store.roles).collect { sync() } }
    }

    private fun snapshot(ui: ModelSetupUi): ModelSetupUi {
        val providers = store.providers.value
        return ui.copy(
            providers = providers, configs = store.configs.value, roles = store.roles.value,
            keyed = providers.filter { store.hasKey(it.id) }.map { it.id }.toSet(),
            chatGptAccount = store.chatGptProvider()?.let { p ->
                store.chatGptSession(p.id).let { ChatGptAccount(it?.email.orEmpty(), it?.signedIn == true) }
            },
        )
    }

    private fun sync() = _state.update(::snapshot)

    // ---------- 添加服务商 ----------

    fun openForm() = _state.update { it.copy(formOpen = true) }

    fun closeForm() {
        testJob?.cancel()
        _state.update { it.copy(formOpen = false, form = AddForm.of(ProviderPresets.all.first())) }
    }

    fun selectPreset(label: String) {
        val p = AddForm.presetFor(label)
        if (p.label == _state.value.form.preset) return
        val key = _state.value.form.apiKey
        edit { AddForm.of(p).copy(apiKey = key) }
    }

    fun setKind(kind: ProviderConfig.Kind) = edit { it.copy(kind = kind) }
    fun setBaseUrl(url: String) = edit { it.copy(baseUrl = url) }
    fun setApiKey(key: String) = edit { it.copy(apiKey = key) }

    /** 连接信息变了:进行中的测试和上次的失败原因作废。 */
    private fun edit(change: (AddForm) -> AddForm) {
        testJob?.cancel()
        _state.update { it.copy(form = change(it.form).copy(test = ListState.Idle)) }
    }

    /**
     * 测试并保存:列得出模型(或服务不提供列表),再让一个模型回一句话(见 [probeProblem])也成了,就存下这家服务商,表单清空收起;
     * 哪一步不成就说原因、不存。
     */
    fun testAndSave() {
        val f = _state.value.form
        if (!f.canTest) return
        testJob?.cancel()
        _state.update { it.copy(form = it.form.copy(test = ListState.Running)) }
        testJob = scope.launch {
            val key = f.apiKey.trim()
            val baseUrl = f.baseUrl.trim()
            val models = when (val r = withContext(io) { store.listModels(f.kind, baseUrl, key.ifEmpty { null }, f.sessionHeader) }) {
                is ModelListResult.Ok -> r.models
                is ModelListResult.Failed -> {
                    if (r.reason != ListFailure.NotSupported) {
                        _state.update { it.copy(form = it.form.copy(test = ListState.Failed(r.message))) }
                        return@launch
                    }
                    emptyList()
                }
            }
            probeProblem(f, baseUrl, key.ifEmpty { null }, models)?.let { problem ->
                _state.update { it.copy(form = it.form.copy(test = ListState.Failed(problem))) }
                return@launch
            }
            withContext(io) {
                val p = store.connect(f.providerName, f.kind, baseUrl, key, models, f.sessionHeader)
                if (store.roles.value.main == null) {
                    val options = ModelOptions.ofProvider(p, store.list())
                    (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                }
            }
            _state.update { snapshot(it.copy(form = AddForm.of(ProviderPresets.all.first()), formOpen = false)) }
        }
    }

    /**
     * 列出模型后,让会被选作主模型的那个(没有就第一个)回一句最短的话,和真实任务走同一条建模型的路(请求头、会话号都一样):
     * Key 不对、少了服务商要的请求头,这里就露出来,不等到第一句话。成功、或没有可试的模型(服务不提供列表)为 null,
     * 失败是给人看的原因(服务端的报错原样带上),超过 [PROBE_TIMEOUT_MS] 没回也算失败。
     */
    private suspend fun probeProblem(f: AddForm, baseUrl: String, key: String?, models: List<ListedModel>): String? {
        // 用 ChatGPT 登录不走这张表单(登录本身就核对过了);表单只给 Key 的接口类型,这里再挡一次
        if (f.kind == ProviderConfig.Kind.ChatGpt) return null
        val base = (ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.custom).capabilities
        val options = ModelOptions.merge(models, base)
        val model = ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id ?: return null
        val caps = options.first { it.id == model }.capabilities
        val endpoint = models.firstOrNull { it.id == model }?.endpoint
        val error = try {
            withContext(io) { withTimeout(PROBE_TIMEOUT_MS) { store.probe(f.kind, baseUrl, key, f.sessionHeader, model, endpoint, caps) } }
        } catch (e: TimeoutCancellationException) {
            return tr(R.string.model_probe_timeout, model, (PROBE_TIMEOUT_MS / 1000).toInt())
        }
        return error?.let { tr(R.string.model_probe_failed, model, it.message.orEmpty()) }
    }

    // ---------- 用 ChatGPT 登录 ----------

    /**
     * 打开浏览器登录 ChatGPT,回调后换令牌、核对,列出模型,存成(或更新)ChatGPT 服务商,回到 Solace。
     * 还没有主模型时选它的一个模型为主模型。第一次注册成功后说明一次在用套餐额度。失败说原因。
     */
    fun signInWithChatGpt() {
        val browser = chatGptBrowser ?: return
        if (_state.value.chatGptSignIn == ListState.Running) return
        _state.update { it.copy(chatGptSignIn = ListState.Running, chatGptRevokeUnconfirmed = false) }
        signInJob = scope.launch {
            val (listProblem, first) = try {
                withContext(io) {
                    val (hostId, clientId) = store.chatGptRegistration()
                    val existing = store.chatGptProvider()
                    val session = signInWithChatGpt(
                        store.chatGptAuth, hostId, clientId, existing?.let { store.chatGptSession(it.id) },
                        openBrowser = browser::open, onRegistered = store::rememberChatGptClient, page = browser::page,
                    )
                    val listed = store.listModels(ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.accessToken)
                    val p = store.connectChatGpt(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty())
                    if (store.roles.value.main == null) {
                        val options = ModelOptions.ofProvider(p, store.list())
                        (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                    }
                    (listed as? ModelListResult.Failed)?.message to (clientId == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = (e as? SignInException)?.reason ?: SignInFailure.Network
                browser.returnToApp()
                _state.update { it.copy(chatGptSignIn = ListState.Failed(ModelsCopy.chatGptFailure(reason))) }
                return@launch
            }
            browser.returnToApp()
            val id = store.chatGptProvider()?.id
            _state.update {
                snapshot(it.copy(
                    chatGptSignIn = ListState.Idle, chatGptWelcome = first, formOpen = false,
                    listing = if (listProblem != null && id != null) it.listing + (id to ListState.Failed(listProblem)) else it.listing - id.orEmpty(),
                ))
            }
        }
    }

    /** 放弃正在进行的登录(本机回调随之关掉)。 */
    fun cancelChatGptSignIn() {
        signInJob?.cancel()
        _state.update { it.copy(chatGptSignIn = ListState.Idle) }
    }

    fun dismissChatGptWelcome() = _state.update { it.copy(chatGptWelcome = false) }

    /** 退出 ChatGPT 登录:服务端注销、本机清令牌;服务商和用途不动,再登录就能接着用。 */
    fun signOutChatGpt(providerId: String) {
        scope.launch {
            val revoked = withContext(io) { store.signOutChatGpt(providerId) }
            _state.update { snapshot(it.copy(chatGptRevokeUnconfirmed = !revoked, listing = it.listing - providerId)) }
        }
    }

    // ---------- 用途 ----------

    /** 把 [providerId] 下的 [model] 用作 [role](选择页和「用作」面板都走这里):没有配置的当场建一条。 */
    fun pick(role: ModelRole, providerId: String, model: String) {
        val m = model.trim()
        if (m.isEmpty()) return
        scope.launch {
            withContext(io) {
                val p = store.provider(providerId) ?: return@withContext
                use(role, p, m, ModelOptions.ofProvider(p, store.list()))
            }
            sync()
        }
    }

    /** 看图、轻量的固定选项:「用主模型」或(只有看图)「不用」。 */
    fun choose(role: ModelRole, choice: RoleChoice) {
        scope.launch {
            withContext(io) { store.choose(role, choice) }
            sync()
        }
    }

    private fun use(role: ModelRole, p: Provider, model: String, options: List<ModelOption>) {
        val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(p)
        store.use(role, p.id, model, caps)
    }

    // ---------- 一个服务商 ----------

    /** 重新列一次这个服务商的模型并记下;失败时记下原因。 */
    fun refresh(providerId: String) {
        val p = store.provider(providerId) ?: return
        if (refreshJobs[providerId]?.isActive == true) return
        _state.update { it.copy(listing = it.listing + (providerId to ListState.Running)) }
        refreshJobs[providerId] = scope.launch {
            val r = withContext(io) { store.listModels(p) }
            val models: List<ListedModel>? = when (r) {
                is ModelListResult.Ok -> r.models
                is ModelListResult.Failed -> if (r.reason == ListFailure.NotSupported) emptyList() else null
            }
            if (models != null) withContext(io) { store.rememberModels(p.id, models) }
            val after = if (r is ModelListResult.Failed && models == null) ListState.Failed(r.message) else ListState.Idle
            _state.update { snapshot(it.copy(listing = it.listing + (providerId to after))) }
        }
    }

    /** 选择页、服务商页打开时:还没记住列表的服务商列一次。 */
    fun ensureListed() {
        _state.value.providers.filter { it.models == null && _state.value.listing[it.id] == null && listable(it) }.forEach { refresh(it.id) }
    }

    /** 退出了登录的 ChatGPT 列不了模型,等再登录。 */
    private fun listable(p: Provider) = p.kind != ProviderConfig.Kind.ChatGpt || store.chatGptSession(p.id)?.signedIn == true

    /** 换 Key,然后用新 Key 重新列一次模型。 */
    fun changeKey(providerId: String, apiKey: String) {
        val k = apiKey.trim()
        if (k.isEmpty()) return
        scope.launch {
            withContext(io) { store.updateProviderKey(providerId, k) }
            refreshJobs.remove(providerId)?.cancel()
            refresh(providerId)
        }
    }

    /** 删掉服务商和它的 Key;用着它的用途退回默认。 */
    fun removeProvider(providerId: String) {
        scope.launch {
            withContext(io) {
                // ChatGPT 的先在服务端注销登录,再删
                if (store.provider(providerId)?.kind == ProviderConfig.Kind.ChatGpt) store.signOutChatGpt(providerId)
                store.removeProvider(providerId)
            }
            _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
        }
    }

    companion object {
        /** 「测试并保存」里试调用最多等多久。 */
        const val PROBE_TIMEOUT_MS = 20_000L
    }
}
