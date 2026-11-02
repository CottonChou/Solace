package com.zizephus.solace.harness.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineResourceBudgetTest {

    @Test
    fun `memory tiers map to budgets`() {
        assertEquals(ResourceBudget(2048, 8, false), EngineResourceBudget.fromDeviceProfile(8192, false, false))
        assertEquals(ResourceBudget(2048, 8, false), EngineResourceBudget.fromDeviceProfile(16384, false, false))
        assertEquals(ResourceBudget(1536, 6, false), EngineResourceBudget.fromDeviceProfile(6144, false, false))
        assertEquals(ResourceBudget(1024, 4, false), EngineResourceBudget.fromDeviceProfile(4096, false, false))
        assertEquals(ResourceBudget(512, 2, false), EngineResourceBudget.fromDeviceProfile(2048, false, false))
    }

    @Test
    fun `lowRam forces the lowest tier regardless of ram`() {
        assertEquals(ResourceBudget(512, 2, false), EngineResourceBudget.fromDeviceProfile(16384, true, false))
    }

    @Test
    fun `thermal lite halves old space and floors uv threads`() {
        assertEquals(ResourceBudget(1024, 8, true), EngineResourceBudget.fromDeviceProfile(16384, false, true))
        assertEquals(ResourceBudget(768, 6, true), EngineResourceBudget.fromDeviceProfile(7168, false, true))
        assertEquals(ResourceBudget(512, 4, true), EngineResourceBudget.fromDeviceProfile(4096, false, true))
        assertEquals(ResourceBudget(256, 2, true), EngineResourceBudget.fromDeviceProfile(2048, false, true))
    }

    @Test
    fun `toEnv exposes node options and threadpool`() {
        val env = EngineResourceBudget.fromDeviceProfile(8192, false, false).toEnv()
        assertEquals("--max-old-space-size=2048", env["NODE_OPTIONS"])
        assertEquals("8", env["UV_THREADPOOL_SIZE"])
    }

    @Test
    fun `thermal controller holds lite through the 30s cooldown`() {
        var now = 1_000L
        val controller = ThermalLiteController(clock = { now })
        controller.enterLite()
        assertTrue(controller.isLite)
        now += 29_999
        controller.update(0)
        assertTrue(controller.isLite)
        now += 1
        controller.update(0)
        assertFalse(controller.isLite)
    }

    @Test
    fun `re-entering lite restarts the cooldown and high level always enters`() {
        var now = 0L
        val controller = ThermalLiteController(clock = { now })
        controller.update(2)
        assertTrue(controller.isLite)
        now += 31_000
        controller.update(1)
        now += 29_000
        controller.update(0)
        assertTrue(controller.isLite)
        now += 1_001
        controller.update(0)
        assertFalse(controller.isLite)
    }

    @Test
    fun `mergeNodeOptions keeps host flags and lets later flags win`() {
        val merged = EngineRuntimeEnv.mergeNodeOptions("--max-old-space-size=2048", listOf("--trace-gc", "--max-old-space-size=1024"))
        assertEquals("--max-old-space-size=1024 --trace-gc", merged)
    }
}
