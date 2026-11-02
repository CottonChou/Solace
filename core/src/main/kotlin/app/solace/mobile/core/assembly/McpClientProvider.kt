package app.solace.mobile.core.assembly

import app.solace.mobile.core.cap.mcp.McpCapability
import app.solace.mobile.core.cap.mcp.McpConnection
import app.solace.mobile.core.run.RunSpec

internal class McpClientProvider(private val connection: McpConnection) : CapabilityProvider {
    override val id: String = "mcp:${connection.endpoint.serverId}"
    override fun open(spec: RunSpec) = McpCapability(connection, id)
}
