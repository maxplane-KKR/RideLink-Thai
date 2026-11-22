package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideScreenTelemetryTest {
    @Test fun missingAndStaleGpsNeverBecomeSampleSpeed() {
        assertNull(RideScreenTelemetry.gpsSpeedKmh(null, 0))
        assertNull(RideScreenTelemetry.gpsSpeedKmh(10f, 5001))
        assertNull(RideScreenTelemetry.gpsSpeedKmh(10f, -1))
        assertNull(RideScreenTelemetry.gpsSpeedKmh(Float.NaN, 0))
        assertNull(RideScreenTelemetry.gpsSpeedKmh(-1f, 0))
    }

    @Test fun freshGpsIsConvertedAndStandstillJitterIsBounded() {
        assertEquals(36.0, RideScreenTelemetry.gpsSpeedKmh(10f, 5000)!!, 0.001)
        assertEquals(0.0, RideScreenTelemetry.gpsSpeedKmh(0.2f, 100)!!, 0.001)
    }

    @Test fun liveFrameContainsOnlyFreshGpsAndNoSampleVehicleData() {
        assertEquals(LiveDashboardData(speedKmh = 36.0, speedFromGps = true),
            RideScreenTelemetry.dashboardData(10f, 1000))
        assertEquals(LiveDashboardData(), RideScreenTelemetry.dashboardData(10f, 5001))
    }
}
