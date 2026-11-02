package app.solace.mobile.harness.core.engine

/**
 * Node memory/thread budget derived from device RAM. Pure JVM so it stays unit-testable; the host
 * measures [totalRamMb] and [isLowRam] via ActivityManager and passes them in.
 */
data class ResourceBudget(
    val maxOldSpaceMb: Int,
    val uvThreadpoolSize: Int,
    val liteMode: Boolean,
) {
    /** Env deltas for the engine process; NODE_OPTIONS is merged by [EngineRuntimeEnv], never overwritten. */
    fun toEnv(): Map<String, String> = mapOf(
        "NODE_OPTIONS" to "--max-old-space-size=$maxOldSpaceMb",
        "UV_THREADPOOL_SIZE" to uvThreadpoolSize.toString(),
    )
}

object EngineResourceBudget {
    fun fromDeviceProfile(totalRamMb: Long, isLowRam: Boolean, thermalLite: Boolean): ResourceBudget {
        var (oldSpace, uvThreads) = when {
            isLowRam || totalRamMb < 4096 -> 512 to 2
            totalRamMb < 6144 -> 1024 to 4
            totalRamMb < 8192 -> 1536 to 6
            else -> 2048 to 8
        }
        if (thermalLite) {
            oldSpace = (oldSpace / 2).coerceAtLeast(256)
            uvThreads = uvThreads.coerceAtLeast(2)
        }
        return ResourceBudget(oldSpace, uvThreads, thermalLite)
    }
}

/**
 * Thermal lite switch with a 30 s hold: once [enterLite] fires, [isLite] stays true for at least
 * the cooldown even if the thermal level recovers, preventing start/stop thrash of the engine
 * budget. Driven by an injected clock so it is testable without real time.
 */
class ThermalLiteController(
    private val cooldownMs: Long = COOLDOWN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var liteSinceMs: Long? = null

    val isLite: Boolean get() = liteSinceMs != null

    /** thermal level >= 1 (light or severe) enters lite; level 0 may exit after the cooldown. */
    fun update(level: Int) {
        when {
            level >= LITE_LEVEL -> liteSinceMs = clock()
            isLite && clock() - (liteSinceMs ?: 0L) >= cooldownMs -> liteSinceMs = null
        }
    }

    fun enterLite() = update(LITE_LEVEL)

    companion object {
        const val LITE_LEVEL = 1
        const val COOLDOWN_MS = 30_000L
    }
}
