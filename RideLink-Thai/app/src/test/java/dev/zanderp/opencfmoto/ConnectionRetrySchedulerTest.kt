package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRetrySchedulerTest {
    private class FakeHandler {
        val pending = mutableListOf<Pair<Runnable, Long>>()
        fun post(task: Runnable, delay: Long) { pending.add(task to delay) }
        fun remove(task: Runnable) { pending.removeAll { it.first === task } }
        fun runNext() {
            val next = pending.minByOrNull { it.second } ?: error("ไม่มีงานรอ")
            pending.remove(next)
            next.first.run()
        }
    }

    @Test fun manualRetryRunsOnceWithoutWaitingForTheOriginalDelay() {
        val handler = FakeHandler()
        var attempts = 0
        val scheduler = ConnectionRetryScheduler(handler::post, handler::remove, { true }) { attempts++ }
        scheduler.schedule(4_000)
        assertTrue(scheduler.retryNow())
        assertTrue(scheduler.retryNow())
        assertEquals(listOf(0L), handler.pending.map { it.second })
        handler.runNext()
        assertEquals(1, attempts)
        assertFalse(scheduler.retryNow())
        assertTrue(handler.pending.isEmpty())
    }

    @Test fun recoveryOrStopBeforeRetryDoesNotCancelAWorkingNetwork() {
        val handler = FakeHandler()
        var available = false
        var attempts = 0
        val scheduler = ConnectionRetryScheduler(handler::post, handler::remove, { !available }) { attempts++ }
        scheduler.schedule(4_000)
        available = true
        assertFalse(scheduler.retryNow())
        handler.runNext()
        assertEquals(0, attempts)
        available = false
        scheduler.schedule(2_000)
        scheduler.cancel()
        assertTrue(handler.pending.isEmpty())
    }

    @Test fun aDequeuedOldRetryAndAnImmediateRetryCannotStartTwice() {
        val handler = FakeHandler()
        var attempts = 0
        val scheduler = ConnectionRetryScheduler(handler::post, handler::remove, { true }) { attempts++ }
        scheduler.schedule(4_000)
        val alreadyDequeued = handler.pending.single().first
        assertTrue(scheduler.retryNow())
        alreadyDequeued.run()
        handler.runNext()
        assertEquals(1, attempts)
    }

    @Test fun replacedRetryCannotStartAfterANewerRetryWasScheduled() {
        val handler = FakeHandler()
        var attempts = 0
        val scheduler = ConnectionRetryScheduler(handler::post, handler::remove, { true }) { attempts++ }
        scheduler.schedule(4_000)
        val oldTask = handler.pending.single().first
        scheduler.schedule(2_000)
        oldTask.run()
        assertEquals(0, attempts)
        handler.runNext()
        assertEquals(1, attempts)
    }
}
