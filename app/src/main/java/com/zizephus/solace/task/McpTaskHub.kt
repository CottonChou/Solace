package com.zizephus.solace.task

import android.content.Context
import com.zizephus.solace.core.mcp.McpTaskHost
import com.zizephus.solace.core.mcp.McpTaskLink
import com.zizephus.solace.core.record.RunEvent
import com.zizephus.solace.core.run.RunEventBus
import com.zizephus.solace.core.run.RunId
import com.zizephus.solace.core.run.RunSpec

/** 本机传输桩的整任务出口；将来加 socket 时仍经 TaskService 提交。 */
class McpTaskHub(private val context: Context) : McpTaskHost {
    private val buses = HashMap<RunId, RunEventBus>()

    @Synchronized override fun submit(spec: RunSpec): McpTaskLink {
        require(spec.provenance.origin == com.zizephus.solace.core.run.RunOrigin.Mcp)
        val bus = buses.getOrPut(spec.id) { RunEventBus() }
        try { TaskService.startMcp(context, spec.goal, spec.threadId, spec.id.value, spec.provenance.attendance) }
        catch (e: Exception) { buses.remove(spec.id); throw e }
        return McpTaskLink(spec.id, bus) { TaskService.cancelMcp(context, spec.id.value) }
    }

    @Synchronized fun attach(id: String, source: RunEventBus): AutoCloseable? =
        buses[RunId(id)]?.let { sink -> source.subscribe(sink) }

    @Synchronized fun ended(id: String, result: String) {
        buses.remove(RunId(id))?.event(RunEvent.State(System.currentTimeMillis(), "mcp_task_ended", result))
    }
}
