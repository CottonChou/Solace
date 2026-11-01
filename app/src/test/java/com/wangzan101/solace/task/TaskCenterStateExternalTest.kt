package com.wangzan101.solace.task

import com.wangzan101.solace.backend.ExecutionBackend
import com.wangzan101.solace.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCenterStateExternalTest {
    @Test
    fun externalRunParticipatesInTaskCenterLifecycle() {
        val center = TaskCenterState()
        val run = ExternalTaskRun(
            key = "dsh-conversation",
            conversationId = "conversation",
            goal = "Summarize my downloads",
            backend = ExecutionBackend.DeepSeekHarness,
        )

        center.publishExternal(run, TaskState.Running(1, "Reading files"))

        assertEquals(run, center.external("conversation"))
        assertEquals(run, center.externalByKey(run.key))
        assertTrue(center.busy.value)
        assertEquals(listOf("conversation"), center.activeConversations.value)

        center.updateExternal(
            "conversation",
            TaskState.WaitingInput("Which folder?", listOf("Downloads", "Documents")),
        )

        assertTrue(center.externalStateByKey(run.key)?.value is TaskState.WaitingInput)
        assertEquals("conversation", center.shownTask.value?.first)

        center.removeExternal("conversation")

        assertNull(center.external("conversation"))
        assertFalse(center.busy.value)
        assertTrue(center.activeConversations.value.isEmpty())
        assertNull(center.shownTask.value)
    }

    @Test
    fun removedTerminalExternalRunIsExposedToCapsuleHistory() {
        val center = TaskCenterState()
        val run = ExternalTaskRun(
            key = "dsh-finished",
            conversationId = "finished",
            goal = "Finish the report",
            backend = ExecutionBackend.DeepSeekHarness,
        )

        center.publishExternal(run)
        center.updateExternal("finished", TaskState.Succeeded("Report ready"))
        center.removeExternal("finished")

        assertEquals(run.key, center.endedRunId.value)
    }
}
