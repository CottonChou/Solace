package com.wangzan101.solace

import com.wangzan101.solace.core.action.ActionExecutor
import com.wangzan101.solace.core.action.ActionRecorder
import com.wangzan101.solace.core.action.Origin
import com.wangzan101.solace.core.action.execution
import com.wangzan101.solace.core.guard.OperationGuards
import com.wangzan101.solace.core.model.Part
import com.wangzan101.solace.core.record.RunSink
import com.wangzan101.solace.core.tool.ToolDispatcher
import com.wangzan101.solace.core.tool.ToolExecution

/** 设备测试里直接做一个工具调用:和产品一样经执行入口(不配确认关卡,不等锁屏)。 */
suspend fun ToolDispatcher.runAction(call: Part.ToolCall): ToolExecution =
    ActionExecutor(this, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE)).run(call, Origin.Model(0)).execution()
