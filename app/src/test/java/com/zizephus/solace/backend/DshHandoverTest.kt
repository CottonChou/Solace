package com.zizephus.solace.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshHandoverTest {
    private fun smallTimeoutChannel() = DshHandoverChannel(timeoutMs = 200)

    @Test
    fun handBackCompletesDeferredWithCompleteOutcome() = runTest {
        val channel = DshHandoverChannel()
        val waiter = async { channel.wait("s1", "please check") }
        runCurrent()
        channel.complete("s1", "complete")
        val result = waiter.await()
        assertEquals("complete", result.outcome)
        assertFalse(result.skipped)
    }

    @Test
    fun skipMarksResultSkipped() = runTest {
        val channel = DshHandoverChannel()
        val waiter = async { channel.wait("s1", "please check") }
        runCurrent()
        channel.complete("s1", "skipped")
        val result = waiter.await()
        assertEquals("skipped", result.outcome)
        assertTrue(result.skipped)
    }

    @Test
    fun unknownSessionHandBackDoesNotCrash() = runTest {
        val channel = DshHandoverChannel()
        channel.complete("no-such-session", "complete")
        channel.cancel("no-such-session")
        channel.clear()
    }

    @Test
    fun waitTimesOutIntoTimeoutOutcome() = runTest {
        val channel = smallTimeoutChannel()
        val result = channel.wait("s1", "please check")
        assertEquals("handover_timeout", result.outcome)
        assertTrue(result.skipped)
    }

    @Test
    fun cancelAndClearFinishPendingWaitersAsSkipped() = runTest {
        val channel = DshHandoverChannel()
        val waiter = async { channel.wait("s1", "please check") }
        runCurrent()
        channel.cancel("s1")
        val result = waiter.await()
        assertEquals("skipped", result.outcome)
        assertTrue(result.skipped)

        val waiter2 = async { channel.wait("s2", "please check") }
        runCurrent()
        channel.clear()
        val result2 = waiter2.await()
        assertTrue(result2.skipped)
    }

    @Test
    fun reconnectReplacesStaleWaiterAsSkipped() = runTest {
        val channel = DshHandoverChannel()
        val stale = async { channel.wait("s1", "old run") }
        runCurrent()
        channel.connect("s1")
        assertTrue(stale.await().skipped)

        val fresh = async { channel.wait("s1", "new run") }
        runCurrent()
        channel.complete("s1", "finished")
        val result = fresh.await()
        assertEquals("finished", result.outcome)
        assertTrue(result.skipped)
    }
}
