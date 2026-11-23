package dev.zanderp.opencfmoto

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates PXC discovery without stalling 12s on mDNS when the bike gateway (`:10930`)
 * responds directly, while waking immediately if NSD advertises a non-default endpoint.
 * All [probe] invocations stay serialized on the caller thread.
 */
internal object EasyConnProbeCoordinator {
    private const val DIRECT_ATTEMPTS = 3
    private const val DIRECT_CONNECT_TIMEOUT_MS = 1_500
    private const val NSD_CONNECT_TIMEOUT_MS = 1_500
    private const val NSD_PROBE_ATTEMPTS = 3
    private const val PORT_SCAN_ATTEMPTS = 2

    fun <T> discoverAndProbe(
        gatewayHost: T,
        directPort: Int,
        isRunning: () -> Boolean,
        probe: (host: T, port: Int, attempts: Int, connectTimeoutMs: Int) -> Boolean,
        discoverNsdAsync: (onComplete: (Pair<T, Int>?) -> Unit) -> () -> Unit,
        scanOpenPorts: () -> List<Int>,
        directRetryDelayMs: Long = 500L,
        nsdFinalWaitMs: Long = 5_000L,
    ): Boolean {
        if (!isRunning()) return false

        val nsdResult = AtomicReference<Pair<T, Int>?>(null)
        val nsdDone = CountDownLatch(1)
        val cancelNsd = discoverNsdAsync { endpoint ->
            if (endpoint != null) nsdResult.compareAndSet(null, endpoint)
            nsdDone.countDown()
        }

        try {
            var probedNsd = false

            fun tryPendingNsd(): Boolean {
                val ep = nsdResult.get() ?: return false
                if (probedNsd) return false
                probedNsd = true
                return probe(ep.first, ep.second, NSD_PROBE_ATTEMPTS, NSD_CONNECT_TIMEOUT_MS)
            }

            for (attempt in 1..DIRECT_ATTEMPTS) {
                if (!isRunning()) return false
                if (attempt > 1) {
                    if (tryPendingNsd()) return true
                    if (!isRunning()) return false
                }
                if (probe(gatewayHost, directPort, 1, DIRECT_CONNECT_TIMEOUT_MS)) return true
                if (!isRunning()) return false
                if (attempt < DIRECT_ATTEMPTS && !probedNsd && nsdResult.get() == null) {
                    try {
                        nsdDone.await(directRetryDelayMs, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            }

            if (!probedNsd && isRunning()) {
                if (tryPendingNsd()) return true
                if (!isRunning()) return false
                if (nsdResult.get() == null) {
                    try {
                        nsdDone.await(nsdFinalWaitMs, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                if (!isRunning()) return false
                if (tryPendingNsd()) return true
            }

            if (!isRunning()) return false
            val open = scanOpenPorts()
            if (!isRunning()) return false
            val ordered = open.sortedBy { if (it == directPort) 0 else 1 }
            for (port in ordered) {
                if (!isRunning()) return false
                if (probe(gatewayHost, port, PORT_SCAN_ATTEMPTS, NSD_CONNECT_TIMEOUT_MS)) return true
            }
            return false
        } finally {
            runCatching { cancelNsd() }
        }
    }
}
