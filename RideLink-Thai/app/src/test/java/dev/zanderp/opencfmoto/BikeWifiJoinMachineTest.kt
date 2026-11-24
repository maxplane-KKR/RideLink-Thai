package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeWifiJoinMachineTest {

    private val targetWithBssid = BikeWifiTarget(
        ssid = "A004DC0D30888555",
        psk = "12345678",
        hintedBssid = "dc:0d:30:08:85:55",
    )

    private val targetSsidOnly = BikeWifiTarget(
        ssid = "A004DC0D30888555",
        psk = "12345678",
        hintedBssid = null,
    )

    @Test
    fun readySystemNetworkAdoptedImmediatelyWithoutSpecifierAndDeduplicated() {
        val machine = BikeWifiJoinMachine<String>()
        val effects = machine.start(
            targetWithBssid,
            activeSystemNetwork = "sys-net" to BikeLinkSnapshot(
                hasIpv4 = true,
                hasGatewayOrSubnet = true,
                observedBssid = "dc:0d:30:08:85:55",
            ),
        )

        assertTrue(machine.active)
        assertEquals("sys-net", machine.currentNetwork)
        assertNull(machine.associatedNetwork)
        assertTrue(machine.everDelivered)
        assertFalse(machine.specifierRequestInFlight)
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.DeliverReadyNetwork(
                    network = "sys-net",
                    viaSystem = true,
                    reacquired = false,
                    observedBssid = "dc:0d:30:08:85:55",
                ),
            ),
            effects,
        )

        // Duplicate system callback for the already-adopted network must be a no-op.
        val dup = machine.onNetworkAvailable(
            network = "sys-net",
            viaSystem = true,
            snapshot = BikeLinkSnapshot(hasIpv4 = true, hasGatewayOrSubnet = true),
        )
        assertTrue(dup.isEmpty())
    }

    @Test
    fun unreadySystemNetworkOnStartStillStartsSpecifierAndDefersUntilDhcp() {
        val machine = BikeWifiJoinMachine<String>()
        val startEffects = machine.start(
            targetSsidOnly,
            activeSystemNetwork = "sys-net" to BikeLinkSnapshot(hasIpv4 = false, hasGatewayOrSubnet = false),
        )

        assertTrue(machine.specifierRequestInFlight)
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.RequestSpecifier(
                    ssid = targetSsidOnly.ssid,
                    psk = targetSsidOnly.psk,
                    bssid = null,
                    timeoutMs = BikeWifiJoinMachine.FIRST_JOIN_TIMEOUT_MS,
                    attempt = 1,
                ),
            ),
            startEffects,
        )

        // Specifier callback fires before DHCP completes -> WaitForDhcp.
        val availEffects = machine.onNetworkAvailable(
            network = "spec-net",
            viaSystem = false,
            snapshot = BikeLinkSnapshot(hasIpv4 = false, hasGatewayOrSubnet = false),
        )
        assertNull(machine.currentNetwork)
        assertEquals("spec-net", machine.associatedNetwork)
        assertFalse(machine.everDelivered)
        assertEquals(listOf(BikeWifiJoinMachine.Effect.WaitForDhcp("spec-net")), availEffects)

        // Intermediate linkProperties change without gateway/subnet stays deferred.
        val stillNoGateway = machine.onLinkPropertiesChanged(
            network = "spec-net",
            viaSystem = false,
            snapshot = BikeLinkSnapshot(hasIpv4 = true, hasGatewayOrSubnet = false),
        )
        assertTrue(stillNoGateway.isEmpty())
        assertNull(machine.currentNetwork)

        // Unrelated network linkProperties change is ignored.
        val unrelated = machine.onLinkPropertiesChanged(
            network = "other-net",
            viaSystem = false,
            snapshot = BikeLinkSnapshot(hasIpv4 = true, hasGatewayOrSubnet = true),
        )
        assertTrue(unrelated.isEmpty())

        // DHCP completes -> promote to currentNetwork without emitting CancelSpecifier.
        val promoted = machine.onLinkPropertiesChanged(
            network = "spec-net",
            viaSystem = false,
            snapshot = BikeLinkSnapshot(
                hasIpv4 = true,
                hasGatewayOrSubnet = true,
                observedBssid = "dc:0d:30:08:85:55",
            ),
        )
        assertEquals("spec-net", machine.currentNetwork)
        assertNull(machine.associatedNetwork)
        assertTrue(machine.everDelivered)
        assertFalse(machine.specifierRequestInFlight)
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.CancelRetry,
                BikeWifiJoinMachine.Effect.DeliverReadyNetwork(
                    network = "spec-net",
                    viaSystem = false,
                    reacquired = false,
                    observedBssid = "dc:0d:30:08:85:55",
                ),
            ),
            promoted,
        )
    }

    @Test
    fun hintedBssidFallsBackToSsidOnlyForeverAfterUnavailable() {
        val machine = BikeWifiJoinMachine<String>()
        val start = machine.start(targetWithBssid)
        assertEquals(
            BikeWifiJoinMachine.Effect.RequestSpecifier(
                ssid = targetWithBssid.ssid,
                psk = targetWithBssid.psk,
                bssid = "dc:0d:30:08:85:55",
                timeoutMs = BikeWifiJoinMachine.FIRST_JOIN_TIMEOUT_MS,
                attempt = 1,
            ),
            start.single(),
        )

        val firstUnavailable = machine.onRequestUnavailable()
        assertFalse(machine.specifierRequestInFlight)
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.PublishUi(
                    Phase.WAITING_FOR_BIKE,
                    R.string.conn_detail_wifi_searching,
                    attempt = 1,
                ),
                BikeWifiJoinMachine.Effect.ScheduleRetry(BikeWifiJoinMachine.INITIAL_RETRY_MS),
            ),
            firstUnavailable,
        )

        val retry2 = machine.onRetryTimerFired()
        assertTrue(machine.specifierRequestInFlight)
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.RequestSpecifier(
                    ssid = targetWithBssid.ssid,
                    psk = targetWithBssid.psk,
                    bssid = null,
                    timeoutMs = BikeWifiJoinMachine.FIRST_JOIN_TIMEOUT_MS,
                    attempt = 2,
                ),
            ),
            retry2,
        )

        // Even after a later delivery and network drop, retries remain SSID-only.
        machine.onNetworkAvailable(
            network = "net-1",
            viaSystem = false,
            snapshot = BikeLinkSnapshot(hasIpv4 = true, hasGatewayOrSubnet = true),
        )
        machine.onNetworkLost("net-1")
        val rejoinRetry = machine.onRetryTimerFired()
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.RequestSpecifier(
                    ssid = targetWithBssid.ssid,
                    psk = targetWithBssid.psk,
                    bssid = null,
                    timeoutMs = BikeWifiJoinMachine.REJOIN_TIMEOUT_MS,
                    attempt = 3,
                ),
            ),
            rejoinRetry,
        )
    }

    @Test
    fun twoWaitingRetriesBeforeErrorAndBackedOffSchedule() {
        val machine = BikeWifiJoinMachine<String>()
        machine.start(targetSsidOnly)

        val u1 = machine.onRequestUnavailable()
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.PublishUi(
                    Phase.WAITING_FOR_BIKE,
                    R.string.conn_detail_wifi_searching,
                    attempt = 1,
                ),
                BikeWifiJoinMachine.Effect.ScheduleRetry(BikeWifiJoinMachine.INITIAL_RETRY_MS),
            ),
            u1,
        )

        machine.onRetryTimerFired()
        val u2 = machine.onRequestUnavailable()
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.PublishUi(
                    Phase.WAITING_FOR_BIKE,
                    R.string.conn_detail_wifi_searching,
                    attempt = 2,
                ),
                BikeWifiJoinMachine.Effect.ScheduleRetry(BikeWifiJoinMachine.INITIAL_RETRY_SECOND_MS),
            ),
            u2,
        )

        machine.onRetryTimerFired()
        val u3 = machine.onRequestUnavailable()
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.PublishUi(
                    Phase.ERROR,
                    R.string.conn_detail_wifi_picker,
                    attempt = null,
                ),
                BikeWifiJoinMachine.Effect.ScheduleRetry(BikeWifiJoinMachine.REJOIN_BASE_MS * 3),
            ),
            u3,
        )

        // Caps at REJOIN_MAX_MS as unavailability continues.
        repeat(4) {
            machine.onRetryTimerFired()
            machine.onRequestUnavailable()
        }
        machine.onRetryTimerFired()
        val capped = machine.onRequestUnavailable()
        assertEquals(
            BikeWifiJoinMachine.Effect.ScheduleRetry(BikeWifiJoinMachine.REJOIN_MAX_MS),
            capped[1],
        )
    }

    @Test
    fun staleLossIgnoredAndFastRejoinThenBackoffWorksAcrossDeliveries() {
        val machine = BikeWifiJoinMachine<String>()
        assertTrue(machine.onNetworkLost("net-1").isEmpty())

        machine.start(targetSsidOnly)
        machine.onNetworkAvailable(
            network = "net-1",
            viaSystem = false,
            snapshot = BikeLinkSnapshot(hasIpv4 = true, hasGatewayOrSubnet = true),
        )

        // Unknown / stale network loss does not disrupt live link.
        assertTrue(machine.onNetworkLost("stale-net").isEmpty())
        assertEquals("net-1", machine.currentNetwork)

        // First drop after delivery schedules REJOIN_FAST_MS (300ms).
        val firstDrop = machine.onNetworkLost("net-1")
        assertNull(machine.currentNetwork)
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.NotifyNetworkLost,
                BikeWifiJoinMachine.Effect.ScheduleRetry(BikeWifiJoinMachine.REJOIN_FAST_MS),
            ),
            firstDrop,
        )

        // Rejoin attempt times out -> RECONNECTING + REJOIN_BASE_MS backoff.
        val req2 = machine.onRetryTimerFired()
        assertEquals(BikeWifiJoinMachine.REJOIN_TIMEOUT_MS, (req2.single() as BikeWifiJoinMachine.Effect.RequestSpecifier).timeoutMs)

        val unavail1 = machine.onRequestUnavailable()
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.PublishUi(Phase.RECONNECTING, R.string.conn_joining_wifi),
                BikeWifiJoinMachine.Effect.ScheduleRetry(BikeWifiJoinMachine.REJOIN_BASE_MS),
            ),
            unavail1,
        )

        machine.onRetryTimerFired()
        val unavail2 = machine.onRequestUnavailable()
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.PublishUi(Phase.RECONNECTING, R.string.conn_joining_wifi),
                BikeWifiJoinMachine.Effect.ScheduleRetry(BikeWifiJoinMachine.REJOIN_BASE_MS * 2),
            ),
            unavail2,
        )

        // Successful re-acquire sets reacquired = true and resets fast-rejoin budget.
        val reacquired = machine.onNetworkAvailable(
            network = "net-2",
            viaSystem = false,
            snapshot = BikeLinkSnapshot(hasIpv4 = true, hasGatewayOrSubnet = true),
        )
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.CancelRetry,
                BikeWifiJoinMachine.Effect.DeliverReadyNetwork(
                    network = "net-2",
                    viaSystem = false,
                    reacquired = true,
                    observedBssid = null,
                ),
            ),
            reacquired,
        )

        val nextFastDrop = machine.onNetworkLost("net-2")
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.NotifyNetworkLost,
                BikeWifiJoinMachine.Effect.ScheduleRetry(BikeWifiJoinMachine.REJOIN_FAST_MS),
            ),
            nextFastDrop,
        )
    }

    @Test
    fun associatedNetworkLostBeforeDeliveryClearsStateWithoutNotifyNetworkLost() {
        val machine = BikeWifiJoinMachine<String>()
        machine.start(targetSsidOnly)

        machine.onNetworkAvailable(
            network = "assoc-net",
            viaSystem = false,
            snapshot = BikeLinkSnapshot(hasIpv4 = false, hasGatewayOrSubnet = false),
        )
        assertEquals("assoc-net", machine.associatedNetwork)

        // Unavailable must be ignored while associatedNetwork is awaiting DHCP.
        assertTrue(machine.onRequestUnavailable().isEmpty())

        val lostBeforeDelivery = machine.onNetworkLost("assoc-net")
        assertNull(machine.associatedNetwork)
        assertFalse(machine.specifierRequestInFlight)
        assertEquals(
            listOf(BikeWifiJoinMachine.Effect.ScheduleRetry(BikeWifiJoinMachine.INITIAL_RETRY_MS)),
            lostBeforeDelivery,
        )
    }

    @Test
    fun systemNetworkCancelsPendingSpecifierImmediatelyOrAfterDhcp() {
        val immediate = BikeWifiJoinMachine<String>()
        immediate.start(targetSsidOnly)
        assertTrue(immediate.specifierRequestInFlight)

        val sysEffects = immediate.onNetworkAvailable(
            network = "sys-net",
            viaSystem = true,
            snapshot = BikeLinkSnapshot(
                hasIpv4 = true,
                hasGatewayOrSubnet = true,
                observedBssid = "dc:0d:30:08:85:55",
            ),
        )
        assertFalse(immediate.specifierRequestInFlight)
        assertEquals("sys-net", immediate.currentNetwork)
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.CancelRetry,
                BikeWifiJoinMachine.Effect.CancelSpecifier,
                BikeWifiJoinMachine.Effect.DeliverReadyNetwork(
                    network = "sys-net",
                    viaSystem = true,
                    reacquired = false,
                    observedBssid = "dc:0d:30:08:85:55",
                ),
            ),
            sysEffects,
        )

        // Also when system network arrives unready first and completes DHCP via onLinkPropertiesChanged.
        val delayed = BikeWifiJoinMachine<String>()
        delayed.start(targetSsidOnly)
        assertEquals(
            listOf(BikeWifiJoinMachine.Effect.WaitForDhcp("sys-net")),
            delayed.onNetworkAvailable(
                network = "sys-net",
                viaSystem = true,
                snapshot = BikeLinkSnapshot(hasIpv4 = false, hasGatewayOrSubnet = false),
            ),
        )
        assertTrue(delayed.specifierRequestInFlight)

        val promoted = delayed.onLinkPropertiesChanged(
            network = "sys-net",
            viaSystem = true,
            snapshot = BikeLinkSnapshot(hasIpv4 = true, hasGatewayOrSubnet = true),
        )
        assertFalse(delayed.specifierRequestInFlight)
        assertEquals(
            listOf(
                BikeWifiJoinMachine.Effect.CancelRetry,
                BikeWifiJoinMachine.Effect.CancelSpecifier,
                BikeWifiJoinMachine.Effect.DeliverReadyNetwork(
                    network = "sys-net",
                    viaSystem = true,
                    reacquired = false,
                    observedBssid = null,
                ),
            ),
            promoted,
        )
    }

    @Test
    fun stopClearsAllStateAndCancelsSpecifierOnlyWhenInFlight() {
        val withSpecifier = BikeWifiJoinMachine<String>()
        withSpecifier.start(targetWithBssid)
        assertEquals(
            listOf(BikeWifiJoinMachine.Effect.CancelRetry, BikeWifiJoinMachine.Effect.CancelSpecifier),
            withSpecifier.stop(),
        )
        assertFalse(withSpecifier.active)
        assertNull(withSpecifier.currentNetwork)
        assertNull(withSpecifier.associatedNetwork)
        assertFalse(withSpecifier.everDelivered)
        assertFalse(withSpecifier.specifierRequestInFlight)
        assertTrue(withSpecifier.onRequestUnavailable().isEmpty())
        assertTrue(withSpecifier.onRetryTimerFired().isEmpty())

        val adoptedSystem = BikeWifiJoinMachine<String>()
        adoptedSystem.start(
            targetSsidOnly,
            activeSystemNetwork = "sys-net" to BikeLinkSnapshot(hasIpv4 = true, hasGatewayOrSubnet = true),
        )
        assertEquals(listOf(BikeWifiJoinMachine.Effect.CancelRetry), adoptedSystem.stop())
        assertFalse(adoptedSystem.active)
    }

    @Test
    fun normalizeValidUnicastBssidRejectsSentinelsAndMulticast() {
        assertEquals("dc:0d:30:08:85:55", BikeMemory.normalizeValidUnicastBssid("DC:0D:30:08:85:55"))
        assertEquals("dc:0d:30:08:85:55", BikeMemory.normalizeValidUnicastBssid(" dc-0d-30-08-85-55 "))
        assertEquals("dc:0d:30:08:85:55", BikeMemory.normalizeValidUnicastBssid("DC0D30088555"))
        assertNull(BikeMemory.normalizeValidUnicastBssid(null))
        assertNull(BikeMemory.normalizeValidUnicastBssid("   "))
        assertNull(BikeMemory.normalizeValidUnicastBssid("00:00:00:00:00:00"))
        assertNull(BikeMemory.normalizeValidUnicastBssid("02:00:00:00:00:00"))
        assertNull(BikeMemory.normalizeValidUnicastBssid("ff:ff:ff:ff:ff:ff"))
        assertNull(BikeMemory.normalizeValidUnicastBssid("FF:FF:FF:FF:FF:FF"))
        assertNull(BikeMemory.normalizeValidUnicastBssid("01:00:5e:00:00:01"))
        assertNull(BikeMemory.normalizeValidUnicastBssid("33:33:00:00:00:01"))
        assertNull(BikeMemory.normalizeValidUnicastBssid("not-a-bssid"))
        assertNull(BikeMemory.normalizeValidUnicastBssid("dc:0d:30:08:85"))
    }
}
