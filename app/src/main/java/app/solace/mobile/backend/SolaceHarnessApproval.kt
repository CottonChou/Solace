package app.solace.mobile.backend

import app.solace.mobile.core.gate.ConfirmationRequest
import app.solace.mobile.core.risk.IrrevAction
import app.solace.mobile.harness.core.engine.SolaceHarnessCapabilityDescriptors
import app.solace.mobile.harness.core.engine.SolaceHarnessCapabilityRisk

/**
 * Map a Harness approval to Solace's confirmation vocabulary. Harness and Solace use different risk models;
 * the capability catalogue is the authoritative bridge between them.
 */
internal fun harnessConfirmationRequest(
    tool: String,
    reason: String,
    packageName: String,
): ConfirmationRequest {
    val risk = SolaceHarnessCapabilityDescriptors.all.firstOrNull { it.name == tool }?.risk
    val action = when (risk) {
        SolaceHarnessCapabilityRisk.DANGEROUS -> IrrevAction.delete
        SolaceHarnessCapabilityRisk.WRITE, null -> IrrevAction.submit
        SolaceHarnessCapabilityRisk.SENSITIVE, SolaceHarnessCapabilityRisk.READ -> IrrevAction.other
    }
    val riskText = risk?.wire ?: "write"
    return ConfirmationRequest(
        action = action,
        target = tool,
        content = "$reason\n\nHarness risk: $riskText",
        amount = null,
        app = packageName,
    )
}
