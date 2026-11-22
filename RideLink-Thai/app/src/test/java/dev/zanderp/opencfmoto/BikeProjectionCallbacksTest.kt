package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeProjectionCallbacksTest {
    @Test fun wifiAndPxcCallbacksFromAStoppedAttemptCannotPublish() {
        val queue = mutableListOf<Runnable>()
        val changes = mutableListOf<String>()
        val callbacks = BikeProjectionCallbacks(
            post = { queue.add(it) },
            postDelayed = { task, _ -> queue.add(task) },
            remove = { queue.remove(it) },
        )
        val old = callbacks.begin()
        callbacks.dispatch(old) { changes.add("old Wi-Fi error") }
        callbacks.end()
        val next = callbacks.begin()
        callbacks.dispatch(old) { changes.add("old PXC frame") }
        callbacks.dispatch(next) { changes.add("new Wi-Fi ready") }

        queue.forEach(Runnable::run)

        assertEquals(listOf("new Wi-Fi ready"), changes)
    }

    @Test fun aDequeuedOldWatchdogCannotClearOrStartTheNewAttempt() {
        val queue = mutableListOf<Runnable>()
        val changes = mutableListOf<String>()
        val callbacks = BikeProjectionCallbacks(
            post = { queue.add(it) },
            postDelayed = { task, _ -> queue.add(task) },
            remove = { queue.remove(it) },
        )
        val old = callbacks.begin()
        callbacks.scheduleRetry(old, 500) { changes.add("old watchdog") }
        val dequeued = queue.single()
        callbacks.end()
        assertTrue(queue.isEmpty())
        val next = callbacks.begin()
        callbacks.scheduleRetry(next, 500) { changes.add("new watchdog") }

        dequeued.run()
        callbacks.scheduleRetry(next, 500) { changes.add("duplicate watchdog") }
        queue.single().run()

        assertEquals(listOf("new watchdog"), changes)
    }
}
