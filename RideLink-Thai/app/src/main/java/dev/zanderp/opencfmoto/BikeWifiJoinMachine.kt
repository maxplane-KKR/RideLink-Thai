package dev.zanderp.opencfmoto

internal data class BikeWifiTarget(
    val ssid: String,
    val psk: String,
    val hintedBssid: String? = null,
)

internal data class BikeLinkSnapshot(
    val hasIpv4: Boolean,
    val hasGatewayOrSubnet: Boolean,
    val observedBssid: String? = null,
) {
    val isReadyForPxc: Boolean get() = hasIpv4 && hasGatewayOrSubnet
}

/**
 * Pure state machine governing bike SoftAP / system Wi-Fi acquisition, DHCP readiness gating,
 * observed-BSSID fallback to SSID-only, and retry/backoff timing without Android framework types.
 */
internal class BikeWifiJoinMachine<T : Any> {

    sealed interface Effect<out N> {
        data class RequestSpecifier(
            val ssid: String,
            val psk: String,
            val bssid: String?,
            val timeoutMs: Int,
            val attempt: Int,
        ) : Effect<Nothing>

        data object CancelSpecifier : Effect<Nothing>

        data class DeliverReadyNetwork<out N>(
            val network: N,
            val viaSystem: Boolean,
            val reacquired: Boolean,
            val observedBssid: String?,
        ) : Effect<N>

        data class WaitForDhcp<out N>(val network: N) : Effect<N>

        data object NotifyNetworkLost : Effect<Nothing>

        data class ScheduleRetry(val delayMs: Long) : Effect<Nothing>

        data object CancelRetry : Effect<Nothing>

        data class PublishUi(
            val phase: Phase,
            val detailRes: Int,
            val attempt: Int? = null,
        ) : Effect<Nothing>
    }

    companion object {
        const val FIRST_JOIN_TIMEOUT_MS = 25_000
        const val REJOIN_TIMEOUT_MS = 20_000
        const val REJOIN_FAST_MS = 300L
        const val INITIAL_RETRY_MS = 2_500L
        const val INITIAL_RETRY_SECOND_MS = 3_500L
        const val REJOIN_BASE_MS = 2_500L
        const val REJOIN_MAX_MS = 15_000L
        const val UNAVAILABLE_BEFORE_ERROR = 3
    }

    @Volatile var currentNetwork: T? = null
        private set
    @Volatile var associatedNetwork: T? = null
        private set
    @Volatile var everDelivered: Boolean = false
        private set
    @Volatile var active: Boolean = false
        private set
    @Volatile var specifierRequestInFlight: Boolean = false
        private set

    private var target: BikeWifiTarget? = null
    private var associatedViaSystem: Boolean = false
    private var attempt: Int = 0
    private var consecutiveUnavailable: Int = 0
    private var rejoinDropsSinceDelivery: Int = 0
    private var lastRequestedBssid: String? = null
    private var bssidFallbackDisabled: Boolean = false

    @Synchronized
    fun start(
        target: BikeWifiTarget,
        activeSystemNetwork: Pair<T, BikeLinkSnapshot>? = null,
    ): List<Effect<T>> {
        resetInternalState()
        this.target = target
        active = true

        if (activeSystemNetwork != null && activeSystemNetwork.second.isReadyForPxc) {
            val (net, snapshot) = activeSystemNetwork
            currentNetwork = net
            everDelivered = true
            return listOf(
                Effect.DeliverReadyNetwork(
                    network = net,
                    viaSystem = true,
                    reacquired = false,
                    observedBssid = snapshot.observedBssid,
                ),
            )
        }

        specifierRequestInFlight = true
        attempt = 1
        lastRequestedBssid = target.hintedBssid
        return listOf(
            Effect.RequestSpecifier(
                ssid = target.ssid,
                psk = target.psk,
                bssid = target.hintedBssid,
                timeoutMs = FIRST_JOIN_TIMEOUT_MS,
                attempt = 1,
            ),
        )
    }

    @Synchronized
    fun onNetworkAvailable(
        network: T,
        viaSystem: Boolean,
        snapshot: BikeLinkSnapshot,
    ): List<Effect<T>> {
        if (!active) return emptyList()
        if (viaSystem && currentNetwork == network) return emptyList()
        if (currentNetwork == network) return emptyList()

        if (!snapshot.isReadyForPxc) {
            if (currentNetwork == null || !viaSystem) {
                associatedNetwork = network
                associatedViaSystem = viaSystem
                return listOf(Effect.WaitForDhcp(network))
            }
            return emptyList()
        }

        return promoteReadyNetwork(network, viaSystem, snapshot)
    }

    @Synchronized
    fun onLinkPropertiesChanged(
        network: T,
        viaSystem: Boolean,
        snapshot: BikeLinkSnapshot,
    ): List<Effect<T>> {
        if (!active) return emptyList()
        val relevant = network == associatedNetwork ||
            network == currentNetwork ||
            (viaSystem && currentNetwork == null)
        if (!relevant) return emptyList()
        if (currentNetwork != network && snapshot.isReadyForPxc) {
            return promoteReadyNetwork(network, viaSystem, snapshot)
        }
        return emptyList()
    }

    @Synchronized
    fun onNetworkLost(network: T): List<Effect<T>> {
        if (!active) return emptyList()
        val lostCurrent = network == currentNetwork
        val lostAssociated = network == associatedNetwork
        if (!lostCurrent && !lostAssociated) return emptyList()

        if (lostCurrent) {
            currentNetwork = null
        }
        if (lostAssociated) {
            if (!associatedViaSystem) {
                specifierRequestInFlight = false
            }
            associatedNetwork = null
            associatedViaSystem = false
        }

        val effects = ArrayList<Effect<T>>(2)
        if (lostCurrent) {
            effects.add(Effect.NotifyNetworkLost)
        }
        if (currentNetwork == null && associatedNetwork == null) {
            rejoinDropsSinceDelivery++
            val delayMs = when {
                !everDelivered -> if (rejoinDropsSinceDelivery <= 1) INITIAL_RETRY_MS else INITIAL_RETRY_SECOND_MS
                rejoinDropsSinceDelivery == 1 -> REJOIN_FAST_MS
                else -> minOf(REJOIN_BASE_MS * (rejoinDropsSinceDelivery - 1), REJOIN_MAX_MS)
            }
            effects.add(Effect.ScheduleRetry(delayMs))
        }
        return effects
    }

    @Synchronized
    fun onRequestUnavailable(): List<Effect<T>> {
        if (!active || currentNetwork != null || associatedNetwork != null) return emptyList()
        specifierRequestInFlight = false
        consecutiveUnavailable++
        rejoinDropsSinceDelivery++
        if (lastRequestedBssid != null) {
            bssidFallbackDisabled = true
        }

        return when {
            !everDelivered && consecutiveUnavailable < UNAVAILABLE_BEFORE_ERROR -> {
                val delayMs = if (consecutiveUnavailable == 1) INITIAL_RETRY_MS else INITIAL_RETRY_SECOND_MS
                listOf(
                    Effect.PublishUi(Phase.WAITING_FOR_BIKE, R.string.conn_detail_wifi_searching, consecutiveUnavailable),
                    Effect.ScheduleRetry(delayMs),
                )
            }
            !everDelivered -> {
                val delayMs = minOf(REJOIN_BASE_MS * consecutiveUnavailable, REJOIN_MAX_MS)
                listOf(
                    Effect.PublishUi(Phase.ERROR, R.string.conn_detail_wifi_picker),
                    Effect.ScheduleRetry(delayMs),
                )
            }
            else -> {
                val delayMs = minOf(REJOIN_BASE_MS * consecutiveUnavailable, REJOIN_MAX_MS)
                listOf(
                    Effect.PublishUi(Phase.RECONNECTING, R.string.conn_joining_wifi),
                    Effect.ScheduleRetry(delayMs),
                )
            }
        }
    }

    @Synchronized
    fun onRetryTimerFired(): List<Effect<T>> {
        if (!active || currentNetwork != null) return emptyList()
        val tgt = target ?: return emptyList()
        attempt++
        val bssid = if (bssidFallbackDisabled) null else tgt.hintedBssid
        val timeoutMs = if (everDelivered) REJOIN_TIMEOUT_MS else FIRST_JOIN_TIMEOUT_MS
        specifierRequestInFlight = true
        lastRequestedBssid = bssid
        return listOf(
            Effect.RequestSpecifier(
                ssid = tgt.ssid,
                psk = tgt.psk,
                bssid = bssid,
                timeoutMs = timeoutMs,
                attempt = attempt,
            ),
        )
    }

    @Synchronized
    fun stop(): List<Effect<T>> {
        val wasInFlight = specifierRequestInFlight
        resetInternalState()
        return buildList {
            add(Effect.CancelRetry)
            if (wasInFlight) add(Effect.CancelSpecifier)
        }
    }

    private fun promoteReadyNetwork(
        network: T,
        viaSystem: Boolean,
        snapshot: BikeLinkSnapshot,
    ): List<Effect<T>> {
        val reacquired = everDelivered
        currentNetwork = network
        associatedNetwork = null
        associatedViaSystem = false
        consecutiveUnavailable = 0
        rejoinDropsSinceDelivery = 0

        val effects = ArrayList<Effect<T>>(3)
        effects.add(Effect.CancelRetry)
        if (viaSystem && specifierRequestInFlight) {
            specifierRequestInFlight = false
            effects.add(Effect.CancelSpecifier)
        } else if (!viaSystem) {
            specifierRequestInFlight = false
        }
        everDelivered = true
        effects.add(
            Effect.DeliverReadyNetwork(
                network = network,
                viaSystem = viaSystem,
                reacquired = reacquired,
                observedBssid = snapshot.observedBssid,
            ),
        )
        return effects
    }

    private fun resetInternalState() {
        target = null
        currentNetwork = null
        associatedNetwork = null
        associatedViaSystem = false
        everDelivered = false
        active = false
        specifierRequestInFlight = false
        attempt = 0
        consecutiveUnavailable = 0
        rejoinDropsSinceDelivery = 0
        lastRequestedBssid = null
        bssidFallbackDisabled = false
    }
}
