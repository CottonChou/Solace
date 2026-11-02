package com.zizephus.solace.harness.core.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale
import java.util.TimeZone

sealed interface SolaceHarnessEngineState {
    data object Stopped : SolaceHarnessEngineState
    data object Starting : SolaceHarnessEngineState
    data object Ready : SolaceHarnessEngineState
    data class Failed(val message: String, val log: String? = null) : SolaceHarnessEngineState
    data class SafeMode(val reason: String) : SolaceHarnessEngineState
}

data class SolaceHarnessEngineRoute(
    val provider: String,
    val model: String,
    val effort: String? = null,
)

/**
 * Everything a host supplies for one engine process. [profilePatch] is DSH's YAML override
 * layer; third-party providers are configured through it.
 */
data class SolaceHarnessLaunchConfig(
    val route: SolaceHarnessEngineRoute,
    val deepSeekApiKey: String? = null,
    val profilePatch: String = "[]",
    val environment: Map<String, String> = emptyMap(),
    val totalRamMb: Long? = null,
    val isLowRam: Boolean = false,
    val thermalLite: Boolean = false,
)

data class SolaceHarnessEngineCallbacks(
    val onSdkRequest: suspend (method: String, params: JsonObject) -> JsonElement,
    val onSdkNotification: suspend (method: String, params: JsonObject, generation: Int) -> Unit = { _, _, _ -> },
    val onCapabilityRequest: suspend (method: String, params: JsonObject) -> JsonElement = { method, _ ->
        when (method) {
            "capability/list" -> buildJsonObject { put("tools", buildJsonArray { }) }
            "capability/invoke" -> throw JsonRpcException(-32601, "device capability is not connected")
            "capability/cancel" -> buildJsonObject { put("ok", true) }
            else -> throw JsonRpcException(-32601, "unsupported $method")
        }
    },
    val onCapabilityNotification: suspend (method: String, params: JsonObject) -> Unit = { _, _ -> },
    val onClosed: () -> Unit = {},
)

data class SolaceHarnessEngineConnection(
    val generation: Int,
    val sdk: JsonRpcPeer,
    val bridge: JsonRpcPeer,
)

class SolaceHarnessEngineStartException(message: String, val log: String?, cause: Throwable) : Exception(message, cause)

/**
 * Owns the embedded Node engine in its dedicated process. This deliberately contains no Compose
 * or conversation state: Solace binds it as a transport and keeps its own task and conversation UI.
 */
class SolaceHarnessEngineGateway(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SolaceHarnessEngineState>(SolaceHarnessEngineState.Stopped)
    val state: StateFlow<SolaceHarnessEngineState> = _state.asStateFlow()

    private var callbacks = SolaceHarnessEngineCallbacks(
        onSdkRequest = { method, _ -> throw JsonRpcException(-32601, "engine is not connected: $method") },
    )
    private var connection: ServiceConnection? = null
    private var death: CompletableDeferred<Unit>? = null
    private var active: SolaceHarnessEngineConnection? = null
    private var generation = 0
    @Volatile private var expectedExit = -1
    private val crashTracker = EngineCrashTracker()
    private var readyAtMs = -1L

    suspend fun connect(config: SolaceHarnessLaunchConfig, callbacks: SolaceHarnessEngineCallbacks): SolaceHarnessEngineConnection = mutex.withLock {
        this.callbacks = callbacks
        active?.takeIf { _state.value == SolaceHarnessEngineState.Ready }?.let { return it }
        _state.value = SolaceHarnessEngineState.Starting
        val gen = generation + 1
        var processStarted = false
        val startedAtMs = System.currentTimeMillis()
        fun phase(name: String) = logPhase(name, startedAtMs)
        try {
            phase("connect")
            var service = bind(gen)
            phase("bind")
            if (runCatching { service.isRunning() }.getOrDefault(false)) {
                val dying = death
                connection?.let { runCatching { context.unbindService(it) } }
                connection = null
                killEngineProcess()
                withTimeoutOrNull(5_000) { dying?.await() }
                service = bind(gen)
            }
            writeProfilePatch(config.profilePatch)
            phase("profile")
            val handles = service.start(environment(config))
            phase("start")
            val sdkIn = ParcelFileDescriptor.AutoCloseOutputStream(handles.pfd("sdk_in"))
            val sdkOut = ParcelFileDescriptor.AutoCloseInputStream(handles.pfd("sdk_out"))
            val bridgeFd = handles.pfd("bridge")
            val sdk = JsonRpcPeer(
                "sdk",
                sdkOut,
                sdkIn,
                scope,
                onRequest = this.callbacks.onSdkRequest,
                onNotification = { method, params -> this.callbacks.onSdkNotification(method, params, gen) },
                onClosed = { onEngineGone(gen) },
            )
            val bridge = JsonRpcPeer(
                "bridge",
                ParcelFileDescriptor.AutoCloseInputStream(bridgeFd),
                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(bridgeFd.fileDescriptor)),
                scope,
                onRequest = { method, params -> this.callbacks.onCapabilityRequest(method, params) },
                onNotification = { method, params -> this.callbacks.onCapabilityNotification(method, params) },
                onClosed = {},
            )
            sdk.start()
            bridge.start()
            processStarted = true
            phase("handshake")
            withTimeout(90_000) {
                sdk.request(
                    "initialize",
                    buildJsonObject {
                        put("cwd", File(context.filesDir, "home").path)
                        put("provider", config.route.provider)
                        put("model", config.route.model)
                        config.route.effort?.takeIf { it.isNotBlank() }?.let { put("reasoningEffort", it) }
                        put("approvalRequests", true)
                        putJsonObject("capabilities") { put("userQuestions", true) }
                    },
                )
            }
            phase("ready")
            val ready = SolaceHarnessEngineConnection(gen, sdk, bridge)
            active = ready
            generation = gen
            readyAtMs = System.currentTimeMillis()
            crashTracker.recordSuccess(config)
            _state.value = SolaceHarnessEngineState.Ready
            ready
        } catch (e: Exception) {
            val log = EngineDiagnostics.logTail(context, 4_000)
            crashTracker.record(
                crashTracker.classifyStartup(processStarted),
                e.message ?: e.javaClass.simpleName,
            )
            teardown()
            killEngineProcess()
            _state.value = crashTracker.safeMode?.let { SolaceHarnessEngineState.SafeMode(it.reason) }
                ?: SolaceHarnessEngineState.Failed(e.message ?: e.javaClass.simpleName, log)
            throw SolaceHarnessEngineStartException(e.message ?: e.javaClass.simpleName, log, e)
        }
    }

    /** The SDK has no per-session cancel, so this ends every active DSH root task. */
    suspend fun stop() = mutex.withLock {
        val dying = death
        expectedExit = generation
        runCatching { withTimeout(3_000) { active?.sdk?.request("shutdown") } }
        teardown()
        if (dying != null && withTimeoutOrNull(5_000) { dying.await() } == null) killEngineProcess()
        _state.value = SolaceHarnessEngineState.Stopped
    }

    suspend fun restart(config: SolaceHarnessLaunchConfig, callbacks: SolaceHarnessEngineCallbacks): SolaceHarnessEngineConnection {
        stop()
        return connect(config, callbacks)
    }

    private fun onEngineGone(gen: Int) {
        if (gen != generation || gen == expectedExit) return
        teardown()
        val readyUptimeMs = readyAtMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it }
        crashTracker.record(crashTracker.classifyExit(readyUptimeMs), "engine process exited")
        _state.value = crashTracker.safeMode?.let { SolaceHarnessEngineState.SafeMode(it.reason) }
            ?: SolaceHarnessEngineState.Failed("DeepSeek Harness engine process exited")
        callbacks.onClosed()
    }

    private fun teardown() {
        val current = active
        val cause = EngineCallCancelled("DSH engine generation ended")
        current?.sdk?.cancelRequests(cause)
        current?.bridge?.cancelRequests(cause)
        active = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        context.stopService(Intent(context, EngineService::class.java))
    }

    private suspend fun bind(gen: Int): IEngineService {
        val deferred = CompletableDeferred<IEngineService>()
        val died = CompletableDeferred<Unit>().also { death = it }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                runCatching { binder.linkToDeath({ died.complete(Unit); onEngineGone(gen) }, 0) }
                    .onFailure {
                        died.complete(Unit)
                        deferred.completeExceptionally(it)
                        return
                    }
                deferred.complete(IEngineService.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                died.complete(Unit)
                onEngineGone(gen)
            }
        }
        val intent = Intent(context, EngineService::class.java)
        context.startForegroundService(intent)
        check(context.bindService(intent, conn, Context.BIND_IMPORTANT)) { "could not bind DSH engine service" }
        connection = conn
        return withTimeout(20_000) { deferred.await() }
    }

    private fun environment(config: SolaceHarnessLaunchConfig): Bundle = Bundle().apply {
        val files = context.filesDir
        val tz = TimeZone.getDefault().id
        putString("DSH_HOME", File(files, "harness-home").path)
        putString("HOME", File(files, "home").path)
        putString("TMPDIR", File(context.cacheDir, "harness-tmp").path)
        putString("SHELL", "/system/bin/sh")
        putString("PATH", "${File(files, "bin").path}:/system/bin:/system/xbin")
        putString("DSH_NATIVE_DIR", context.applicationInfo.nativeLibraryDir)
        putString("DSH_ANDROID_PACKAGE", context.packageName)
        putString("DSH_ANDROID_LOCALE", Locale.getDefault().toLanguageTag())
        putString("TZ", tz)
        putString("DSH_ANDROID_TZ", tz)
        putString("DSH_TELEMETRY_DISABLED", "1")
        putString("NARB_DISABLE_NATIVE_CACHE", "1")
        val budget = EngineResourceBudget.fromDeviceProfile(
            totalRamMb = config.totalRamMb ?: 8192L,
            isLowRam = config.isLowRam,
            thermalLite = config.thermalLite,
        )
        // Budget first, host overrides after: NODE_OPTIONS merges so budget flags lose to
        // explicit host options, everything else the host set wins outright.
        budget.toEnv().forEach { (key, value) ->
            if (key == "NODE_OPTIONS") {
                mergeBudgetNodeOptions(value, config.environment["NODE_OPTIONS"])?.let { putString(key, it) }
            } else {
                putString(key, value)
            }
        }
        config.deepSeekApiKey?.takeIf { it.isNotBlank() }?.let { putString("DEEPSEEK_API_KEY", it) }
        config.environment.forEach { (key, value) -> putString(key, value) }
    }

    /** Budget flags first, host NODE_OPTIONS second — later --flag wins, so host overrides budget. */
    private fun mergeBudgetNodeOptions(budget: String, host: String?): String? {
        val merged = EngineRuntimeEnv.mergeNodeOptions(budget, host.orEmpty().split(' ').filter { it.isNotBlank() })
        return merged.ifBlank { null }
    }

    private fun logPhase(phase: String, startedAtMs: Long) {
        Log.i(TAG, "harness-engine-start: phase=$phase elapsed=${System.currentTimeMillis() - startedAtMs}")
    }

    private fun writeProfilePatch(text: String) {
        val dir = File(context.filesDir, "dsh-home/profiles/android").apply { mkdirs() }
        val target = File(dir, "cordis.patch.yml")
        if (target.exists() && target.readText() == text) return
        val temp = File(dir, "cordis.patch.yml.tmp")
        temp.writeText(text)
        check(temp.renameTo(target)) { "could not write ${target.path}" }
    }

    private fun killEngineProcess() {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        am.runningAppProcesses
            ?.filter { it.processName == context.packageName + ":engine" }
            ?.forEach { android.os.Process.killProcess(it.pid) }
    }

    private fun Bundle.pfd(key: String): ParcelFileDescriptor =
        @Suppress("DEPRECATION")
        (getParcelable<ParcelFileDescriptor>(key) ?: error("missing $key"))

    private companion object {
        const val TAG = "SolaceHarnessEngineGateway"
    }
}
