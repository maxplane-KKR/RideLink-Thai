// RideLink TH: graceful AAP teardown over the AccessoryConnection seam.
package dev.zanderp.opencfmoto.aa

/**
 * Closes an AAP session the way Gearhead's Head Unit Server (:5277) expects: send
 * `MESSAGE_BYEBYE_REQUEST` (only after the handshake, only while the peer is still connected),
 * give it a short flush window, then ALWAYS close the connection — closing also unblocks a poll
 * thread parked in [AccessoryConnection.recvBlocking]. Pure Kotlin: no Android types, so the
 * ordering contract is tested with an in-memory adapter.
 */
object AapTeardown {
    const val BYEBYE_FLUSH_MS = 120L

    data class Result(val graceful: Boolean)

    fun close(
        connection: AccessoryConnection?,
        handshakeComplete: Boolean,
        sendByeBye: () -> Boolean,
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
    ): Result {
        if (connection == null) return Result(graceful = false)
        var graceful = false
        try {
            if (handshakeComplete && connection.isConnected) {
                graceful = try { sendByeBye() } catch (_: Exception) { false }
                if (graceful) {
                    try { sleeper(BYEBYE_FLUSH_MS) } catch (_: InterruptedException) {}
                }
            }
        } finally {
            try { connection.disconnect() } catch (_: Exception) {}
        }
        return Result(graceful)
    }
}
