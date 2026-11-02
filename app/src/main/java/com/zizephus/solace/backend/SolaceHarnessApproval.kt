package com.zizephus.solace.backend

import com.zizephus.solace.core.gate.ConfirmationRequest
import com.zizephus.solace.core.risk.IrrevAction
import com.zizephus.solace.harness.core.engine.SolaceHarnessCapabilityDescriptors
import com.zizephus.solace.harness.core.engine.SolaceHarnessCapabilityRisk

/**
 * Map a DSH approval to Solace's confirmation vocabulary. DSH and Solace use different risk models;
 * the capability catalogue is the authoritative bridge between them.
 */
internal fun dshConfirmationRequest(
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
        content = "$reason\n\nDSH risk: $riskText",
        amount = null,
        app = packageName,
    )
}
