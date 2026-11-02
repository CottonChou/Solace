package com.zizephus.solace.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalNotifyPolicyTest {

    @Test
    fun `slots do not collide for one run`() {
        val p = ExternalNotifyPolicy.progressId("run-1")
        val a = ExternalNotifyPolicy.attentionId("run-1")
        val i = ExternalNotifyPolicy.inputId("run-1")
        assertNotEquals(p, a)
        assertNotEquals(a, i)
        assertNotEquals(p, i)
    }

    @Test
    fun `different runs map to different slots`() {
        assertNotEquals(ExternalNotifyPolicy.progressId("run-1"), ExternalNotifyPolicy.progressId("run-2"))
    }

    @Test
    fun `public title drops sensitive content`() {
        assertEquals(ExternalNotifyPolicy.TITLE_ATTENTION, ExternalNotifyPolicy.publicTitle(attention = true, sensitive = "转账 5000 元至张三"))
        assertEquals(ExternalNotifyPolicy.TITLE_PROGRESS, ExternalNotifyPolicy.publicTitle(attention = false, sensitive = "读取短信验证码 123456"))
    }

    @Test
    fun `summary title carries count`() {
        assertTrue(ExternalNotifyPolicy.summaryTitle(3).contains("3"))
    }

    @Test
    fun `approval expires after timeout window`() {
        val posted = 1_000_000L
        assertFalse(ExternalNotifyPolicy.approvalExpired(posted, posted + ExternalNotifyPolicy.CONFIRM_TIMEOUT_MS - 1))
        assertTrue(ExternalNotifyPolicy.approvalExpired(posted, posted + ExternalNotifyPolicy.CONFIRM_TIMEOUT_MS))
    }
}
