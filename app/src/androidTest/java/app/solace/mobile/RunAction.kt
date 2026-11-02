package app.solace.mobile

import app.solace.mobile.core.action.ActionExecutor
import app.solace.mobile.core.action.ActionRecorder
import app.solace.mobile.core.action.Origin
import app.solace.mobile.core.action.execution
import app.solace.mobile.core.guard.OperationGuards
import app.solace.mobile.core.model.Part
import app.solace.mobile.core.record.RunSink
import app.solace.mobile.core.tool.ToolDispatcher
import app.solace.mobile.core.tool.ToolExecution

/** 设备测试里直接做一个工具调用:和产品一样经执行入口(不配确认关卡,不等锁屏)。 */
suspend fun ToolDispatcher.runAction(call: Part.ToolCall): ToolExecution =
    ActionExecutor(this, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE)).run(call, Origin.Model(0)).execution()
