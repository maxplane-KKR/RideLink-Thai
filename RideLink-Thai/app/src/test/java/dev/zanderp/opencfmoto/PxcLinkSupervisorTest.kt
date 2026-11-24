package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PxcLinkSupervisorTest {

    private fun snapshot(
        running: Boolean = true,
        inErrorState: Boolean = false,
        probed: Boolean = false,
        liveConns: Int = 0,
        framesSent: Int = 0,
        aaDecoding: Boolean = true,
        msSinceProbeAccepted: Long = 0L,
        msSinceFirstConnect: Long = 0L,
        msSinceLastFrame: Long = Long.MAX_VALUE,
        msSinceLastRecovery: Long = Long.MAX_VALUE,
    ) = PxcLinkSupervisor.HealthSnapshot(
        running = running,
        inErrorState = inErrorState,
        probed = probed,
        liveConns = liveConns,
        framesSent = framesSent,
        aaDecoding = aaDecoding,
        msSinceProbeAccepted = msSinceProbeAccepted,
        msSinceFirstConnect = msSinceFirstConnect,
        msSinceLastFrame = msSinceLastFrame,
        msSinceLastRecovery = msSinceLastRecovery,
    )

    @Test
    fun `not running returns NONE regardless of error or stall state`() {
        assertEquals(
            PxcLinkSupervisor.Action.NONE,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    running = false,
                    inErrorState = true,
                    probed = true,
                    liveConns = 2,
                    framesSent = 10,
                    msSinceProbeAccepted = 60_000L,
                    msSinceFirstConnect = 60_000L,
                    msSinceLastFrame = 60_000L,
                    msSinceLastRecovery = 60_000L,
                ),
            ),
        )
    }

    @Test
    fun `error state rearms only after ERROR_REARM_MS and suppresses dialback reprobe`() {
        assertEquals(
            PxcLinkSupervisor.Action.NONE,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    inErrorState = true,
                    probed = true,
                    liveConns = 0,
                    msSinceProbeAccepted = 10_000L,
                    msSinceLastRecovery = PxcLinkSupervisor.ERROR_REARM_MS - 1L,
                ),
            ),
        )
        assertEquals(
            PxcLinkSupervisor.Action.REARM_FROM_ERROR,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    inErrorState = true,
                    probed = false,
                    liveConns = 0,
                    msSinceLastRecovery = PxcLinkSupervisor.ERROR_REARM_MS,
                ),
            ),
        )
    }

    @Test
    fun `dialback timeout triggers REPROBE_DIALBACK_TIMEOUT after DIALBACK_TIMEOUT_MS and cooldown`() {
        assertEquals(
            PxcLinkSupervisor.Action.NONE,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    probed = true,
                    liveConns = 0,
                    msSinceProbeAccepted = PxcLinkSupervisor.DIALBACK_TIMEOUT_MS - 1L,
                    msSinceLastRecovery = Long.MAX_VALUE,
                ),
            ),
        )
        assertEquals(
            PxcLinkSupervisor.Action.NONE,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    probed = true,
                    liveConns = 0,
                    msSinceProbeAccepted = PxcLinkSupervisor.DIALBACK_TIMEOUT_MS,
                    msSinceLastRecovery = PxcLinkSupervisor.DIALBACK_TIMEOUT_MS - 1L,
                ),
            ),
        )
        assertEquals(
            PxcLinkSupervisor.Action.NONE,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    probed = false,
                    liveConns = 0,
                    msSinceProbeAccepted = 10_000L,
                    msSinceLastRecovery = Long.MAX_VALUE,
                ),
            ),
        )
        assertEquals(
            PxcLinkSupervisor.Action.REPROBE_DIALBACK_TIMEOUT,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    probed = true,
                    liveConns = 0,
                    msSinceProbeAccepted = PxcLinkSupervisor.DIALBACK_TIMEOUT_MS,
                    msSinceLastRecovery = PxcLinkSupervisor.DIALBACK_TIMEOUT_MS,
                ),
            ),
        )
    }

    @Test
    fun `first frame stall triggers FORCE_RECONNECT_FIRST_FRAME_STALL after FIRST_FRAME_STALL_MS and cooldown`() {
        assertEquals(
            PxcLinkSupervisor.Action.NONE,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    probed = true,
                    liveConns = 1,
                    framesSent = 0,
                    msSinceFirstConnect = PxcLinkSupervisor.FIRST_FRAME_STALL_MS - 1L,
                    msSinceLastRecovery = Long.MAX_VALUE,
                ),
            ),
        )
        assertEquals(
            PxcLinkSupervisor.Action.NONE,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    probed = true,
                    liveConns = 1,
                    framesSent = 0,
                    msSinceFirstConnect = PxcLinkSupervisor.FIRST_FRAME_STALL_MS,
                    msSinceLastRecovery = PxcLinkSupervisor.FIRST_FRAME_STALL_MS - 1L,
                ),
            ),
        )
        assertEquals(
            PxcLinkSupervisor.Action.FORCE_RECONNECT_FIRST_FRAME_STALL,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    probed = true,
                    liveConns = 2,
                    framesSent = 0,
                    msSinceFirstConnect = PxcLinkSupervisor.FIRST_FRAME_STALL_MS,
                    msSinceLastRecovery = PxcLinkSupervisor.FIRST_FRAME_STALL_MS,
                ),
            ),
        )
    }

    @Test
    fun `stream stall requires framesSent aaDecoding STREAM_STALL_MS and RECOVERY_COOLDOWN_MS`() {
        assertEquals(
            PxcLinkSupervisor.Action.NONE,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    liveConns = 2,
                    framesSent = 42,
                    aaDecoding = false,
                    msSinceLastFrame = PxcLinkSupervisor.STREAM_STALL_MS + 5_000L,
                    msSinceLastRecovery = PxcLinkSupervisor.RECOVERY_COOLDOWN_MS,
                ),
            ),
        )
        assertEquals(
            PxcLinkSupervisor.Action.NONE,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    liveConns = 2,
                    framesSent = 42,
                    aaDecoding = true,
                    msSinceLastFrame = PxcLinkSupervisor.STREAM_STALL_MS - 1L,
                    msSinceLastRecovery = PxcLinkSupervisor.RECOVERY_COOLDOWN_MS,
                ),
            ),
        )
        assertEquals(
            PxcLinkSupervisor.Action.NONE,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    liveConns = 2,
                    framesSent = 42,
                    aaDecoding = true,
                    msSinceLastFrame = PxcLinkSupervisor.STREAM_STALL_MS,
                    msSinceLastRecovery = PxcLinkSupervisor.RECOVERY_COOLDOWN_MS - 1L,
                ),
            ),
        )
        assertEquals(
            PxcLinkSupervisor.Action.FORCE_RECONNECT_STREAM_STALL,
            PxcLinkSupervisor.evaluate(
                snapshot(
                    liveConns = 2,
                    framesSent = 42,
                    aaDecoding = true,
                    msSinceLastFrame = PxcLinkSupervisor.STREAM_STALL_MS,
                    msSinceLastRecovery = PxcLinkSupervisor.RECOVERY_COOLDOWN_MS,
                ),
            ),
        )
    }

    @Test
    fun `shouldRunThinkerrideScan skips known Zontes CFMoto Carbit and Morini bikes`() {
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(ZontesProfile, null, null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(ZontesHdProfile, null, null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(Cfdl16MotoPlayLandscapeProfile, null, null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(Cfdl26LandscapeProfile, null, null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(Cfdl26PortraitProfile, null, null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(Cfdl26NkTouchProfile, null, null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(Nk800Profile, null, null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(ClC450Profile, null, null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(MoriniMlSoftApProfile, null, null))
        assertFalse(
            PxcLinkSupervisor.shouldRunThinkerrideScan(
                LearnedGeometryProfile(ZontesProfile, 800 to 480, AaResolution.LANDSCAPE_800x480),
                null,
                null,
            ),
        )
        assertFalse(
            PxcLinkSupervisor.shouldRunThinkerrideScan(
                LearnedGeometryProfile(LegacyCfdl16Profile, 800 to 480, AaResolution.LANDSCAPE_800x480),
                null,
                null,
            ),
        )

        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(LegacyCfdl16Profile, "A004DC0D30888555", null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(LegacyCfdl16Profile, "ZT150X", null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(LegacyCfdl16Profile, "ZONTES-350D", null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(LegacyCfdl16Profile, "CFMOTO-1381DA", null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(LegacyCfdl16Profile, "DIRECT-AB-CFMOTO", null))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(LegacyCfdl16Profile, "UNKNOWN_SSID", "48406"))
        assertFalse(PxcLinkSupervisor.shouldRunThinkerrideScan(LegacyCfdl16Profile, "UNKNOWN_SSID", "37416"))
    }

    @Test
    fun `shouldRunThinkerrideScan allows generic or Thinkerride SoftAP without modelId`() {
        assertTrue(PxcLinkSupervisor.shouldRunThinkerrideScan(LegacyCfdl16Profile, "CQKY_5e1c6cb10", null))
        assertTrue(PxcLinkSupervisor.shouldRunThinkerrideScan(LegacyCfdl16Profile, "CQKY_5e1c6cb10", "  "))
        assertTrue(PxcLinkSupervisor.shouldRunThinkerrideScan(LegacyCfdl16Profile, null, null))
    }
}
