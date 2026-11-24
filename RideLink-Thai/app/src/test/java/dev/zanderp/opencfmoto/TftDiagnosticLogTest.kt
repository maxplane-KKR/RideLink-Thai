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

    @Test fun quickReportRetainsLatestConnectionStartupTraceAfterLaterReconnectNoise() {
        val trace = buildList {
            add("18:20:24.183  [WiFi] requesting Wi-Fi join: TEST")
            add("18:20:25.601  [WiFi] Wi-Fi joined: TEST")
            add("18:20:25.602  [PXC] our IP=192.168.43.2 bike IP=192.168.43.1")
            add("18:20:25.650  [PXC] [PROBE] *** accepted ***")
            add("18:20:27.289  [PXC] sent frame #1 (1234b)")
            add("18:20:27.290  [state] Connected — projecting to dash")
            repeat(45) { idx ->
                add("18:26:30.$idx  [WiFi] Wi-Fi join unavailable after 12s — will retry")
            }
        }.joinToString("\n")
        val report = TftDiagnostics.formatQuickReport(
            diagnosticsText = "",
            traceText = trace,
            phoneInfo = "phone",
            appVersion = "0.2.11",
            activeProfileName = "Zontes",
            savedQrModel = "48406",
            maskedSsid = "••••••••8555",
            headunitStatus = "CONNECTED",
            connectionPhase = "IDLE",
        )
        assertTrue(report.contains("--- ช่วงเริ่มเชื่อมล่าสุด ---"))
        assertTrue(report.contains("[WiFi] requesting Wi-Fi join:"))
        assertTrue(report.contains("[PXC] sent frame #1 (1234b)"))
    }

    @Test fun isDiagnosticTraceLineFiltersHighStandbyRefusalCounts() {
        for (attempt in 1..5) {
            assertTrue(
                "Expected try $attempt to be retained",
                TftDiagnostics.isDiagnosticTraceLine(
                    "08:00:0$attempt.000  [AA] head unit server :5277 no answer (try $attempt) — ConnectException: ECONNREFUSED",
                ),
            )
        }
        for (attempt in listOf(6, 10, 110)) {
            assertFalse(
                "Expected try $attempt to be filtered",
                TftDiagnostics.isDiagnosticTraceLine(
                    "08:05:00.000  [AA] head unit server :5277 no answer (try $attempt) — ConnectException: ECONNREFUSED",
                ),
            )
        }
    }

    @Test fun quickReportFiltersStandbySpamAndPreservesBikeSessionAfterLaterHomeTap() {
        val trace = buildList {
            add("08:10:00.100  [WiFi] requesting Wi-Fi join: A004DC0D30888555")
            add("08:10:01.200  [WiFi] Wi-Fi joined: A004DC0D30888555 (network=105, bind deferred until AA video)")
            add("08:10:01.250  [PXC] our IP=192.168.43.2 bike IP=192.168.43.1")
            add("08:10:01.400  [PXC] [PROBE] *** accepted — bike should now connect back to our ports ***")
            add("08:10:02.900  [PXC] sent frame #1 (1234b)")
            add("08:10:02.910  [state] Connected — projecting to dash")
            // Later brief Start tap at home with standby refusals & Wi-Fi unavailable retries
            add("20:00:00.000  [WiFi] requesting Wi-Fi join: A004DC0D30888555")
            add("20:00:01.000  [AA] head unit server :5277 no answer (try 1) — ConnectException: ECONNREFUSED")
            add("20:00:08.000  [AA] head unit server :5277 no answer (try 5) — ConnectException: ECONNREFUSED")
            for (attempt in 6..110) {
                add("20:01:00.$attempt  [AA] head unit server :5277 no answer (try $attempt) — ConnectException: ECONNREFUSED")
            }
            repeat(52) { idx ->
                add("20:02:00.$idx  [WiFi] Wi-Fi join unavailable after 25s — will retry")
            }
        }.joinToString("\n")

        val report = TftDiagnostics.formatQuickReport(
            diagnosticsText = "",
            traceText = trace,
            phoneInfo = "phone",
            appVersion = "0.2.12",
            activeProfileName = "Zontes",
            savedQrModel = "48406",
            maskedSsid = "••••••••8555",
            headunitStatus = "REFUSED",
            connectionPhase = "RECONNECTING",
        )

        assertFalse(report.contains("no answer (try 6)"))
        assertFalse(report.contains("no answer (try 10)"))
        assertFalse(report.contains("no answer (try 110)"))
        assertTrue(report.contains("no answer (try 5)"))
        assertTrue(report.contains("--- ช่วงเชื่อมรถล่าสุด (Wi-Fi / PXC) ---"))
        assertTrue(report.contains("[PXC] our IP=192.168.43.2 bike IP=192.168.43.1"))
        assertTrue(report.contains("[PROBE] *** accepted"))
        assertTrue(report.contains("[PXC] sent frame #1 (1234b)"))
        assertFalse(report.contains("A004DC0D30888555"))
        assertTrue(report.contains("••••••••8555"))
    }

    @Test fun quickReportExpandsStatusAndTraceTailsAndRedactsWifiSecrets() {
        val diagnostics = (1..32).joinToString("\n") { idx ->
            "2026-11-24T08:00:${idx.toString().padStart(2, '0')}+07:00 connection=RECONNECTING reason=retry_$idx"
        }
        val traceLines = buildList {
            add("08:00:00.100  [WiFi] requesting Wi-Fi join: A004DC0D30888555 (bssid=dc:0d:30:08:85:55 psk=\"BikeSecret99\") …")
            repeat(49) { idx ->
                add("08:01:00.$idx  [Session] heartbeat_event_$idx")
            }
        }
        val report = TftDiagnostics.formatQuickReport(
            diagnosticsText = diagnostics,
            traceText = traceLines.joinToString("\n"),
            phoneInfo = "phone",
            appVersion = "0.2.12",
            activeProfileName = "Zontes",
            savedQrModel = "48406",
            maskedSsid = "••••••••8555",
            headunitStatus = "CONNECTED",
            connectionPhase = "IDLE",
        )

        assertTrue(report.contains("--- บันทึกสถานะย่อ (30 รายการล่าสุด) ---"))
        assertFalse(Regex("""\breason=retry_2\b""").containsMatchIn(report))
        assertTrue(report.contains("reason=retry_3"))
        assertTrue(report.contains("reason=retry_32"))

        assertTrue(report.contains("--- ลำดับเหตุการณ์ Wi-Fi / PXC (50 บรรทัดล่าสุด) ---"))
        assertTrue(report.contains("[WiFi] requesting Wi-Fi join: ••••••••8555"))
        assertFalse(report.contains("A004DC0D30888555"))
        assertFalse(report.contains("dc:0d:30:08:85:55"))
        assertFalse(report.contains("BikeSecret99"))
    }

    @Test fun quickReportPreservesBikeSessionWhenLaterHomeTapIsInsideTail() {
        val trace = buildList {
            add("08:10:00.100  [WiFi] requesting Wi-Fi join: TEST")
            add("08:10:01.200  [WiFi] Wi-Fi joined: TEST (network=105)")
            add("08:10:01.250  [PXC] our IP=192.168.43.2 bike IP=192.168.43.1")
            add("08:10:01.400  [PXC] [PROBE] *** accepted ***")
            add("08:10:02.900  [PXC] sent frame #1 (1234b)")
            repeat(12) { idx -> add("08:15:00.$idx  [PXC] heartbeat ok $idx") }
            // Later brief Start tap at home inside the 50-line tail (index 17 >= tailStart 13)
            add("20:00:00.000  [WiFi] requesting Wi-Fi join: TEST")
            repeat(45) { idx -> add("20:01:00.$idx  [WiFi] Wi-Fi join unavailable") }
        }
        val window = TftDiagnostics.latestBikeSessionTraceWindow(trace)
        assertEquals(17, window.size)
        assertEquals("08:10:00.100  [WiFi] requesting Wi-Fi join: TEST", window.first())

        val report = TftDiagnostics.formatQuickReport(
            diagnosticsText = "",
            traceText = trace.joinToString("\n"),
            phoneInfo = "phone",
            appVersion = "0.2.12",
            activeProfileName = "Zontes",
            savedQrModel = "48406",
            maskedSsid = "••••••••8555",
            headunitStatus = "REFUSED",
            connectionPhase = "IDLE",
        )
        assertFalse(report.contains("--- ช่วงเริ่มเชื่อมล่าสุด ---"))
        assertTrue(report.contains("--- ช่วงเชื่อมรถล่าสุด (Wi-Fi / PXC) ---"))
        assertTrue(report.contains("[PXC] our IP=192.168.43.2 bike IP=192.168.43.1"))
        assertTrue(report.contains("[PXC] sent frame #1 (1234b)"))
    }
}
