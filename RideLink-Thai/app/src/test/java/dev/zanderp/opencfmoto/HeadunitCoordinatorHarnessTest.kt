package dev.zanderp.opencfmoto

import dev.zanderp.opencfmoto.ProjectionHarness.AaConnectAction
import dev.zanderp.opencfmoto.ProjectionHarness.AaConnectInput
import dev.zanderp.opencfmoto.ProjectionHarness.BtDeviceCandidate
import dev.zanderp.opencfmoto.ProjectionHarness.HeadunitLinkStatus
import dev.zanderp.opencfmoto.ProjectionHarness.UiLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the four root causes behind "ต้องกด หยุด/เชื่อมต่อ หลายครั้ง" and the Head Unit
 * Server (:5277) dying with `IllegalStateException: Already connected` on Gearhead 17.9.
 */
class HeadunitCoordinatorHarnessTest {

    private fun input(
        running: Boolean = true,
        sessionBusy: Boolean = false,
        teardownInProgress: Boolean = false,
        msSinceLastTeardown: Long = Long.MAX_VALUE,
        lastTeardownGraceful: Boolean = true,
        msSinceLastDial: Long = Long.MAX_VALUE,
        consecutiveRefusals: Int = 0,
        consecutiveHandshakeFailures: Int = 0,
        selfModeFallbackTriggered: Boolean = false,
        guidanceSurfaced: Boolean = false,
    ) = AaConnectInput(
        running = running,
        sessionBusy = sessionBusy,
        teardownInProgress = teardownInProgress,
        msSinceLastTeardown = msSinceLastTeardown,
        lastTeardownGraceful = lastTeardownGraceful,
        msSinceLastDial = msSinceLastDial,
        consecutiveRefusals = consecutiveRefusals,
        consecutiveHandshakeFailures = consecutiveHandshakeFailures,
        selfModeFallbackTriggered = selfModeFallbackTriggered,
        guidanceSurfaced = guidanceSurfaced,
    )

    // ---------------------------------------------------------------- connect coordinator

    @Test
    fun `fresh connect dials head unit server immediately`() {
        assertEquals(AaConnectAction.DIAL_HEADUNIT_5277, ProjectionHarness.evaluateAaConnectStep(input()).action)
    }

    @Test
    fun `stopped receiver stops the coordinator`() {
        assertEquals(AaConnectAction.STOP, ProjectionHarness.evaluateAaConnectStep(input(running = false)).action)
    }

    @Test
    fun `never dials 5277 while a previous teardown is still in flight`() {
        val j = ProjectionHarness.evaluateAaConnectStep(input(teardownInProgress = true))
        assertEquals(AaConnectAction.WAIT_SETTLE_COOLDOWN, j.action)
        assertTrue(j.delayMs > 0)
    }

    @Test
    fun `ungraceful drop waits the long settle window before redialling 5277`() {
        val j = ProjectionHarness.evaluateAaConnectStep(
            input(msSinceLastTeardown = 100L, lastTeardownGraceful = false)
        )
        assertEquals(AaConnectAction.WAIT_SETTLE_COOLDOWN, j.action)
        assertEquals(ProjectionHarness.SETTLE_AFTER_UNGRACEFUL_MS - 100L, j.delayMs)
    }

    @Test
    fun `graceful ByeBye teardown needs only the short settle window`() {
        assertTrue(ProjectionHarness.SETTLE_AFTER_BYEBYE_MS < ProjectionHarness.SETTLE_AFTER_UNGRACEFUL_MS)
        val waiting = ProjectionHarness.evaluateAaConnectStep(
            input(msSinceLastTeardown = 50L, lastTeardownGraceful = true)
        )
        assertEquals(AaConnectAction.WAIT_SETTLE_COOLDOWN, waiting.action)
        val ready = ProjectionHarness.evaluateAaConnectStep(
            input(msSinceLastTeardown = ProjectionHarness.SETTLE_AFTER_BYEBYE_MS, lastTeardownGraceful = true)
        )
        assertEquals(AaConnectAction.DIAL_HEADUNIT_5277, ready.action)
    }

    @Test
    fun `holds while a session is handshaking or live - never opens a second 5277 socket`() {
        assertEquals(
            AaConnectAction.HOLD_SESSION_ACTIVE,
            ProjectionHarness.evaluateAaConnectStep(input(sessionBusy = true)).action
        )
    }

    @Test
    fun `first refusal triggers 5288 self-mode fallback exactly once`() {
        val first = ProjectionHarness.evaluateAaConnectStep(input(consecutiveRefusals = 1, msSinceLastDial = 10L))
        assertEquals(AaConnectAction.TRIGGER_SELF_MODE_AND_RETRY_5277, first.action)
        val after = ProjectionHarness.evaluateAaConnectStep(
            input(consecutiveRefusals = 1, msSinceLastDial = 10L, selfModeFallbackTriggered = true)
        )
        assertEquals(AaConnectAction.WAIT_SETTLE_COOLDOWN, after.action)
    }

    @Test
    fun `failed handshake does NOT kill the dial loop - it backs off then redials`() {
        val backoff = ProjectionHarness.evaluateAaConnectStep(
            input(consecutiveHandshakeFailures = 1, msSinceLastDial = 100L, msSinceLastTeardown = 5_000L)
        )
        assertEquals(AaConnectAction.WAIT_SETTLE_COOLDOWN, backoff.action)
        val redial = ProjectionHarness.evaluateAaConnectStep(
            input(consecutiveHandshakeFailures = 1, msSinceLastDial = 60_000L, msSinceLastTeardown = 60_000L)
        )
        assertEquals(AaConnectAction.DIAL_HEADUNIT_5277, redial.action)
    }

    @Test
    fun `repeated handshake failures surface Thai guidance for a wedged head unit server`() {
        val j = ProjectionHarness.evaluateAaConnectStep(
            input(
                consecutiveHandshakeFailures = ProjectionHarness.HANDSHAKE_FAILURES_BEFORE_GUIDANCE,
                selfModeFallbackTriggered = true,
            )
        )
        assertEquals(AaConnectAction.SURFACE_HEADUNIT_ACTION_NEEDED, j.action)
        assertTrue(j.reasonTh.contains(":5277"))
        // once surfaced, the coordinator keeps redialling instead of surfacing forever
        val next = ProjectionHarness.evaluateAaConnectStep(
            input(
                consecutiveHandshakeFailures = ProjectionHarness.HANDSHAKE_FAILURES_BEFORE_GUIDANCE,
                selfModeFallbackTriggered = true,
                guidanceSurfaced = true,
            )
        )
        assertFalse(next.action == AaConnectAction.SURFACE_HEADUNIT_ACTION_NEEDED)
    }

    @Test
    fun `handshake backoff is bounded`() {
        assertEquals(
            ProjectionHarness.MAX_RETRY_BACKOFF_MS,
            ProjectionHarness.retryIntervalMs(consecutiveRefusals = 0, consecutiveHandshakeFailures = 50)
        )
        assertEquals(0L, ProjectionHarness.retryIntervalMs(0, 0))
    }

    // ---------------------------------------------------------------- server status UI (zero socket probes)

    @Test
    fun `server status never claims live before video or handshake`() {
        val connecting = ProjectionHarness.evaluateServerStatusUi(
            sessionRunning = true, handshakeLive = false, decoding = false, fps = 0,
            headunit = HeadunitLinkStatus.UNKNOWN,
        )
        assertEquals(UiLevel.BUSY, connecting.level)
        val live = ProjectionHarness.evaluateServerStatusUi(
            sessionRunning = true, handshakeLive = true, decoding = true, fps = 30,
            headunit = HeadunitLinkStatus.CONNECTED,
        )
        assertEquals(UiLevel.OK, live.level)
    }

    @Test
    fun `server status explains refused and wedged head unit server`() {
        val refused = ProjectionHarness.evaluateServerStatusUi(
            sessionRunning = true, handshakeLive = false, decoding = false, fps = 0,
            headunit = HeadunitLinkStatus.REFUSED,
        )
        assertEquals(UiLevel.WARN, refused.level)
        assertTrue(refused.text.contains(":5277"))
        val wedged = ProjectionHarness.evaluateServerStatusUi(
            sessionRunning = true, handshakeLive = false, decoding = false, fps = 0,
            headunit = HeadunitLinkStatus.HANDSHAKE_FAILED,
        )
        assertEquals(UiLevel.ERROR, wedged.level)
        val idle = ProjectionHarness.evaluateServerStatusUi(
            sessionRunning = false, handshakeLive = false, decoding = false, fps = 0,
            headunit = HeadunitLinkStatus.UNKNOWN,
        )
        assertEquals(UiLevel.NEUTRAL, idle.level)
    }

    // ---------------------------------------------------------------- bike Bluetooth MAC selection

    @Test
    fun `never sends a smartwatch or earbud MAC to START_WIRELESS_PROJECTION`() {
        val mac = ProjectionHarness.selectBikeBluetoothMac(
            listOf(
                BtDeviceCandidate("AA:00:00:00:00:01", "Galaxy Watch6", connected = true),
                BtDeviceCandidate("AA:00:00:00:00:02", "Redmi Buds 5", connected = true),
                BtDeviceCandidate("AA:00:00:00:00:03", "Car Kit", connected = false),
            ),
            savedSsids = listOf("A004DC0D30888555"),
        )
        assertNull(mac)
    }

    @Test
    fun `prefers the connected bike cluster then a bonded bike cluster`() {
        val connectedBike = ProjectionHarness.selectBikeBluetoothMac(
            listOf(
                BtDeviceCandidate("AA:00:00:00:00:01", "Galaxy Watch6", connected = true),
                BtDeviceCandidate("AA:00:00:00:00:04", "ZT-350D", connected = false),
                BtDeviceCandidate("AA:00:00:00:00:05", "A004DC0D30888555", connected = true),
            ),
            savedSsids = listOf("A004DC0D30888555"),
        )
        assertEquals("AA:00:00:00:00:05", connectedBike)

        val bondedBike = ProjectionHarness.selectBikeBluetoothMac(
            listOf(
                BtDeviceCandidate("AA:00:00:00:00:01", "Galaxy Watch6", connected = true),
                BtDeviceCandidate("AA:00:00:00:00:04", "ZT-350D", connected = false),
            ),
            savedSsids = emptyList(),
        )
        assertEquals("AA:00:00:00:00:04", bondedBike)
    }

    @Test
    fun `bike name matching rejects accessories even if they contain bike-ish words`() {
        assertTrue(ProjectionHarness.isBikeDeviceName("Zontes 350D", emptyList()))
        assertTrue(ProjectionHarness.isBikeDeviceName("Carbit-8555", listOf("A004DC0D30888555")))
        assertFalse(ProjectionHarness.isBikeDeviceName("Moto Buds+", emptyList()))
        assertFalse(ProjectionHarness.isBikeDeviceName("Bike Watch 150", emptyList()))
        assertFalse(ProjectionHarness.isBikeDeviceName(null, emptyList()))
    }
}
