package com.zizephus.solace.harness.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private fun testConfig(provider: String = "deepseek", model: String = "deepseek-chat") =
    SolaceHarnessLaunchConfig(route = SolaceHarnessEngineRoute(provider = provider, model = model))

class EngineCrashPolicyTest {
    private fun tracker(now: Long = 1_000L): EngineCrashTracker =
        EngineCrashTracker(clock = { now })

    @Test
    fun `two early crashes in a row enter safe mode`() {
        val t = tracker()
        t.record(CrashKind.EARLY_CRASH, "first")
        assertNull(t.safeMode)
        t.record(CrashKind.EARLY_CRASH, "second")
        assertEquals("second", t.safeMode?.reason)
    }

    @Test
    fun `two startup failures in a row enter safe mode`() {
        val t = tracker()
        t.record(CrashKind.STARTUP_CHAIN, "bind failed")
        t.record(CrashKind.STARTUP, "initialize timeout")
        assertNotNull(t.safeMode)
    }

    @Test
    fun `late crash is recorded but never counts a strike`() {
        val t = tracker()
        t.record(CrashKind.EARLY_CRASH, "first")
        t.record(CrashKind.LATE_CRASH, "plugin crashed")
        assertNull(t.safeMode)
        assertEquals(listOf(CrashKind.EARLY_CRASH), t.strikes().map { it.kind })
        t.record(CrashKind.LATE_CRASH, "again")
        t.record(CrashKind.LATE_CRASH, "and again")
        assertNull(t.safeMode)
    }

    @Test
    fun `external kill resets strikes and never enters safe mode`() {
        val t = tracker()
        t.record(CrashKind.EARLY_CRASH, "first")
        t.record(CrashKind.EXTERNAL_KILL, "stop()")
        assertNull(t.safeMode)
        assertEquals(0, t.strikes().size)
        t.record(CrashKind.EARLY_CRASH, "next run")
        assertNull(t.safeMode)
    }

    @Test
    fun `success clears strikes and safe mode`() {
        val t = tracker()
        t.record(CrashKind.EARLY_CRASH, "first")
        t.record(CrashKind.EARLY_CRASH, "second")
        t.recordSuccess(testConfig())
        assertNull(t.safeMode)
        assertEquals(0, t.strikes().size)
    }

    @Test
    fun `last known good captures route fields and time`() {
        val t = EngineCrashTracker(clock = { 42L })
        assertNull(t.lastKnownGood())
        t.recordSuccess(testConfig(provider = "openai", model = "gpt-x"))
        val lkg = t.lastKnownGood()
        assertNotNull(lkg)
        assertEquals("openai", lkg?.provider)
        assertEquals("gpt-x", lkg?.model)
        assertNull(lkg?.routeEffort)
        assertEquals(42L, lkg?.recordedAtMs)
    }

    @Test
    fun `exit within 60s of ready is early crash`() {
        val t = tracker()
        t.recordSuccess(testConfig())
        assertEquals(CrashKind.EARLY_CRASH, t.classifyExit(0L))
        assertEquals(CrashKind.EARLY_CRASH, t.classifyExit(EngineCrashTracker.EARLY_WINDOW_MS))
    }

    @Test
    fun `exit after 60s of ready is late crash`() {
        val t = tracker()
        t.recordSuccess(testConfig())
        assertEquals(CrashKind.LATE_CRASH, t.classifyExit(EngineCrashTracker.EARLY_WINDOW_MS + 1))
    }

    @Test
    fun `exit without a ready time is treated as startup failure`() {
        val t = tracker()
        assertEquals(CrashKind.STARTUP, t.classifyExit(null))
    }

    @Test
    fun `startup classification reflects whether the process existed`() {
        val t = tracker()
        assertEquals(CrashKind.STARTUP_CHAIN, t.classifyStartup(readyKnown = false))
        assertEquals(CrashKind.STARTUP, t.classifyStartup(readyKnown = true))
    }

    @Test
    fun `late crash between counted crashes does not reset the strike streak`() {
        val t = tracker()
        t.record(CrashKind.EARLY_CRASH, "a")
        t.record(CrashKind.LATE_CRASH, "ignored")
        t.record(CrashKind.STARTUP, "b")
        // a and b are two counted failures in a row; the late crash in between neither counts
        // nor resets the streak, so SafeMode is entered at b.
        assertNotNull(t.safeMode)
        assertEquals(listOf(CrashKind.EARLY_CRASH, CrashKind.STARTUP), t.strikes().map { it.kind })
    }
}
