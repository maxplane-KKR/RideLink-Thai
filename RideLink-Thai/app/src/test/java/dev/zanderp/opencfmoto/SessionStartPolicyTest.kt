package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartPolicyTest {
    private fun decide(
        requested: Boolean = true, running: Boolean = true, projecting: Boolean = true,
        receiverReady: Boolean = true, sameConfiguration: Boolean = true,
        error: Boolean = false, wifiPickerError: Boolean = false,
    ) = SessionStartPolicy.decide(requested, running, projecting, receiverReady,
        sameConfiguration, error, wifiPickerError)

    @Test fun duplicateConnectKeepsTheCurrentSessionAndWifiRetry() {
        assertEquals(SessionStartPolicy.Action.REUSE_PROJECTION, decide())
        assertEquals(SessionStartPolicy.Action.REUSE_PROJECTION, decide(error = true, wifiPickerError = true))
    }

    @Test fun differentBikeAndUnrecoverableErrorsRestartButStandbyIsPromoted() {
        assertEquals(SessionStartPolicy.Action.PROMOTE_STANDBY, decide(projecting = false))
        assertEquals(SessionStartPolicy.Action.RESTART, decide(sameConfiguration = false))
        assertEquals(SessionStartPolicy.Action.RESTART, decide(error = true))
        assertEquals(SessionStartPolicy.Action.RESTART, decide(receiverReady = false))
        assertEquals(SessionStartPolicy.Action.RESTART, decide(running = false))
        assertEquals(SessionStartPolicy.Action.RESTART, decide(requested = false))
    }

    @Test fun syncStopKeepsOnlyAVerifiedHeadunitForTheNextConnect() {
        assertTrue(SessionStartPolicy.keepHeadunitAfterSyncStop(projecting = true, verifiedHeadunitLive = true))
        assertFalse(SessionStartPolicy.keepHeadunitAfterSyncStop(projecting = true, verifiedHeadunitLive = false))
        assertFalse(SessionStartPolicy.keepHeadunitAfterSyncStop(projecting = false, verifiedHeadunitLive = true))
        assertEquals(SessionStartPolicy.Action.PROMOTE_STANDBY, decide(projecting = false))
    }

    @Test fun backgroundTimeoutStopsOnlyStandby() {
        assertTrue(SessionStartPolicy.stopHiddenStandby(running = true, projecting = false, appVisible = false))
        assertFalse(SessionStartPolicy.stopHiddenStandby(running = true, projecting = false, appVisible = true))
        assertFalse(SessionStartPolicy.stopHiddenStandby(running = true, projecting = true, appVisible = false))
        assertFalse(SessionStartPolicy.stopHiddenStandby(running = false, projecting = false, appVisible = false))
    }

    @Test fun learnedGeometryUsesItsValuesWhenComparingTwoSessionConfigurations() {
        val first = LearnedGeometryProfile(ZontesProfile, 800 to 368, AaResolution.LANDSCAPE_800x480)
        val repeated = LearnedGeometryProfile(ZontesProfile, 800 to 368, AaResolution.LANDSCAPE_800x480)
        val different = LearnedGeometryProfile(ZontesProfile, 800 to 480, AaResolution.LANDSCAPE_800x480)
        assertEquals(first, repeated)
        assertEquals(first.hashCode(), repeated.hashCode())
        assertNotEquals(first, different)
        assertEquals(800 to 368, repeated.panelSize)
        assertEquals(SessionStartPolicy.Action.PROMOTE_STANDBY,
            decide(projecting = false, sameConfiguration = first == repeated))
        assertEquals(SessionStartPolicy.Action.REUSE_PROJECTION,
            decide(sameConfiguration = first == repeated))
    }
}
