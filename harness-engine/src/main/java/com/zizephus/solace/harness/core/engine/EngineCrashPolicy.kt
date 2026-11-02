package com.zizephus.solace.harness.core.engine

/**
 * Host-side crash policy for the embedded engine. Pure JVM logic: no Android calls, so it can be
 * unit-tested without Robolectric.
 */
enum class CrashKind {
    /** Binding or the launch handshake failed before the engine could run. */
    STARTUP_CHAIN,

    /** [SolaceHarnessEngineGateway.connect] timed out or failed after the process existed. */
    STARTUP,

    /** The process exited within the healthy-observation window after Ready. */
    EARLY_CRASH,

    /** The process ran past the observation window and then exited. */
    LATE_CRASH,

    /** The host stopped the engine itself; never counts as a crash. */
    EXTERNAL_KILL,
}

data class EngineSafeModeState(
    val reason: String,
    val enteredAtMs: Long,
)

data class LastKnownGoodConfig(
    val provider: String,
    val model: String,
    val routeEffort: String?,
    val recordedAtMs: Long,
)

data class CrashRecord(
    val kind: CrashKind,
    val reason: String,
    val atMs: Long,
)

class EngineCrashTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** Counted failures in a row before the host should enter SafeMode. */
        const val STRIKE_LIMIT = 2

        /** Exits within this window after Ready count as early crashes. */
        const val HEALTHY_OBSERVATION_MS = 120_000L

        /** The ready-to-early-crash boundary required by the spec. */
        const val EARLY_WINDOW_MS = 60_000L
    }

    private val strikes = ArrayDeque<CrashRecord>()
    private var recordedAtMs = -1L
    private var readyAtMs = -1L

    var safeMode: EngineSafeModeState? = null
        private set

    var lastKnownGood: LastKnownGoodConfig? = null
        private set

    /** Records a successful [SolaceHarnessEngineGateway.connect]; clears strikes and leaves SafeMode. */
    fun recordSuccess(config: SolaceHarnessLaunchConfig) {
        val now = clock()
        strikes.clear()
        safeMode = null
        recordedAtMs = now
        readyAtMs = now
        lastKnownGood = LastKnownGoodConfig(
            provider = config.route.provider,
            model = config.route.model,
            routeEffort = config.route.effort,
            recordedAtMs = now,
        )
    }

    fun lastKnownGood(): LastKnownGoodConfig? = lastKnownGood

    /**
     * Classifies a failure purely from when the last success happened; the gateway calls this
     * before recording anything. A startup failure with a prior success counts as STARTUP when
     * the process came up (or we cannot tell) and as STARTUP_CHAIN when binding failed first.
     */
    fun classifyStartup(readyKnown: Boolean): CrashKind =
        if (readyKnown) CrashKind.STARTUP else CrashKind.STARTUP_CHAIN

    /**
     * Classifies an unexpected exit by how long the engine had been Ready. Within the first
     * minute it is EARLY_CRASH, afterwards LATE_CRASH. Without a Ready time we cannot claim the
     * process was healthy, so treat it as a startup failure.
     */
    fun classifyExit(readyUptimeMs: Long?): CrashKind = when {
        readyUptimeMs == null -> CrashKind.STARTUP
        readyUptimeMs <= EARLY_WINDOW_MS -> CrashKind.EARLY_CRASH
        else -> CrashKind.LATE_CRASH
    }

    /**
     * Records a classified failure. Startup-chain, startup and early crashes count strikes;
     * late crashes are logged but do not. When [STRIKE_LIMIT] counted failures happen in a row,
     * SafeMode is entered and the tracker stays there until the next successful start.
     */
    fun record(kind: CrashKind, reason: String) {
        val now = clock()
        when (kind) {
            CrashKind.EXTERNAL_KILL -> strikes.clear()
            CrashKind.LATE_CRASH -> Unit
            else -> {
                strikes.addLast(CrashRecord(kind, reason, now))
                if (strikes.size > STRIKE_LIMIT) strikes.removeFirst()
            }
        }
        if (kind != CrashKind.EXTERNAL_KILL && strikes.size >= STRIKE_LIMIT && safeMode == null) {
            safeMode = EngineSafeModeState(reason = reason, enteredAtMs = now)
        }
    }

    fun strikes(): List<CrashRecord> = strikes.toList()
}
