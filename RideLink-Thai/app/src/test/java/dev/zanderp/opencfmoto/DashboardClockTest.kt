package dev.zanderp.opencfmoto

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardClockTest {
    @Test fun format24HourClockUsesAsciiDigits() {
        val bkk = ZoneId.of("Asia/Bangkok")
        assertEquals("00:00", DashboardClock.format(millis(2026, 11, 24, 0, 0, bkk), bkk))
        assertEquals("07:05", DashboardClock.format(millis(2026, 11, 24, 7, 5, bkk), bkk))
        assertEquals("16:19", DashboardClock.format(millis(2026, 11, 24, 16, 19, bkk), bkk))
        assertEquals("23:59", DashboardClock.format(millis(2026, 11, 24, 23, 59, bkk), bkk))
    }

    @Test fun resolveUsesExplicitClockTextOrFormatsTimestamp() {
        val sampleMillis = millis(2026, 11, 24, 22, 30, ZoneId.systemDefault())
        assertEquals("09:15", DashboardClock.resolve("09:15", sampleMillis))
        assertEquals("09:15", DashboardClock.resolve("  09:15  ", sampleMillis))
        assertEquals("22:30", DashboardClock.resolve(null, sampleMillis))
        assertEquals("22:30", DashboardClock.resolve("   ", sampleMillis))
    }

    @Test fun formatNowAdvancesCleanlyAcrossMinuteBoundary() {
        val zone = ZoneId.systemDefault()
        val minuteA = millis(2026, 11, 24, 22, 29, zone) + 45_000L
        val minuteB = millis(2026, 11, 24, 22, 30, zone) + 1_000L
        assertEquals("22:29", DashboardClock.formatNow(minuteA))
        assertEquals("22:29", DashboardClock.formatNow(minuteA + 5_000L))
        assertEquals("22:30", DashboardClock.formatNow(minuteB))
    }

    @Test fun liveDashboardDataEqualityDetectsClockMinuteRollover() {
        val sameA = LiveDashboardData(speedKmh = 0.0, speedFromGps = true, clockText = "22:30")
        val sameB = LiveDashboardData(speedKmh = 0.0, speedFromGps = true, clockText = "22:30")
        val nextMinute = sameA.copy(clockText = "22:31")
        assertEquals(sameA, sameB)
        assertNotEquals(sameA, nextMinute)
    }

    @Test fun clockFitsAboveVehicleFooterAndNeverOverlapsCardsOnAll14Designs() {
        assertEquals(14, LiveDashboardDesign.entries.size)
        for (design in LiveDashboardDesign.entries) {
            val clock = DashboardClock.slot(design).bounds
            assertTrue("${design.id} left/right out of canvas", clock.x >= 0f && clock.x + clock.width <= SelectedDashboardLayout.WIDTH)
            assertTrue("${design.id} top/bottom out of safe area", clock.y >= 0f && clock.y + clock.height <= DashboardClock.MAX_SAFE_BOTTOM_Y)
            for (count in 0..7) {
                for (card in SelectedDashboardLayout.slots(design, count)) {
                    assertFalse(
                        "${design.id} clock overlaps card at count=$count: clock=$clock card=$card",
                        overlaps(clock, card),
                    )
                }
            }
        }
    }

    @Test fun clockClearsSpeedRpmAndGpsRegionsOnAll14Designs() {
        for (design in LiveDashboardDesign.entries) {
            val clock = DashboardClock.slot(design).bounds
            for ((label, box) in protectedRegions(design)) {
                assertFalse(
                    "${design.id} clock overlaps $label: clock=$clock region=$box",
                    overlaps(clock, box),
                )
            }
        }
    }

    private fun protectedRegions(design: LiveDashboardDesign): List<Pair<String, DashboardSlot>> = when (design) {
        LiveDashboardDesign.SPORT -> listOf(
            "speed" to DashboardSlot(42f, 80f, 344f, 185f),
            "kmh_gps" to DashboardSlot(100f, 280f, 240f, 55f),
            "top_left_poly" to DashboardSlot(0f, 0f, 192f, 70f),
        )
        LiveDashboardDesign.RACING -> listOf(
            "rpm_bar_scale" to DashboardSlot(16f, 20f, 764f, 68f),
            "rpm_value" to DashboardSlot(520f, 96f, 246f, 38f),
            "speed" to DashboardSlot(30f, 144f, 355f, 174f),
            "gps_kmh" to DashboardSlot(390f, 205f, 98f, 90f),
        )
        LiveDashboardDesign.TOURING -> listOf(
            "speed" to DashboardSlot(413f, 76f, 245f, 168f),
            "gps_kmh" to DashboardSlot(675f, 76f, 88f, 120f),
        )
        LiveDashboardDesign.C01_NEO_SPORT, LiveDashboardDesign.C03_ENDURANCE -> listOf(
            "rpm_bar_scale" to DashboardSlot(18f, 20f, 762f, 58f),
            "rpm_value" to DashboardSlot(518f, 102f, 260f, 35f),
            "speed" to DashboardSlot(35f, 117f, 270f, 150f),
            "gps_kmh" to DashboardSlot(342f, 195f, 72f, 70f),
        )
        LiveDashboardDesign.C02_CIRCUIT -> listOf(
            "rpm_bar" to DashboardSlot(180f, 29f, 440f, 24f),
            "speed" to DashboardSlot(285f, 108f, 230f, 140f),
            "gps_kmh" to DashboardSlot(346f, 256f, 170f, 32f),
        )
        LiveDashboardDesign.C04_TRACK_FOCUS -> listOf(
            "rpm_bar_scale" to DashboardSlot(63f, 20f, 697f, 63f),
            "rpm_value" to DashboardSlot(28f, 148f, 300f, 28f),
            "speed" to DashboardSlot(502f, 124f, 236f, 142f),
            "gps_kmh" to DashboardSlot(515f, 273f, 226f, 32f),
        )
        LiveDashboardDesign.C06_STREET_SPORT -> listOf(
            "top_left_lines" to DashboardSlot(30f, 8f, 135f, 114f),
            "speed" to DashboardSlot(35f, 117f, 270f, 150f),
            "gps_kmh" to DashboardSlot(342f, 195f, 72f, 70f),
        )
        LiveDashboardDesign.C07_APEX -> listOf(
            "top_left_poly" to DashboardSlot(0f, 14f, 346f, 105f),
            "speed" to DashboardSlot(35f, 117f, 270f, 150f),
            "gps_kmh" to DashboardSlot(342f, 195f, 72f, 70f),
        )
        LiveDashboardDesign.C08_CARBON_RACE -> listOf(
            "top_line" to DashboardSlot(25f, 19f, 750f, 4f),
            "speed" to DashboardSlot(35f, 117f, 270f, 150f),
            "gps_kmh" to DashboardSlot(342f, 195f, 72f, 70f),
        )
        LiveDashboardDesign.C09_MINIMAL_BLACK -> listOf(
            "speed" to DashboardSlot(285f, 108f, 230f, 140f),
            "gps_kmh" to DashboardSlot(346f, 256f, 170f, 32f),
        )
        LiveDashboardDesign.C11_AMBER_CLASSIC -> listOf(
            "top_header" to DashboardSlot(18f, 9f, 764f, 25f),
            "speed" to DashboardSlot(35f, 117f, 270f, 150f),
            "gps_kmh" to DashboardSlot(342f, 195f, 72f, 70f),
        )
        LiveDashboardDesign.C19_STEALTH -> listOf(
            "top_left_bar" to DashboardSlot(0f, 35f, 370f, 19f),
            "right_hairline" to DashboardSlot(520f, 64f, 255f, 2f),
            "speed" to DashboardSlot(35f, 117f, 270f, 150f),
            "gps_kmh" to DashboardSlot(342f, 195f, 72f, 70f),
        )
        LiveDashboardDesign.HORIZON -> listOf(
            "top_banner" to DashboardSlot(0f, 22f, 800f, 24f),
            "speed" to DashboardSlot(35f, 117f, 270f, 150f),
            "gps_kmh" to DashboardSlot(342f, 195f, 72f, 70f),
        )
    }

    private fun overlaps(a: DashboardSlot, b: DashboardSlot): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width &&
            a.y < b.y + b.height && b.y < a.y + a.height

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
