package com.zizephus.solace.backend

import com.zizephus.solace.core.risk.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolaceHarnessApprovalTest {
    @Test
    fun dangerousCapabilityMapsToDestructiveConfirmation() {
        val request = harnessConfirmationRequest("file_delete", "Delete downloads", "com.zizephus.solace")

        assertEquals(IrrevAction.delete, request.action)
        assertTrue(request.content.contains("Harness risk: dangerous"))
    }

    @Test
    fun writeCapabilityMapsToSubmissionConfirmation() {
        assertEquals(
            IrrevAction.submit,
            harnessConfirmationRequest("screen_tap", "Tap send", "com.zizephus.solace").action,
        )
    }

    @Test
    fun unknownBuiltinDefaultsToWriteConfirmation() {
        assertEquals(
            IrrevAction.submit,
            harnessConfirmationRequest("bash", "Run a command", "com.zizephus.solace").action,
        )
    }
}
