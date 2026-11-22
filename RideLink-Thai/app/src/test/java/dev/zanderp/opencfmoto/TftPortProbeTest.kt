package dev.zanderp.opencfmoto

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class TftPortProbeTest {
    @Test fun connectsAndClosesWithoutSendingBytes() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 3000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<Int> {
                    server.accept().use { client ->
                        client.soTimeout = 3000
                        client.getInputStream().read()
                    }
                }
                assertEquals(TftPortProbe.Result.OPEN, TftPortProbe.probe(loopback, server.localPort) { Socket() })
                assertEquals(-1, received.get(4, TimeUnit.SECONDS).toInt())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun reportsTimeoutWithoutTreatingItAsClosed() {
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                throw SocketTimeoutException()
            }
        }
        assertEquals(
            TftPortProbe.Result.TIMEOUT,
            TftPortProbe.probe(InetAddress.getLoopbackAddress(), 23) { socket },
        )
    }
}
