package com.wangzan101.solace.backend

import io.github.jackwener.dsh.core.engine.DshCapabilityRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSafetyPolicyTest {
    private fun policy(unlocks: HostSafetyPolicy.Unlocks = HostSafetyPolicy.Unlocks()) =
        HostSafetyPolicy(ownAppPackage = "com.wangzan101.solace").also { it.setPolicies(unlocks = unlocks) }

    @Test
    fun denyListIsHardDeniedEvenWithTaskGrant() {
        val p = policy()
        p.grantAllForTask("t1")
        val verdict = p.evaluate("app_launch", "com.eg.android.AlipayGphone", "t1")
        assertTrue(verdict is SafetyVerdict.Denied)
        assertTrue((verdict as SafetyVerdict.Denied).reason.contains("com.eg.android.AlipayGphone"))
    }

    @Test
    fun confirmEveryWritePackageTriggersConfirmation() {
        val p = policy()
        assertEquals(
            SafetyVerdict.Confirm::class,
            p.evaluate("screen_type", "com.tencent.mm", "t1")::class,
        )
    }

    @Test
    fun systemPackageReadAllowedWriteDenied() {
        val p = policy()
        assertTrue(p.evaluate("setting_get", "com.android.settings", "t1") is SafetyVerdict.Allowed)
        assertTrue(p.evaluate("setting_put", "com.android.settings", "t1") is SafetyVerdict.Denied)
    }

    @Test
    fun secretFieldsDeniedWhenUnlocksAllOff() {
        val p = policy()
        assertTrue(p.evaluate("sms_read", null, "t1") is SafetyVerdict.Denied)
        val open = policy(HostSafetyPolicy.Unlocks(secretFields = true))
        assertTrue(open.evaluate("sms_read", null, "t1") is SafetyVerdict.Confirm)
    }

    @Test
    fun taskGrantExpiresWhenTaskChanges() {
        val p = policy()
        p.grantAllForTask("t1")
        assertTrue(p.evaluate("screen_tap", "com.example.app", "t1") is SafetyVerdict.Allowed)
        val next = p.evaluate("screen_tap", "com.example.app", "t2")
        assertTrue(next is SafetyVerdict.Confirm)
    }

    @Test
    fun allowOnceIsConsumedOnFirstUse() {
        val p = policy()
        p.grantOnce("t1", "com.example.app", "screen_tap")
        assertTrue(p.evaluate("screen_tap", "com.example.app", "t1") is SafetyVerdict.Allowed)
        assertTrue(p.evaluate("screen_tap", "com.example.app", "t1") is SafetyVerdict.Confirm)
    }

    @Test
    fun cannotUnlockOutsideSetPolicies() {
        val p = HostSafetyPolicy()
        // unlocks 默认全 false;任何不走 setPolicies 的途径都改不了它。
        assertTrue(p.evaluate("sms_read", null, "t1") is SafetyVerdict.Denied)
        p.setPolicies(unlocks = HostSafetyPolicy.Unlocks(secretFields = true))
        assertTrue(p.evaluate("sms_read", null, "t1") !is SafetyVerdict.Denied)
    }

    @Test
    fun ownAppDeniedUntilUnlocked() {
        val p = policy()
        assertTrue(p.evaluate("file_read", "com.wangzan101.solace", "t1") is SafetyVerdict.Denied)
        val open = policy(HostSafetyPolicy.Unlocks(ownApp = true))
        assertTrue(open.evaluate("file_read", "com.wangzan101.solace", "t1") is SafetyVerdict.Allowed)
    }

    @Test
    fun readOnlyStaysAllowedWithoutAnyUnlock() {
        val p = policy()
        assertEquals(
            SafetyVerdict.Allowed::class,
            p.evaluate("device_info", null, "t1", risk = DshCapabilityRisk.READ)::class,
        )
    }
}
