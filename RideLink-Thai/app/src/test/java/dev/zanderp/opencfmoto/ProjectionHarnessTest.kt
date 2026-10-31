package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionHarnessTest {

    @Test
    fun `normal BT ride without QR menu stays in standby and does NOT launch AA`() {
        val judgment = ProjectionHarness.evaluateAutoTrigger(
            ProjectionHarness.AutoTriggerInput(
                autoConnectEnabled = true,
                savedSsid = "A004DC0D30888555",
                sessionRunning = false,
                manuallyStopped = false,
                btBikeConnected = true,
                qrWifiDetected = false,
                msSinceLastTrigger = 60_000L,
            )
        )
        assertEquals(ProjectionHarness.TriggerDecision.ARM_WIFI_WATCHER_ONLY, judgment.decision)
        assertTrue(judgment.shouldKeepPassiveWifiRequest)
    }

    @Test
    fun `entering QR menu on Zontes dash fires AA projection automatically`() {
        val judgment = ProjectionHarness.evaluateAutoTrigger(
            ProjectionHarness.AutoTriggerInput(
                autoConnectEnabled = true,
                savedSsid = "A004DC0D30888555",
                sessionRunning = false,
                manuallyStopped = false,
                btBikeConnected = true,
                qrWifiDetected = true,
                msSinceLastTrigger = 60_000L,
            )
        )
        assertEquals(ProjectionHarness.TriggerDecision.FIRE_PROJECTION, judgment.decision)
        assertFalse(judgment.shouldKeepPassiveWifiRequest)
    }

    @Test
    fun `second connect defers PXC process bind until AA loopback video is live`() {
        // At 200ms (fast pre-approved Wi-Fi join on 2nd connect), AA loopback hasn't finished yet:
        val early = ProjectionHarness.evaluatePxcHandoff(
            hasBikeNetwork = true,
            proberActiveOnSameNetwork = false,
            aaSteadyOrDecoding = false,
            elapsedSinceSessionStartMs = 200L,
        )
        assertEquals(ProjectionHarness.HandoffDecision.DEFER_FOR_AA_LOOPBACK, early)

        // As soon as AA starts decoding on 127.0.0.1:5288, hand off to PXC immediately:
        val ready = ProjectionHarness.evaluatePxcHandoff(
            hasBikeNetwork = true,
            proberActiveOnSameNetwork = false,
            aaSteadyOrDecoding = true,
            elapsedSinceSessionStartMs = 2_400L,
        )
        assertEquals(ProjectionHarness.HandoffDecision.START_PXC_NOW, ready)

        // Fallback timeout also guarantees PXC starts even if AA takes longer than grace window:
        val fallback = ProjectionHarness.evaluatePxcHandoff(
            hasBikeNetwork = true,
            proberActiveOnSameNetwork = false,
            aaSteadyOrDecoding = false,
            elapsedSinceSessionStartMs = ProjectionHarness.LOOPBACK_GRACE_MS,
        )
        assertEquals(ProjectionHarness.HandoffDecision.START_PXC_NOW, fallback)
    }

    @Test
    fun `recognizes 16-hex Zontes Carbit SSID and masks sensitive details`() {
        val ssid = "A004DC0D30888555"
        assertTrue(ProjectionHarness.isZontesCarbitHexSsid(ssid))
        assertEquals("••••••••8555", ProjectionHarness.maskIdentifier(ssid, masked = true))
        assertEquals(ssid, ProjectionHarness.maskIdentifier(ssid, masked = false))
        assertEquals(
            "••••••••8555 (800×480)",
            ProjectionHarness.sanitizeStatusText("A004DC0D30888555 (800×480)", ssid, masked = true)
        )
    }

    @Test
    fun `safe area presets compute correct bounds and scale percentages`() {
        val full = ProjectionHarness.presetConfig(ProjectionHarness.SafeAreaPreset.FULL_PANEL)
        assertEquals(100, full.scalePercent)
        assertEquals(0, full.topPx)
        assertEquals(0, full.bottomPx)

        val zontesSafe = ProjectionHarness.presetConfig(ProjectionHarness.SafeAreaPreset.ZONTES_TEMPLATE_SAFE)
        assertEquals(94, zontesSafe.scalePercent)
        assertEquals(24, zontesSafe.topPx)
        assertEquals(20, zontesSafe.bottomPx)

        val deep = ProjectionHarness.presetConfig(ProjectionHarness.SafeAreaPreset.ZONTES_DEEP_FRAME)
        assertEquals(88, deep.scalePercent)
        assertEquals(36, deep.topPx)
        assertEquals(32, deep.bottomPx)
    }

    @Test
    fun `manual DPI is safely clamped within supported range`() {
        assertEquals(100, ProjectionHarness.clampManualDpi(80))
        assertEquals(135, ProjectionHarness.clampManualDpi(135))
        assertEquals(160, ProjectionHarness.clampManualDpi(160))
        assertEquals(220, ProjectionHarness.clampManualDpi(260))
    }
}
