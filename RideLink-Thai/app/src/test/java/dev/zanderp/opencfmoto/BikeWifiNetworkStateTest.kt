package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeWifiNetworkStateTest {
    @Test fun duplicateCallbacksAndReconnectionAreHandledOnce() {
        val state = BikeWifiNetworkState<String>()
        assertEquals(BikeWifiNetworkState.Available.FIRST, state.available("bike-1"))
        assertEquals(BikeWifiNetworkState.Available.UNCHANGED, state.available("bike-1"))
        assertTrue(state.lost("bike-1"))
        assertFalse(state.lost("bike-1"))
        assertNull(state.current)
        assertEquals(BikeWifiNetworkState.Available.REACQUIRED, state.available("bike-1"))
        assertEquals(BikeWifiNetworkState.Available.UNCHANGED, state.available("bike-1"))
    }

    @Test fun staleLossCannotDiscardNewNetworkAndResetStartsNewSession() {
        val state = BikeWifiNetworkState<String>()
        state.available("old")
        assertEquals(BikeWifiNetworkState.Available.REACQUIRED, state.available("new"))
        assertFalse(state.lost("old"))
        assertEquals("new", state.current)
        state.reset()
        assertNull(state.current)
        assertFalse(state.everDelivered)
        assertEquals(BikeWifiNetworkState.Available.FIRST, state.available("new"))
    }
}
