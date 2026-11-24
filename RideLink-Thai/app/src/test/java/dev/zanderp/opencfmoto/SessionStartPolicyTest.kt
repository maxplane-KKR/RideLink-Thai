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
        headunitLive: Boolean = true, forceBikeReconnect: Boolean = false,
    ) = SessionStartPolicy.decide(requested, running, projecting, receiverReady,
        sameConfiguration, error, wifiPickerError, headunitLive, forceBikeReconnect)

    @Test fun duplicateConnectKeepsTheCurrentSessionAndWifiRetry() {
        assertEquals(SessionStartPolicy.Action.REUSE_PROJECTION, decide())
        assertEquals(SessionStartPolicy.Action.REUSE_PROJECTION, decide(error = true, wifiPickerError = true))
    }

    @Test fun errorsPreserveLiveHeadunitWhileMissingOrChangedConfigRestarts() {
        assertEquals(SessionStartPolicy.Action.PROMOTE_STANDBY, decide(projecting = false))
        assertEquals(SessionStartPolicy.Action.RESTART, decide(sameConfiguration = false))
        assertEquals(SessionStartPolicy.Action.RESTART_BIKE_LINK, decide(error = true, headunitLive = true))
        assertEquals(SessionStartPolicy.Action.RESTART, decide(error = true, headunitLive = false))
        assertEquals(SessionStartPolicy.Action.RESTART, decide(receiverReady = false))
        assertEquals(SessionStartPolicy.Action.RESTART, decide(running = false))
        assertEquals(SessionStartPolicy.Action.RESTART, decide(requested = false))
    }

    @Test fun syncStopAlwaysStopsInOneTapWithoutStandbyFallback() {
        assertFalse(SessionStartPolicy.keepHeadunitAfterSyncStop(projecting = true, verifiedHeadunitLive = true))
        assertFalse(SessionStartPolicy.keepHeadunitAfterSyncStop(projecting = true, verifiedHeadunitLive = false))
        assertFalse(SessionStartPolicy.keepHeadunitAfterSyncStop(projecting = false, verifiedHeadunitLive = true))
    }

    @Test fun primaryToggleSwitchesBetweenConnectAndStopBySessionState() {
        val idle = SessionStartPolicy.evaluateToggleControl(running = false, projecting = false, phase = Phase.IDLE)
        assertEquals(SessionStartPolicy.ToggleCommand.START_CONNECT, idle.command)
        assertEquals(R.string.connect_start, idle.textRes)
        assertEquals(R.drawable.btn_liquid_emerald, idle.backgroundRes)
        assertEquals(R.drawable.ic_play_vector, idle.iconRes)

        val standby = SessionStartPolicy.evaluateToggleControl(running = true, projecting = false, phase = Phase.IDLE)
        assertEquals(SessionStartPolicy.ToggleCommand.START_CONNECT, standby.command)

        val stopped = SessionStartPolicy.evaluateToggleControl(running = false, projecting = false, phase = Phase.STOPPED)
        assertEquals(SessionStartPolicy.ToggleCommand.START_CONNECT, stopped.command)

        val waiting = SessionStartPolicy.evaluateToggleControl(
            running = true,
            projecting = false,
            phase = Phase.WAITING_FOR_BIKE,
        )
        assertEquals(SessionStartPolicy.ToggleCommand.STOP_SESSION, waiting.command)
        assertEquals(R.string.disconnect, waiting.textRes)
        assertEquals(R.drawable.btn_liquid_ruby, waiting.backgroundRes)
        assertEquals(R.drawable.ic_stop_vector, waiting.iconRes)

        val projectingError = SessionStartPolicy.evaluateToggleControl(
            running = true,
            projecting = true,
            phase = Phase.ERROR,
        )
        assertEquals(SessionStartPolicy.ToggleCommand.STOP_SESSION, projectingError.command)

        val streaming = SessionStartPolicy.evaluateToggleControl(
            running = true,
            projecting = true,
            phase = Phase.STREAMING,
        )
        assertEquals(SessionStartPolicy.ToggleCommand.STOP_SESSION, streaming.command)
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

    @Test fun forceBikeReconnectRestartsBikeLinkWhilePreservingLiveHeadunit() {
        assertEquals(
            SessionStartPolicy.Action.RESTART_BIKE_LINK,
            decide(forceBikeReconnect = true, headunitLive = true),
        )
        assertEquals(
            SessionStartPolicy.Action.RESTART_BIKE_LINK,
            decide(forceBikeReconnect = true, error = true, wifiPickerError = true, headunitLive = true),
        )
        assertEquals(
            SessionStartPolicy.Action.RESTART,
            decide(forceBikeReconnect = true, headunitLive = false),
        )
        assertEquals(
            SessionStartPolicy.Action.PROMOTE_STANDBY,
            decide(forceBikeReconnect = true, projecting = false, headunitLive = true),
        )
        assertEquals(
            SessionStartPolicy.Action.RESTART,
            decide(forceBikeReconnect = true, sameConfiguration = false, headunitLive = true),
        )
    }
}
