package dev.zanderp.opencfmoto

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveDashboardDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun recordsOneMilestonePerAttemptAndIgnoresLateFrames() {
        val file = File(temporary.root, "diagnostics.txt")
        val store = TftDiagnosticLog(file)
        val attempt = LiveDashboardLogAttempt(17, store::append)
        attempt.attached(LiveDashboardSettings(design = LiveDashboardDesign.RACING), true)
        attempt.videoSent()
        repeat(1_000) { attempt.bitmap(800, 368, false) }
        attempt.bitmap(800, 368, true)
        attempt.layerEvent(LiveLayerEvent.COMPOSITED)
        repeat(1_000) { attempt.videoSent() }
        attempt.stop(byUser = true)
        attempt.layerEvent(LiveLayerEvent.GL_FAILED)
        attempt.videoSent()

        val lines = TftDiagnosticLog(file).read().lines().filter(String::isNotEmpty)
        assertEquals(6, lines.size)
        assertTrue(lines[0].contains("LAYER_ACCEPTED attempt=17 design=racing"))
        assertTrue(lines[1].contains("FIRST_BITMAP attempt=17 size=800x368 gps_fresh=false"))
        assertTrue(lines[2].contains("GPS_FRESH attempt=17"))
        assertTrue(lines[3].contains("FIRST_ENCODER_SUBMISSION attempt=17"))
        assertTrue(lines[4].contains("VIDEO_SENT_WHILE_ACTIVE attempt=17"))
        assertTrue(lines[5].contains("USER_STOP_REQUESTED attempt=17"))
        assertFalse(lines.any { it.contains("GL_FAILED") })
        assertTrue(file.length() < 1_024)
    }

    @Test fun limitsRepeatedSettingsChangesAndKeepsAttemptsSeparate() {
        val events = mutableListOf<String>()
        val first = LiveDashboardLogAttempt(1) { events.add(it) }
        repeat(50) { first.settingsChanged(LiveDashboardSettings()) }
        first.stop(byUser = false)
        first.settingsChanged(LiveDashboardSettings())
        val second = LiveDashboardLogAttempt(2) { events.add(it) }
        second.layerEvent(LiveLayerEvent.RENDER_FAILED)
        second.layerEvent(LiveLayerEvent.RENDER_FAILED)

        assertEquals(12, events.count { it.contains("SETTINGS_CHANGED attempt=1") })
        assertEquals(1, events.count { it.contains("SETTINGS_LIMIT attempt=1") })
        assertEquals(1, events.count { it.contains("SESSION_STOP_REQUESTED attempt=1") })
        assertEquals(1, events.count { it.contains("RENDER_FAILED attempt=2") })
    }

    @Test fun quickReportKeepsLiveMilestonesAfterLaterConnectionEvents() {
        val diagnostics = buildString {
            appendLine("2026-11-13T10:00:00+07:00 live_dashboard=REQUESTED attempt=10")
            appendLine("2026-11-13T10:00:01+07:00 live_dashboard=FIRST_ENCODER_SUBMISSION attempt=10")
            repeat(30) { appendLine("2026-11-13T10:01:00+07:00 connection=RECONNECTING") }
        }
        val report = TftDiagnostics.formatQuickReport(
            diagnostics, "", "phone", "demo", "profile", null, null, "ready", "stopped",
        )
        assertTrue(report.contains("หน้าปัดสด (สถานะในแอป ไม่ยืนยันภาพบนจอรถ)"))
        assertTrue(report.contains("live_dashboard=REQUESTED attempt=10"))
        assertTrue(report.contains("live_dashboard=FIRST_ENCODER_SUBMISSION attempt=10"))
    }
}
