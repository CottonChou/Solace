package app.solace.mobile.backend

import app.solace.mobile.core.risk.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolaceHarnessApprovalTest {
    @Test
    fun dangerousCapabilityMapsToDestructiveConfirmation() {
        val request = harnessConfirmationRequest("file_delete", "Delete downloads", "app.solace.mobile")

        assertEquals(IrrevAction.delete, request.action)
        assertTrue(request.content.contains("Harness risk: dangerous"))
    }

    @Test
    fun writeCapabilityMapsToSubmissionConfirmation() {
        assertEquals(
            IrrevAction.submit,
            harnessConfirmationRequest("screen_tap", "Tap send", "app.solace.mobile").action,
        )
    }

    @Test
    fun unknownBuiltinDefaultsToWriteConfirmation() {
        assertEquals(
            IrrevAction.submit,
            harnessConfirmationRequest("bash", "Run a command", "app.solace.mobile").action,
        )
    }
}
