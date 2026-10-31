package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorKeepAliveTest {

    @Test
    fun `oppo family manufacturers are recognized`() {
        assertEquals("oppo", VendorKeepAlive.vendorManufacturer("OPPO"))
        assertEquals("oneplus", VendorKeepAlive.vendorManufacturer("OnePlus"))
        assertEquals("realme", VendorKeepAlive.vendorManufacturer("realme"))
    }

    @Test
    fun `non vendor manufacturers return null`() {
        assertNull(VendorKeepAlive.vendorManufacturer("Xiaomi"))
        assertNull(VendorKeepAlive.vendorManufacturer("samsung"))
        assertNull(VendorKeepAlive.vendorManufacturer(null))
        assertNull(VendorKeepAlive.vendorManufacturer("  "))
    }

    @Test
    fun `battery entry prefers profile page then list then power manager then app details`() {
        val targets = VendorKeepAlive.candidateTargets(VendorKeepAlive.Entry.Battery, "com.example.app")
        assertTrue(targets.first() is VendorKeepAlive.Target.Component)
        val last = targets.last()
        assertTrue(last is VendorKeepAlive.Target.AppDetails)
        assertEquals(4, targets.size)
    }

    @Test
    fun `status flags default to false until available`() {
        // 纯构造检查:不可用即 false,不抛异常。
        val s = VendorKeepAlive.Status(false, false, false, null)
        assertFalse(s.batteryEntryAvailable)
    }
}
