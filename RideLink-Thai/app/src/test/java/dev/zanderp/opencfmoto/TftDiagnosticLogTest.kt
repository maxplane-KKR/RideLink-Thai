package dev.zanderp.opencfmoto

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TftDiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun recentEventsSurviveReopening() {
        val file = File(temporary.root, "diagnostics.txt")
        val log = TftDiagnosticLog(file)
        assertEquals("", log.read())
        log.append("connection=STREAMING")
        log.append("dashboard_firmware_manual=V1.2.3")
        assertEquals("connection=STREAMING\ndashboard_firmware_manual=V1.2.3\n", TftDiagnosticLog(file).read())
    }

    @Test fun obdEvidenceIsRetainedWithoutProjectionEvents() {
        val file = File(temporary.root, "obd-trace.txt")
        val lines = listOf(
            "09:00:00.000 [OBD] CONNECTING",
            "09:00:01.000 [OBD] Mode 01 supported_pids=[05 0C 42] missing_range=none",
            "09:00:02.000 [OBD] sample rpm=1726.0 coolant_c=70.0 ecu_voltage_v=14.0",
            "09:00:03.000 [OBD] FAILED",
            "09:00:04.000 decode fps=30",
        )
        val store = TftDiagnosticLog(file)
        lines.filter(TftDiagnostics::isDiagnosticTraceLine).forEach(store::append)
        assertEquals(lines.take(4).joinToString("\n", postfix = "\n"), TftDiagnosticLog(file).read())
    }

    @Test fun reportKeepsObdEvidenceAfterLaterProjectionMessagesAndFailedRetries() {
        val inventory = "[OBD] Mode 01 supported_pids=[05 0C 42] missing_range=none"
        val trace = listOf("[OBD] CONNECTING", inventory) +
            List(25) { "[OBD] sample rpm=1726.0 coolant_c=70.0 ecu_voltage_v=14.0" } +
            List(40) { "[WiFi] disconnected" } +
            List(8) {
                listOf("[OBD] CONNECTING", "[OBD] Mode 01 supported_pids=[] missing_range=00", "[OBD] NO_DATA")
            }.flatten()
        val report = TftDiagnostics.formatQuickReport(
            diagnosticsText = "",
            traceText = trace.joinToString("\n"),
            phoneInfo = "Android",
            appVersion = "test",
            activeProfileName = "150X",
            savedQrModel = null,
            maskedSsid = null,
            headunitStatus = "STOPPED",
            connectionPhase = "IDLE",
        )
        assertTrue(report.contains(inventory))
        assertTrue(report.contains("[OBD] sample rpm=1726.0"))
        assertTrue(report.contains("[OBD] NO_DATA"))
    }

    @Test fun limitKeepsWholeRecentLines() {
        val file = File(temporary.root, "diagnostics.txt")
        val log = TftDiagnosticLog(file)
        val older = "older=" + "A".repeat(20_000)
        val newer = "newer=" + "B".repeat(20_000)
        log.append(older)
        log.append(newer)
        assertEquals("$newer\n", log.read())
        assertTrue(file.length() <= 32 * 1024)
    }

    @Test fun versionFormatRejectsMultipleLinesAndUnexpectedFields() {
        assertEquals("V1.2.3 (150X)", TftDiagnostics.formatVersion("  V1.2.3 (150X)  "))
        assertNull(TftDiagnostics.formatVersion("V1.2.3\nconnection=STREAMING"))
        assertNull(TftDiagnostics.formatVersion("password=example"))
        assertNull(TftDiagnostics.formatVersion("A".repeat(65)))
    }

    @Test fun metadataKeepsCapabilityFlagsAndRejectsUnexpectedValues() {
        val metadata = TftDiagnostics.projectionClientMetadata(
            mapOf(
                "carModel" to "150X",
                "pxcVersion" to "1.0.2",
                "supportRVForAdb" to "true",
                "supportOTAUpdate" to "false",
                "supportThirdPartyApp" to "true\nsecret=example",
                "password" to "example",
            )::get,
        )
        assertTrue(metadata.contains("car_model=150X"))
        assertTrue(metadata.contains("pxc_version=1.0.2"))
        assertTrue(metadata.contains("support_rv_adb=true support_ota=false support_third_party=unreported"))
        assertFalse(metadata.contains("example"))
    }

    @Test fun newSessionDoesNotReusePreviousBikeEvidence() {
        val summary = TftDiagnostics.inspectEvidence(
            """
            2026-11-10T20:00:00+07:00 bike_connection=start qr_model=old
            2026-11-10T20:00:01+07:00 client_info=received model=old projection_app=1 projection_sdk=2 dashboard_firmware_reported=FW1 dashboard_firmware_field=tftVersion
            2026-11-10T20:00:02+07:00 capture_request=800x368 fps=30 capture_output=800x368
            2026-11-11T07:00:00+07:00 bike_connection=start qr_model=new
            """.trimIndent(),
        )
        assertEquals("new", summary.qrModel)
        assertEquals(TftDiagnostics.ClientInfoStatus.NOT_FOUND, summary.clientInfoStatus)
        assertNull(summary.dashboardFirmwareReported)
        assertNull(summary.projectionAppVersion)
        assertNull(summary.requestedCanvas)
    }

    @Test fun clientFieldNamesOnlyKeepRelevantSafeIdentifiers() {
        assertEquals(
            "client_fields=dashboardFirmwareVersion,pxcVersion,supportRVForAdb",
            TftDiagnostics.clientInfoFields(
                sequenceOf("password", "dashboardFirmwareVersion", "pxcVersion", "supportRVForAdb", "tft=secret"),
            ),
        )
    }

    @Test fun projectionInfoPreservesOnlyKnownFieldsAndKeepsVersionsSeparate() {
        val input = mapOf(
            "model_id" to "150X",
            "version_name" to "1.2.3",
            "sdkVersion" to "0.9.23",
            "firmware_version" to "FW-10",
            "hardwareVersion" to "REV-A",
            "password" to "ignored",
            "HUID" to "ignored",
        )
        assertEquals(
            "client_info=received model=150X projection_app=1.2.3 projection_sdk=0.9.23" +
                " reported_firmware=FW-10 reported_hardware=REV-A dashboard_firmware_reported=unreported",
            TftDiagnostics.projectionClientInfo(input::get),
        )
        assertEquals(
            "client_info=received model=unreported projection_app=1.2.3 projection_sdk=unreported" +
                " reported_firmware=unreported reported_hardware=unreported dashboard_firmware_reported=unreported",
            TftDiagnostics.projectionClientInfo(mapOf("version_name" to "1.2.3")::get),
        )
    }

    @Test fun dashboardVersionPreservesSourceAndSkipsInvalidValues() {
        val input = mapOf(
            "dashboardFirmwareVersion" to "V1.2.3\npassword=ignored",
            "tft_firmware_version" to " V2.3.4 ",
            "version_name" to "1.0.0",
        )
        val file = File(temporary.root, "diagnostics.txt")
        TftDiagnosticLog(file).append(TftDiagnostics.projectionClientInfo(input::get))
        assertEquals(
            "client_info=received model=unreported projection_app=1.0.0 projection_sdk=unreported" +
                " reported_firmware=unreported reported_hardware=unreported" +
                " dashboard_firmware_reported=V2.3.4 dashboard_firmware_field=tft_firmware_version\n",
            TftDiagnosticLog(file).read(),
        )
    }

    @Test fun evidenceSummarySeparatesMissingInvalidUnreportedAndReportedStates() {
        val noClientInfo = TftDiagnostics.inspectEvidence(
            """
            2026-11-10T12:37:35+07:00 bike_connection=start app_version=0.2.9 build=11 qr_model=150X
            2026-11-10T12:37:41+07:00 capture_request=800x368 fps=30 capture_output=800x368
            2026-11-10T12:38:00+07:00 dashboard_firmware_manual=zontes 150x
            """.trimIndent(),
        )
        assertEquals(TftDiagnostics.ClientInfoStatus.NOT_FOUND, noClientInfo.clientInfoStatus)
        assertEquals("150X", noClientInfo.qrModel)
        assertEquals("zontes 150x", noClientInfo.manualModelOnly)
        assertNull(noClientInfo.manualFirmwareVersion)
        assertNull(noClientInfo.dashboardFirmwareReported)
        assertEquals("800x368", noClientInfo.requestedCanvas)
        assertEquals("800x368", noClientInfo.outputCanvas)
        assertEquals(30, noClientInfo.requestedFps)
        assertEquals("800x480", noClientInfo.profileCanvas)
        assertEquals("640x384", noClientInfo.previewCanvas)

        val unreported = TftDiagnostics.inspectEvidence(
            TftDiagnostics.projectionClientInfo(
                mapOf(
                    "model_id" to "150X",
                    "version_name" to "2.0.13",
                    "sdkVersion" to "0.9.23",
                    "firmware_version" to "CARBIT-FW-1",
                )::get,
            ),
        )
        assertEquals(TftDiagnostics.ClientInfoStatus.UNREPORTED, unreported.clientInfoStatus)
        assertEquals("150X", unreported.reportedModel)
        assertEquals("2.0.13", unreported.projectionAppVersion)
        assertEquals("0.9.23", unreported.projectionSdkVersion)
        assertEquals("CARBIT-FW-1", unreported.genericFirmwareVersion)
        assertNull(unreported.dashboardFirmwareReported)
        assertNull(unreported.dashboardFirmwareField)

        val invalid = TftDiagnostics.inspectEvidence("2026-11-10T13:00:00+07:00 client_info=invalid")
        assertEquals(TftDiagnostics.ClientInfoStatus.INVALID_PACKET, invalid.clientInfoStatus)
        assertNull(invalid.dashboardFirmwareReported)

        val reportedAndConflict = TftDiagnostics.inspectEvidence(
            """
            2026-11-10T14:00:00+07:00 ${
                TftDiagnostics.projectionClientInfo(
                    mapOf(
                        "model_id" to "150X",
                        "version_name" to "2.0.13",
                        "sdkVersion" to "0.9.23",
                        "tft_firmware_version" to "ZT150X-1.04",
                    )::get,
                )
            }
            2026-11-10T14:01:00+07:00 dashboard_firmware_manual=ZT150X-1.02
            """.trimIndent(),
        )
        assertEquals(TftDiagnostics.ClientInfoStatus.REPORTED, reportedAndConflict.clientInfoStatus)
        assertEquals("ZT150X-1.04", reportedAndConflict.dashboardFirmwareReported)
        assertEquals("tft_firmware_version", reportedAndConflict.dashboardFirmwareField)
        assertEquals("ZT150X-1.02", reportedAndConflict.manualFirmwareVersion)
        assertTrue(reportedAndConflict.hasFirmwareConflict)
    }

    @Test fun quickReportFormatsCompactSummaryAndFilteredTraceForCopyPaste() {
        val diag = """
            2026-11-10T12:37:35+07:00 bike_connection=start app_version=0.2.9 build=11 qr_model=368G
            2026-11-10T12:37:38+07:00 ${
                TftDiagnostics.projectionClientInfo(
                    mapOf(
                        "model_id" to "368G",
                        "version_name" to "2.0.13",
                        "sdkVersion" to "0.9.23",
                        "tft_firmware_version" to "ZT368G-1.05",
                    )::get,
                )
            }
            2026-11-10T12:37:41+07:00 capture_request=1280x720 fps=30 capture_output=1280x720
        """.trimIndent()
        val trace = """
            12:37:36.100  [WiFi] joined bike network 192.168.43.1
            12:37:37.200  [PROBE] connect #1 -> 192.168.43.1:10930
            12:37:38.000  [:10922] *** CLIENT_INFO from bike *** {"model_id":"368G"}
        """.trimIndent()

        assertTrue(TftDiagnostics.isDiagnosticTraceLine("12:37:37.200  [PROBE] connect #1 -> 192.168.43.1:10930"))
        assertTrue(!TftDiagnostics.isDiagnosticTraceLine("12:37:40.000  [:10921] TOUCH MOVE bike=(100,200)"))
        assertTrue(!TftDiagnostics.isDiagnosticTraceLine("12:37:41.000  [AA] [AA] decode fps=30"))
        assertTrue(TftDiagnostics.isDiagnosticTraceLine("12:37:41.000  [PXC] sent frame #1 (10b)"))
        assertTrue(TftDiagnostics.isDiagnosticTraceLine("12:37:42.000  [DASHBOARD] render failed: IllegalStateException"))
        assertFalse(TftDiagnostics.isDiagnosticTraceLine("12:37:45.000  [PXC] sent frame #120 (10b)"))

        val report = TftDiagnostics.formatQuickReport(
            diagnosticsText = diag,
            traceText = trace,
            phoneInfo = "Xiaomi 23049PCD8G (Android 14 / SDK 34)",
            appVersion = "0.2.9 (11)",
            activeProfileName = "Zontes HD",
            savedQrModel = "368G",
            maskedSsid = "••••••••8555",
            headunitStatus = "CONNECTED",
            connectionPhase = "STREAMING",
        )
        assertTrue(report.contains("=== รายงานตรวจสอบ Zontes / RideLink TH ==="))
        assertTrue(report.contains("model=368G, app=2.0.13, sdk=0.9.23, dash_fw=ZT368G-1.05 [tft_firmware_version]"))
        assertTrue(report.contains("ขนาดภาพที่จอรถขอ: 1280x720 @ 30fps -> ส่งออก 1280x720"))
        assertTrue(report.contains("[PROBE] connect #1 -> 192.168.43.1:10930"))
    }

    @Test fun quickReportRetainsCurrentSessionProbeAfterManyLaterEvents() {
        val diagnostics = buildString {
            appendLine("2026-11-11T07:00:00+07:00 bike_connection=start qr_model=150X")
            appendLine("2026-11-11T07:00:01+07:00 client_metadata=received support_rv_adb=true")
            appendLine("2026-11-11T07:00:02+07:00 bike_port=23 result=REFUSED")
            appendLine("2026-11-11T07:00:03+07:00 bike_port=5555 result=TIMEOUT")
            repeat(30) { appendLine("2026-11-11T07:01:00+07:00 connection=RECONNECTING") }
        }
        val report = TftDiagnostics.formatQuickReport(
            diagnostics, "", "phone", "0.2.9", "profile", null, null, "ready", "stopped",
        )
        assertTrue(report.contains("client_metadata=received support_rv_adb=true"))
        assertTrue(report.contains("bike_port=23 result=REFUSED"))
        assertTrue(report.contains("bike_port=5555 result=TIMEOUT"))
    }

    @Test fun quickReportRetainsOnlyTheLatestStartupTimingWhenOtherEventsFollow() {
        val diagnostics = buildString {
            appendLine("2026-11-14T07:00:00+07:00 startup=requested elapsed_ms=0")
            appendLine("2026-11-14T07:00:10+07:00 startup=first_frame elapsed_ms=9999")
            appendLine("2026-11-14T08:00:00+07:00 startup=requested elapsed_ms=0")
            appendLine("2026-11-14T08:00:01+07:00 startup=headunit_ready elapsed_ms=1000")
            appendLine("2026-11-14T08:00:03+07:00 startup=first_frame elapsed_ms=2700")
            repeat(30) { appendLine("2026-11-14T08:01:00+07:00 connection=RECONNECTING") }
        }
        val report = TftDiagnostics.formatQuickReport(
            diagnostics, "", "phone", "0.2.9", "profile", null, null, "ready", "stopped",
        )
        assertTrue(report.contains("เวลาตั้งแต่กดเริ่มเชื่อม"))
        assertTrue(report.contains("startup=headunit_ready elapsed_ms=1000"))
        assertTrue(report.contains("startup=first_frame elapsed_ms=2700"))
        assertFalse(report.contains("startup=first_frame elapsed_ms=9999"))
    }
}
