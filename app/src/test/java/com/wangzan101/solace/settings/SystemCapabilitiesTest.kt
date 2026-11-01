package com.wangzan101.solace.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCapabilitiesTest {
    @Test
    fun summaryCountsEveryIndependentlyGrantedCapability() {
        val status = SystemCapabilities(
            notificationListener = true,
            allFiles = true,
            sms = true,
            contacts = true,
            calendar = true,
            writeSettings = true,
            dnd = true,
            exactAlarm = false,
        )

        assertEquals(7, status.grantedCount)
        assertFalse(status.allGranted)
        assertFalse(status.isGranted(SystemCapability.ExactAlarm))
    }

    @Test
    fun allGrantedRequiresEveryCapability() {
        val status = SystemCapabilities(
            notificationListener = true,
            allFiles = true,
            sms = true,
            contacts = true,
            calendar = true,
            writeSettings = true,
            dnd = true,
            exactAlarm = true,
        )

        assertEquals(SystemCapability.entries.size, status.grantedCount)
        assertTrue(status.allGranted)
    }
}
