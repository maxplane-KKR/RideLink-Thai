package dev.zanderp.opencfmoto

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyConnProbeCoordinatorTest {

    @Test
    fun `direct 10930 success cancels NSD immediately without port scan or concurrent probes`() {
        val running = AtomicBoolean(true)
        val nsdCancelled = AtomicBoolean(false)
        val scanCalled = AtomicBoolean(false)
        val inFlight = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val probedPorts = mutableListOf<Int>()

        val result = EasyConnProbeCoordinator.discoverAndProbe(
            gatewayHost = "192.168.43.1",
            directPort = 10930,
            isRunning = { running.get() },
            probe = { _, port, attempts, _ ->
                val cur = inFlight.incrementAndGet()
                maxConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
                try {
                    probedPorts.add(port)
                    assertEquals(1, attempts)
                    true
                } finally {
                    inFlight.decrementAndGet()
                }
            },
            discoverNsdAsync = { _ ->
                { nsdCancelled.set(true) }
            },
            scanOpenPorts = {
                scanCalled.set(true)
                emptyList()
            },
            directRetryDelayMs = 5_000L,
            nsdFinalWaitMs = 5_000L,
        )

        assertTrue(result)
        assertEquals(listOf(10930), probedPorts)
        assertEquals(1, maxConcurrent.get())
        assertTrue(nsdCancelled.get())
        assertFalse(scanCalled.get())
    }

    @Test
    fun `wakes immediately when NSD resolves a non-default port after direct probe fails`() {
        val probed = mutableListOf<Pair<String, Int>>()
        val nsdCancelled = AtomicBoolean(false)
        val startMs = System.nanoTime()

        val result = EasyConnProbeCoordinator.discoverAndProbe(
            gatewayHost = "192.168.43.1",
            directPort = 10930,
            isRunning = { true },
            probe = { host, port, _, _ ->
                probed.add(host to port)
                port == 10931
            },
            discoverNsdAsync = { onComplete ->
                val t = thread(isDaemon = true) {
                    try {
                        Thread.sleep(30L)
                        onComplete("192.168.43.1" to 10931)
                    } catch (_: InterruptedException) {
                    }
                }
                val cancel: () -> Unit = {
                    nsdCancelled.set(true)
                    t.interrupt()
                }
                cancel
            },
            scanOpenPorts = { emptyList() },
            directRetryDelayMs = 5_000L,
            nsdFinalWaitMs = 5_000L,
        )

        val elapsedMs = (System.nanoTime() - startMs) / 1_000_000L
        assertTrue(result)
        assertTrue("Expected fast wakeup on NSD resolve, took ${elapsedMs}ms", elapsedMs < 1_500L)
        assertEquals(listOf("192.168.43.1" to 10930, "192.168.43.1" to 10931), probed)
        assertTrue(nsdCancelled.get())
    }

    @Test
    fun `stopping mid-probe cancels NSD and returns immediately without scanning ports`() {
        val running = AtomicBoolean(true)
        val nsdCancelled = AtomicBoolean(false)
        val scanCalled = AtomicBoolean(false)
        val startMs = System.nanoTime()

        val result = EasyConnProbeCoordinator.discoverAndProbe(
            gatewayHost = "192.168.43.1",
            directPort = 10930,
            isRunning = { running.get() },
            probe = { _, _, _, _ ->
                running.set(false)
                false
            },
            discoverNsdAsync = { _ ->
                { nsdCancelled.set(true) }
            },
            scanOpenPorts = {
                scanCalled.set(true)
                listOf(10930)
            },
            directRetryDelayMs = 5_000L,
            nsdFinalWaitMs = 5_000L,
        )

        val elapsedMs = (System.nanoTime() - startMs) / 1_000_000L
        assertFalse(result)
        assertTrue("Expected immediate exit on stop, took ${elapsedMs}ms", elapsedMs < 1_000L)
        assertTrue(nsdCancelled.get())
        assertFalse(scanCalled.get())
    }

    @Test
    fun `falls back to open port scan ordered with direct port first when direct and NSD miss`() {
        val probedPorts = mutableListOf<Int>()
        var scanCalls = 0

        val result = EasyConnProbeCoordinator.discoverAndProbe(
            gatewayHost = "192.168.43.1",
            directPort = 10930,
            isRunning = { true },
            probe = { _, port, _, _ ->
                probedPorts.add(port)
                scanCalls > 0 && port == 10925
            },
            discoverNsdAsync = { onComplete ->
                onComplete(null)
                val cancel: () -> Unit = {}
                cancel
            },
            scanOpenPorts = {
                scanCalls++
                listOf(10925, 10930, 10928)
            },
            directRetryDelayMs = 10L,
            nsdFinalWaitMs = 10L,
        )

        assertTrue(result)
        assertEquals(1, scanCalls)
        assertEquals(listOf(10930, 10930, 10930, 10930, 10925), probedPorts)
    }
}
