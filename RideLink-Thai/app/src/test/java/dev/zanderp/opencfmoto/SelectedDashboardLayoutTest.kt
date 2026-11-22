package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedDashboardLayoutTest {
    @Test fun missingDataNeverCreatesVehicleReadings() {
        val stale = LiveDashboardData(speedKmh = 64.2, speedFromGps = true, rpm = 3000.0, coolant = 90.0)
        val settings = LiveDashboardSettings()
        assertEquals("64", SelectedDashboardLayout.speed(stale))
        assertTrue(SelectedDashboardLayout.cards(stale, settings).all { it.value == "—" })
        assertEquals("—", SelectedDashboardLayout.speed(LiveDashboardData(speedKmh = Double.NaN)))
        assertEquals("—", SelectedDashboardLayout.speed(LiveDashboardData(speedKmh = -1.0)))
        assertNull(SelectedDashboardLayout.fraction(null, 160))
        assertNull(SelectedDashboardLayout.fraction(Double.POSITIVE_INFINITY, 160))
        assertEquals(1f, SelectedDashboardLayout.fraction(200.0, 160)!!, 0.001f)
    }

    @Test fun selectedFieldsAreRenderedOnceAndRacingUsesItsTopRpmGauge() {
        val settings = LiveDashboardSettings(visibleFields = LiveDashboardSpec.fields.toSet())
        val live = LiveDashboardData(rpm = 2800.0, coolant = 90.0, volt = 12.6, obdLive = true)
        val sport = SelectedDashboardLayout.cards(live, settings)
        assertEquals(listOf("rpm", "coolant", "volt", "load", "intake", "baro", "throttle"), sport.map { it.id })
        assertEquals(listOf("2800", "90 °C", "12.6 V"), sport.take(3).map { it.value })
        assertTrue(sport.drop(3).all { it.value == "—" })
        val racing = SelectedDashboardLayout.cards(live, settings.copy(design = LiveDashboardDesign.RACING))
        assertFalse(racing.any { it.id == "rpm" })
        assertEquals(6, racing.size)
        assertTrue(SelectedDashboardLayout.cards(live, settings.copy(visibleFields = emptySet())).isEmpty())
    }

    @Test fun showsManifoldPressureAndTheOtherExtendedReadings() {
        val live = LiveDashboardData(
            load = 47.0, intake = 32.0, manifoldPressureKpa = 39.0,
            relativeThrottle = 8.0, obdLive = true,
        )
        val settings = LiveDashboardSettings(visibleFields = setOf("load", "intake", "baro", "throttle"))
        val cards = SelectedDashboardLayout.cards(live, settings)
        assertEquals(listOf("LOAD", "INTAKE", "MAP", "THROTTLE"), cards.map { it.label })
        assertEquals(listOf("47 %", "32 °C", "39 kPa", "8 %"), cards.map { it.value })
    }

    @Test fun cardsFitWithoutOverlappingAtEverySupportedCount() {
        for (design in LiveDashboardDesign.entries) for (count in 0..7) {
            val slots = SelectedDashboardLayout.slots(design, count)
            assertEquals(count, slots.size)
            slots.forEach { s ->
                assertTrue(s.x >= 0f && s.y >= 0f && s.width > 0f && s.height > 0f)
                assertTrue(s.x + s.width <= SelectedDashboardLayout.WIDTH)
                assertTrue(s.y + s.height <= SelectedDashboardLayout.HEIGHT)
            }
            for (i in slots.indices) for (j in i + 1 until slots.size) {
                val a = slots[i]
                val b = slots[j]
                assertFalse(a.x < b.x + b.width && b.x < a.x + a.width &&
                    a.y < b.y + b.height && b.y < a.y + a.height)
            }
        }
    }

    @Test fun denseCardsHaveSpaceForReadableTextWithoutCoveringGauges() {
        val sport = SelectedDashboardLayout.slots(LiveDashboardDesign.SPORT, 7).first()
        assertEquals(156f, sport.width, .001f)
        assertEquals(53f, sport.height, .001f)

        for (design in listOf(LiveDashboardDesign.RACING, LiveDashboardDesign.C01_NEO_SPORT,
            LiveDashboardDesign.C03_ENDURANCE)) {
            val slots = SelectedDashboardLayout.slots(design, 6)
            assertTrue(slots.all { it.width >= 118f && it.height >= 51f })
            assertTrue(slots.all { it.x >= 518f && it.y >= 140f && it.y + it.height <= 320f })
        }
        val track = SelectedDashboardLayout.slots(LiveDashboardDesign.C04_TRACK_FOCUS, 6)
        assertTrue(track.all { it.width >= 200f && it.y >= 190f && it.y + it.height <= 300f })
    }

    @Test fun conceptCardsLeaveRoomForSpeedAndTheVehicleFooter() {
        val concepts = LiveDashboardDesign.entries.filter { it.isConcept }
        assertEquals(11, concepts.size)
        for (design in concepts) for (count in 0..7) {
            for (slot in ConceptDashboardLayout.slots(design, count)) {
                assertTrue(slot.y >= 0f && slot.y + slot.height <= 300f)
                when (design) {
                    LiveDashboardDesign.C02_CIRCUIT, LiveDashboardDesign.C09_MINIMAL_BLACK ->
                        assertTrue(slot.x + slot.width <= 243f || slot.x >= 557f)
                    LiveDashboardDesign.C04_TRACK_FOCUS -> assertTrue(slot.x + slot.width <= 456f)
                    else -> assertTrue(slot.x >= 516f)
                }
            }
        }
    }
}
