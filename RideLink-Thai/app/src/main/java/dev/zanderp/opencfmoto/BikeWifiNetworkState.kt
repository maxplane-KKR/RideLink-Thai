package dev.zanderp.opencfmoto

/** รวมเหตุการณ์จากตัวติดตาม Wi-Fi หลายตัวให้เหลือสถานะเครือข่ายเดียว */
internal class BikeWifiNetworkState<T : Any> {
    enum class Available { FIRST, REACQUIRED, UNCHANGED }

    @Volatile var current: T? = null
        private set
    @Volatile var everDelivered = false
        private set

    @Synchronized fun available(network: T): Available {
        if (current == network) return Available.UNCHANGED
        current = network
        val result = if (everDelivered) Available.REACQUIRED else Available.FIRST
        everDelivered = true
        return result
    }

    @Synchronized fun lost(network: T): Boolean {
        if (current != network) return false
        current = null
        return true
    }

    @Synchronized fun reset() {
        current = null
        everDelivered = false
    }
}
