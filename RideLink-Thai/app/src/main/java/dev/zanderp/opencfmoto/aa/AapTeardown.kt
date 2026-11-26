// RideLink TH: graceful AAP teardown over the AccessoryConnection seam.
package dev.zanderp.opencfmoto.aa

/**
 * Closes an AAP session the way Gearhead's Head Unit Server (:5277) expects: send
 * `MESSAGE_BYEBYE_REQUEST` (only after the handshake, only while the peer is still connected),
 * wait briefly for the peer's response, then always close the connection. Closing unblocks a poll
 * thread parked in [AccessoryConnection.recvBlocking]. Pure Kotlin: no Android types, so the
 * ordering contract is tested with an in-memory adapter.
 */
object AapTeardown {
    const val BYEBYE_RESPONSE_TIMEOUT_MS = 750L

    data class Result(val graceful: Boolean)

    fun close(
        connection: AccessoryConnection?,
        handshakeComplete: Boolean,
        sendByeBye: () -> Boolean,
        awaitByeByeResponse: (Long) -> Boolean,
    ): Result {
        if (connection == null) return Result(graceful = false)
        var graceful = false
        try {
            if (handshakeComplete && connection.isConnected) {
                graceful = try {
                    sendByeBye() && awaitByeByeResponse(BYEBYE_RESPONSE_TIMEOUT_MS)
                } catch (_: Exception) { false }
            }
        } finally {
            try { connection.disconnect() } catch (_: Exception) {}
        }
        return Result(graceful)
    }
}
