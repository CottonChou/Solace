package app.solace.mobile.core.general

import app.solace.mobile.core.child.ChildPhase
import app.solace.mobile.core.observation.ObsKind
import app.solace.mobile.core.observation.Observation
import app.solace.mobile.core.observation.Trust
import app.solace.mobile.core.observation.Untrusted
import app.solace.mobile.core.prompt.Prompts
import app.solace.mobile.core.run.Attendance
import app.solace.mobile.core.run.RunId
import app.solace.mobile.core.run.RunOrigin
import app.solace.mobile.core.run.RunProvenance
import app.solace.mobile.core.run.RunSpec
import app.solace.mobile.core.tool.AgentTool
import app.solace.mobile.core.tool.Args
import app.solace.mobile.core.tool.Risk
import app.solace.mobile.core.tool.ToolContext
import app.solace.mobile.core.tool.ToolDef
import app.solace.mobile.core.tool.ToolExecution
import app.solace.mobile.core.tool.Verb
import app.solace.mobile.core.tool.str
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SpawnAgent : AgentTool {
    override val verb = Verb.SpawnAgent
    override val risk = Risk.Write
    override val offScreen = true
    override val def = ToolDef("spawn_agent", Prompts.tool("spawn_agent"), mapOf(
        "goal" to str("A bounded research question for the child agent, using web and sandbox reads only."),
        "format" to str("Optional output format.", listOf("text", "json")),
    ), listOf("goal"))

    override fun available(ctx: ToolContext) = ctx.config.childHost != null && ctx.config.runSpec != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val parent = ctx.config.runSpec ?: error("no parent RunSpec")
        val host = ctx.config.childHost ?: error("no child host")
        val goal = a.str("goal", max = 4000)!!
        val format = a.str("format")
        val child = RunSpec(RunId(UUID.randomUUID().toString()), goal, parent.threadId,
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended),
            capabilities = setOf("web", "sandbox"), readOnly = true, parent = parent.id, stepBudget = 15)
        val result = host.spawn(child, format)
        val text = result.text.take(4000)
        return ToolExecution(buildJsonObject {
            put("ok", result.phase == ChildPhase.Completed)
            put("childRunId", result.id.value)
            put("status", result.phase.name.lowercase())
            put("result", Untrusted.wrap(text))
        }, observations = listOf(Observation(ObsKind.Reply, text, source = "child", trust = Trust.Untrusted)))
    }
}
