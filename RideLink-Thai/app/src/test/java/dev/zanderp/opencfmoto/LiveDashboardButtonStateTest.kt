package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDashboardButtonStateTest {
    @Test fun actionAndStatusMatchTheActualDashboardState() {
        val stopped = LiveDashboardButtonState.forActive(false)
        assertEquals(R.string.ridescreen_live_open, stopped.action)
        assertEquals(R.string.ridescreen_live_status_off, stopped.status)
        assertEquals(R.drawable.btn_liquid_emerald, stopped.background)

        val active = LiveDashboardButtonState.forActive(true)
        assertEquals(R.string.ridescreen_live_close, active.action)
        assertEquals(R.string.ridescreen_live_status_on, active.status)
        assertEquals(R.drawable.btn_liquid_sapphire, active.background)

        assertEquals(stopped, LiveDashboardButtonState.forActive(false))
    }
}
