package dev.zanderp.opencfmoto

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** TCP reachability only. A successful connection does not establish protocol compatibility. */
internal object TftPortProbe {
    enum class Result { OPEN, REFUSED, TIMEOUT, NETWORK_ERROR, NO_BIKE_NETWORK, CANCELLED }

    fun probe(peer: InetAddress, port: Int, socketFactory: () -> Socket): Result = try {
        socketFactory().use { it.connect(InetSocketAddress(peer, port), 1500) }
        Result.OPEN
    } catch (error: ConnectException) {
        val message = error.message.orEmpty()
        if (message.contains("ECONNREFUSED", ignoreCase = true) ||
            message.contains("connection refused", ignoreCase = true)
        ) Result.REFUSED else Result.NETWORK_ERROR
    } catch (_: SocketTimeoutException) {
        Result.TIMEOUT
    } catch (_: IOException) {
        Result.NETWORK_ERROR
    } catch (_: SecurityException) {
        Result.NETWORK_ERROR
    }
}
