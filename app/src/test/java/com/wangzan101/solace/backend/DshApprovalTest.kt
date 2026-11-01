package com.wangzan101.solace.backend

import com.wangzan101.solace.core.risk.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshApprovalTest {
    @Test
    fun dangerousCapabilityMapsToDestructiveConfirmation() {
        val request = dshConfirmationRequest("file_delete", "Delete downloads", "com.wangzan101.solace")

        assertEquals(IrrevAction.delete, request.action)
        assertTrue(request.content.contains("DSH risk: dangerous"))
    }

    @Test
    fun writeCapabilityMapsToSubmissionConfirmation() {
        assertEquals(
            IrrevAction.submit,
            dshConfirmationRequest("screen_tap", "Tap send", "com.wangzan101.solace").action,
        )
    }

    @Test
    fun unknownBuiltinDefaultsToWriteConfirmation() {
        assertEquals(
            IrrevAction.submit,
            dshConfirmationRequest("bash", "Run a command", "com.wangzan101.solace").action,
        )
    }
}
