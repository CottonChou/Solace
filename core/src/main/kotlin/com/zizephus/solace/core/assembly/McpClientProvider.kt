package com.zizephus.solace.core.assembly

import com.zizephus.solace.core.cap.mcp.McpCapability
import com.zizephus.solace.core.cap.mcp.McpConnection
import com.zizephus.solace.core.run.RunSpec

internal class McpClientProvider(private val connection: McpConnection) : CapabilityProvider {
    override val id: String = "mcp:${connection.endpoint.serverId}"
    override fun open(spec: RunSpec) = McpCapability(connection, id)
}
