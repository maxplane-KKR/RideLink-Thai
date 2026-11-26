package dev.zanderp.opencfmoto.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Second adapter on the [AccessoryConnection] seam (the first is [SocketAccessoryConnection]).
 * Records the order of operations so the graceful teardown contract is testable without sockets.
 */
private class InMemoryAccessoryConnection(connected: Boolean = true) : AccessoryConnection {
    val events = mutableListOf<String>()
    private var open = connected
    override val isSingleMessage: Boolean = true
    override val isConnected: Boolean get() = open
    override fun connect(): Boolean = open
    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        events += "send"; return if (open) length else -1
    }
    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int = -1
    override fun disconnect() { events += "disconnect"; open = false }
}

class AapTeardownSeamTest {
    private val unexpectedResponseWait: (Long) -> Boolean = { error("must not wait") }

    @Test
    fun `sends ByeBye and waits for acknowledgement before closing`() {
        val conn = InMemoryAccessoryConnection()
        val result = AapTeardown.close(
            connection = conn,
            handshakeComplete = true,
            sendByeBye = { conn.events += "byebye"; true },
            awaitByeByeResponse = {
                assertEquals(AapTeardown.BYEBYE_RESPONSE_TIMEOUT_MS, it)
                conn.events += "ack"
                true
            },
        )
        assertTrue(result.graceful)
        assertEquals(listOf("byebye", "ack", "disconnect"), conn.events)
    }

    @Test
    fun `a written ByeBye with no response uses unconfirmed teardown`() {
        val conn = InMemoryAccessoryConnection()
        val result = AapTeardown.close(
            conn, handshakeComplete = true,
            sendByeBye = { conn.events += "byebye"; true },
            awaitByeByeResponse = { conn.events += "timeout"; false },
        )
        assertFalse(result.graceful)
        assertEquals(listOf("byebye", "timeout", "disconnect"), conn.events)
    }

    @Test
    fun `skips ByeBye before handshake but still closes`() {
        val conn = InMemoryAccessoryConnection()
        val result = AapTeardown.close(
            conn, handshakeComplete = false, sendByeBye = { error("must not send") },
            awaitByeByeResponse = unexpectedResponseWait,
        )
        assertFalse(result.graceful)
        assertEquals(listOf("disconnect"), conn.events)
    }

    @Test
    fun `a failing or throwing ByeBye never prevents the socket from closing`() {
        val conn = InMemoryAccessoryConnection()
        val result = AapTeardown.close(
            conn, handshakeComplete = true, sendByeBye = { throw IllegalStateException("peer gone") },
            awaitByeByeResponse = unexpectedResponseWait,
        )
        assertFalse(result.graceful)
        assertEquals(listOf("disconnect"), conn.events)
    }

    @Test
    fun `already-closed peer is not written to`() {
        val conn = InMemoryAccessoryConnection(connected = false)
        val result = AapTeardown.close(
            conn, handshakeComplete = true, sendByeBye = { conn.events += "byebye"; true },
            awaitByeByeResponse = unexpectedResponseWait,
        )
        assertFalse(result.graceful)
        assertEquals(listOf("disconnect"), conn.events)
    }

    @Test
    fun `null connection is a no-op`() {
        assertFalse(AapTeardown.close(
            null, handshakeComplete = true, sendByeBye = { true },
            awaitByeByeResponse = unexpectedResponseWait,
        ).graceful)
    }
}
