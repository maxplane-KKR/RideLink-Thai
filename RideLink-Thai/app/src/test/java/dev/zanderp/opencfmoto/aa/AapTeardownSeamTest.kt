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

    @Test
    fun `sends ByeBye, flushes, then closes the connection`() {
        val conn = InMemoryAccessoryConnection()
        val slept = mutableListOf<Long>()
        val result = AapTeardown.close(
            connection = conn,
            handshakeComplete = true,
            sendByeBye = { conn.events += "byebye"; true },
            sleeper = { slept += it },
        )
        assertTrue(result.graceful)
        assertEquals(listOf("byebye", "disconnect"), conn.events)
        assertEquals(listOf(AapTeardown.BYEBYE_FLUSH_MS), slept)
    }

    @Test
    fun `skips ByeBye before handshake but still closes`() {
        val conn = InMemoryAccessoryConnection()
        val result = AapTeardown.close(conn, handshakeComplete = false, sendByeBye = { error("must not send") })
        assertFalse(result.graceful)
        assertEquals(listOf("disconnect"), conn.events)
    }

    @Test
    fun `a failing or throwing ByeBye never prevents the socket from closing`() {
        val conn = InMemoryAccessoryConnection()
        val result = AapTeardown.close(conn, handshakeComplete = true, sendByeBye = { throw IllegalStateException("peer gone") })
        assertFalse(result.graceful)
        assertEquals(listOf("disconnect"), conn.events)
    }

    @Test
    fun `already-closed peer is not written to`() {
        val conn = InMemoryAccessoryConnection(connected = false)
        val result = AapTeardown.close(conn, handshakeComplete = true, sendByeBye = { conn.events += "byebye"; true })
        assertFalse(result.graceful)
        assertEquals(listOf("disconnect"), conn.events)
    }

    @Test
    fun `null connection is a no-op`() {
        assertFalse(AapTeardown.close(null, handshakeComplete = true, sendByeBye = { true }).graceful)
    }
}
