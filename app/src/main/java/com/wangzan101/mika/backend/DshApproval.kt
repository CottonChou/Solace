package com.wangzan101.mika.backend

import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import io.github.jackwener.dsh.core.engine.DshCapabilityDescriptors
import io.github.jackwener.dsh.core.engine.DshCapabilityRisk

/**
 * Map a DSH approval to Mika's confirmation vocabulary. DSH and Mika use different risk models;
 * the capability catalogue is the authoritative bridge between them.
 */
internal fun dshConfirmationRequest(
    tool: String,
    reason: String,
    packageName: String,
): ConfirmationRequest {
    val risk = DshCapabilityDescriptors.all.firstOrNull { it.name == tool }?.risk
    val action = when (risk) {
        DshCapabilityRisk.DANGEROUS -> IrrevAction.delete
        DshCapabilityRisk.WRITE, null -> IrrevAction.submit
        DshCapabilityRisk.SENSITIVE, DshCapabilityRisk.READ -> IrrevAction.other
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
