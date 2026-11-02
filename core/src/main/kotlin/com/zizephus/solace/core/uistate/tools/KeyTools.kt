package com.zizephus.solace.core.uistate.tools

import com.zizephus.solace.core.uistate.ScreenFamily
import com.zizephus.solace.core.risk.GateTarget
import com.zizephus.solace.core.tool.ArgError
import com.zizephus.solace.core.tool.Args
import com.zizephus.solace.core.uistate.PressKeys
import com.zizephus.solace.core.tool.Risk
import com.zizephus.solace.core.tool.ToolContext
import com.zizephus.solace.core.tool.ToolDef
import com.zizephus.solace.core.tool.ToolExecution
import com.zizephus.solace.core.tool.Verb
import com.zizephus.solace.core.tool.argStr
import com.zizephus.solace.core.tool.str
import kotlinx.serialization.json.JsonObject
import com.zizephus.solace.core.uistate.action
import com.zizephus.solace.core.uistate.screen

internal class Back(f: ScreenFamily) : ScreenTool(f, "back") {
    override val verb = Verb.Back
    override val batchable = true
    override val routable = f.routable
    override val label = "返回"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name, describe()) }

    override suspend fun execute(a: Args, ctx: ToolContext) = ctx.action(ctx.screen.back())
}

/** 按键(回车、确认键可能是提交,要按目标判不可逆)。 */
internal class PressKey(f: ScreenFamily) : ScreenTool(f, "press_key") {
    override val verb = Verb.PressKey
    override val batchable = true
    override val routable = f.routable
    override val label = "按键"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Key
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name, describe(),
        mapOf("key" to str("One of the allowed KEYCODE_* names, or its numeric keycode string.")), listOf("key")) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val k = a.str("key")!!.trim()
        val code = PressKeys.resolve(k)
            ?: throw ArgError("key", "key '$k' is not allowed; only navigation/editing keys: ${keyNames(family)}")
        family.keys?.let { if (code !in it) throw ArgError("key", family.keysHint ?: "key '$k' is not allowed here") }
        return ctx.action(ctx.screen.pressKey(code))
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("key")
}
