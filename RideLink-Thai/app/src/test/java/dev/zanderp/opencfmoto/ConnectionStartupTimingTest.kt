package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStartupTimingTest {
    @Test fun recordsEachStageOnceFromTheOriginalRequestAndStopsCleanly() {
        val events = mutableListOf<String>()
        val timing = ConnectionStartupTiming { events.add(it) }
        timing.mark(ConnectionStartupStage.FIRST_FRAME, 500)
        timing.begin(1_000)
        timing.mark(ConnectionStartupStage.HEADUNIT_READY, 1_500)
        timing.mark(ConnectionStartupStage.REPEAT_REQUEST, 2_000)
        timing.mark(ConnectionStartupStage.REPEAT_REQUEST, 2_200)
        timing.mark(ConnectionStartupStage.WIFI_AVAILABLE, 3_000)
        timing.mark(ConnectionStartupStage.FIRST_FRAME, 3_700)
        timing.stop()
        timing.mark(ConnectionStartupStage.FIRST_FRAME, 4_000)
        assertEquals(listOf(
            "startup=requested elapsed_ms=0",
            "startup=headunit_ready elapsed_ms=500",
            "startup=repeat_request elapsed_ms=1000",
            "startup=wifi_available elapsed_ms=2000",
            "startup=first_frame elapsed_ms=2700",
        ), events)
    }

    @Test fun newAttemptResetsPreviousStagesAndBoundsClockErrors() {
        val events = mutableListOf<String>()
        val timing = ConnectionStartupTiming { events.add(it) }
        timing.begin(1_000)
        timing.mark(ConnectionStartupStage.WIFI_REQUESTED, 900)
        timing.begin(3_000)
        timing.mark(ConnectionStartupStage.WIFI_REQUESTED, 3_100)
        assertEquals(listOf(
            "startup=requested elapsed_ms=0",
            "startup=wifi_requested elapsed_ms=0",
            "startup=requested elapsed_ms=0",
            "startup=wifi_requested elapsed_ms=100",
        ), events)
    }
}
