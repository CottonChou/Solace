package com.wangzan101.solace.backend

/**
 * The agent runtime fixed to a conversation when its first turn starts.
 *
 * Solace owns phone operation and the interactive Android flow. DSH handles skills, MCP and general
 * agent work through its embedded Node engine. Keeping the choice on the conversation prevents the
 * two incompatible history formats from being mixed under one root task.
 */
enum class ExecutionBackend(val storageName: String) {
    Solace("solace"),
    DeepSeekHarness("dsh");

    companion object {
        fun fromStorage(value: String?): ExecutionBackend =
            entries.firstOrNull { it.storageName == value?.trim()?.lowercase() } ?: Solace
    }
}
