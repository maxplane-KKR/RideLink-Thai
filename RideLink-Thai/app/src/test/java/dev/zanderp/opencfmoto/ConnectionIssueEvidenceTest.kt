package dev.zanderp.opencfmoto

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionIssueEvidenceTest {
    @Test fun reportWorksBeforeScanningOrConnecting() {
        assertEquals(ConnectionIssueEvidence(), ConnectionIssueEvidence.fromHistory(""))
    }

    @Test fun reportDoesNotReusePriorBikeAfterAnEarlyFailure() {
        val history = """
            2026-11-18T10:00:00+07:00 startup=requested elapsed_ms=0
            2026-11-18T10:00:01+07:00 bike_connection=start app_version=0.2.9 qr_model=48406
            2026-11-18T10:00:02+07:00 client_info=received model=OldModel dashboard_firmware_reported=1.2.0
            2026-11-18T10:00:03+07:00 startup=first_frame elapsed_ms=3000
            2026-11-18T10:02:00+07:00 startup=requested elapsed_ms=0
        """.trimIndent()
        val evidence = ConnectionIssueEvidence.fromHistory(history)
        assertTrue(evidence.attemptStarted)
        assertNull(evidence.lastStage)
        assertNull(evidence.qrModel)
        assertNull(evidence.displayModel)
        assertNull(evidence.displayFirmware)
        assertEquals(listOf(ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.REQUESTED, 0)), evidence.timeline)
        assertEquals(OffsetDateTime.parse("2026-11-18T10:02:00+07:00"), evidence.startedAt)
        val summary = evidence.structuredSummary()
        assertFalse(summary.contains("48406"))
        assertFalse(summary.contains("OldModel"))
        assertFalse(summary.contains("1.2.0"))
        assertTrue(summary.contains("previous_attempt=count=1;started_at=2026-11-18T10:00+07:00;last_stage=FIRST_FRAME;elapsed_ms=3000;final_phase=unavailable;final_reason=unavailable"))
    }

    @Test fun reportSeparatesModelCodesAndIgnoresRetryTaps() {
        val history = """
            startup=requested elapsed_ms=0
            startup=headunit_ready elapsed_ms=120
            bike_connection=start app_version=0.2.9 qr_model=350E
            startup=wifi_requested elapsed_ms=200
            startup=wifi_available elapsed_ms=1600
            client_info=received model=ZT350E reported_firmware=generic-v2 dashboard_firmware_reported=2.4.0
            startup=repeat_request elapsed_ms=1900
        """.trimIndent()
        val evidence = ConnectionIssueEvidence.fromHistory(history)
        assertNull(evidence.startedAt)
        assertEquals(ConnectionStartupStage.WIFI_AVAILABLE, evidence.lastStage)
        assertEquals(1600L, evidence.elapsedMs)
        assertEquals("350E", evidence.qrModel)
        assertEquals("ZT350E", evidence.displayModel)
        assertEquals("2.4.0", evidence.displayFirmware)
        assertEquals(listOf(
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.REQUESTED, 0),
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.HEADUNIT_READY, 120),
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.WIFI_REQUESTED, 200),
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.WIFI_AVAILABLE, 1600),
        ), evidence.timeline)
    }

    @Test fun timelineAcceptsOnlyKnownStagesAndTimesFromTheLatestAttempt() {
        val history = """
            startup=requested elapsed_ms=0
            startup=first_frame elapsed_ms=3000
            startup=requested elapsed_ms=0
            startup=unknown_stage elapsed_ms=2
            startup=wifi_requested elapsed_ms=-1
            startup=wifi_requested elapsed_ms=999999999999999999999
            startup=wifi_requested elapsed_ms=100 password=secret
            startup=repeat_request elapsed_ms=120
            startup=wifi_requested elapsed_ms=200
            startup=wifi_requested elapsed_ms=210
            startup=pxc_started elapsed_ms=300
        """.trimIndent()
        val evidence = ConnectionIssueEvidence.fromHistory(history)
        assertEquals(listOf(
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.REQUESTED, 0),
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.WIFI_REQUESTED, 200),
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.PXC_STARTED, 300),
        ), evidence.timeline)
        assertEquals(ConnectionStartupStage.PXC_STARTED, evidence.lastStage)
        assertEquals(300L, evidence.elapsedMs)
    }

    @Test fun reportFiltersIdentifiersAndDataFromAPreviousProcess() {
        val earlier = """
            startup=requested elapsed_ms=0
            bike_connection=start app_version=0.2.9 qr_model=001122AABBCC
            client_info=received model=192.168.1.1 reported_firmware=generic-v2 dashboard_firmware_reported=unreported
        """.trimIndent()
        val evidence = ConnectionIssueEvidence.fromHistory(earlier)
        assertTrue(evidence.attemptStarted)
        assertNull(evidence.qrModel)
        assertNull(evidence.displayModel)
        assertNull(evidence.displayFirmware)
        assertEquals(evidence, ConnectionIssueEvidence.fromHistory("$earlier\ncollection_schema=4 app_version=0.2.9"))
    }

    @Test fun reportKeepsLastAttemptAfterRestartWithoutMixingEventsFromTheNewProcess() {
        val history = """
            2026-11-16T07:30:00+07:00 collection_schema=4 app_version=0.2.9
            2026-11-16T07:37:46.123+07:00 startup=requested elapsed_ms=0
            2026-11-16T07:37:47+07:00 startup=headunit_ready elapsed_ms=100
            2026-11-16T07:37:48+07:00 bike_connection=start app_version=0.2.9 qr_model=350E
            2026-11-16T07:37:49+07:00 client_info=received model=ZT350E dashboard_firmware_reported=2.4.0
            2026-11-18T08:00:00+07:00 collection_schema=4 app_version=0.2.9
            2026-11-18T08:00:01+07:00 startup=wifi_available elapsed_ms=200
            2026-11-18T08:00:02+07:00 bike_connection=start app_version=0.2.9 qr_model=NewModel
            2026-11-18T08:00:03+07:00 client_info=received model=OtherModel dashboard_firmware_reported=9.9.9
        """.trimIndent()
        val evidence = ConnectionIssueEvidence.fromHistory(history)
        assertEquals(OffsetDateTime.parse("2026-11-16T07:37:46.123+07:00"), evidence.startedAt)
        assertEquals(ConnectionStartupStage.HEADUNIT_READY, evidence.lastStage)
        assertEquals(100L, evidence.elapsedMs)
        assertEquals("350E", evidence.qrModel)
        assertEquals("ZT350E", evidence.displayModel)
        assertEquals("2.4.0", evidence.displayFirmware)
        assertEquals(listOf(
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.REQUESTED, 0),
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.HEADUNIT_READY, 100),
        ), evidence.timeline)
    }

    @Test fun reportUsesNewAttemptAfterRestartWhenOneExists() {
        val history = """
            2026-11-16T07:37:46+07:00 startup=requested elapsed_ms=0
            2026-11-16T07:37:47+07:00 startup=headunit_ready elapsed_ms=100
            2026-11-18T08:00:00+07:00 collection_schema=4 app_version=0.2.9
            2026-11-18T08:00:01+07:00 startup=wifi_available elapsed_ms=200
            2026-11-18T08:10:00+07:00 startup=requested elapsed_ms=0
            2026-11-18T08:10:01+07:00 startup=first_frame elapsed_ms=700
        """.trimIndent()
        val evidence = ConnectionIssueEvidence.fromHistory(history)
        assertEquals(OffsetDateTime.parse("2026-11-18T08:10:00+07:00"), evidence.startedAt)
        assertEquals(ConnectionStartupStage.FIRST_FRAME, evidence.lastStage)
        assertNull(evidence.previousAttempt)
        assertEquals(listOf(
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.REQUESTED, 0),
            ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.FIRST_FRAME, 700),
        ), evidence.timeline)
    }

    @Test fun reportHandlesMissingOrInvalidStartTimestamp() {
        for (line in listOf("startup=requested elapsed_ms=0", "2026-11-18Tinvalid startup=requested elapsed_ms=0")) {
            val evidence = ConnectionIssueEvidence.fromHistory("$line\nstartup=headunit_ready elapsed_ms=100")
            assertTrue(evidence.attemptStarted)
            assertNull(evidence.startedAt)
            assertEquals(ConnectionStartupStage.HEADUNIT_READY, evidence.lastStage)
        }
    }

    @Test fun structuredSummaryKeepsOnlyKnownEvidenceFromTheRecordedAttempt() {
        val history = """
            startup=requested elapsed_ms=0
            startup=headunit_ready elapsed_ms=200
            connection=STARTING_AA reason=headunit_refused
            connection=STARTING_AA reason=headunit_refused
            bike_connection=start app_version=0.2.9 qr_model=150X
            client_info=received model=ZT150X projection_app=2.0.13 projection_sdk=0.9.23 reported_firmware=GENERIC dashboard_firmware_reported=ZT150X-1.04
            capture_request=1280x720 fps=30 capture_output=800x480
            connection=ERROR reason=conn_detail_easyconn_offline
            collection_schema=4 app_version=0.2.9
            connection=STREAMING
            client_info=received model=Other projection_app=9.9.9 dashboard_firmware_reported=9.9.9
        """.trimIndent()
        val evidence = ConnectionIssueEvidence.fromHistory(history)
        assertEquals(ConnectionIssueClientInfo.RECEIVED, evidence.clientInfo)
        assertEquals("2.0.13", evidence.projectionApp)
        assertEquals("0.9.23", evidence.projectionSdk)
        assertEquals("1280x720", evidence.captureRequest)
        assertEquals("800x480", evidence.captureOutput)
        assertEquals(30, evidence.captureFps)
        assertEquals(listOf(
            ConnectionIssuePhase(Phase.STARTING_AA, "headunit_refused"),
            ConnectionIssuePhase(Phase.ERROR, "conn_detail_easyconn_offline"),
        ), evidence.phaseHistory)
        val summary = evidence.structuredSummary()
        assertTrue(summary.contains("diagnostics_schema=1\n"))
        assertTrue(summary.contains("last_stage=HEADUNIT_READY\nelapsed_ms=200\n"))
        assertTrue(summary.contains("client_info=received\nclient_model=reported\nclient_dashboard_firmware=reported\n"))
        assertTrue(summary.contains("final_phase=ERROR\nfinal_reason=conn_detail_easyconn_offline\n"))
        assertTrue(summary.contains("phase_history=STARTING_AA:headunit_refused,ERROR:conn_detail_easyconn_offline\n"))
        assertFalse(summary.contains("9.9.9"))
    }

    @Test fun structuredSummaryDoesNotTreatGenericFirmwareAsDashboardFirmware() {
        val evidence = ConnectionIssueEvidence.fromHistory("""
            startup=requested elapsed_ms=0
            client_info=received model=unreported projection_app=2.0.13 projection_sdk=unreported reported_firmware=GENERIC dashboard_firmware_reported=unreported
        """.trimIndent())
        assertEquals(ConnectionIssueClientInfo.RECEIVED, evidence.clientInfo)
        assertNull(evidence.displayModel)
        assertNull(evidence.displayFirmware)
        assertTrue(evidence.structuredSummary().contains("client_model=unavailable\nclient_dashboard_firmware=unavailable"))
        assertEquals("2.0.13", evidence.projectionApp)
    }

    @Test fun structuredSummarySupportsOlderLogsWithoutAClientInfoOrBikeStart() {
        val evidence = ConnectionIssueEvidence.fromHistory("""
            startup=requested elapsed_ms=0
            connection=STREAMING projection_app=2.0.13 projection_sdk=0.9.23
        """.trimIndent())
        assertEquals(ConnectionIssueClientInfo.NOT_FOUND, evidence.clientInfo)
        assertEquals("2.0.13", evidence.projectionApp)
        assertEquals("0.9.23", evidence.projectionSdk)
        assertTrue(evidence.structuredSummary().contains("client_info=not_found"))
        assertTrue(evidence.structuredSummary().contains("final_phase=STREAMING"))
    }

    @Test fun structuredSummaryRejectsIdentifiersMalformedValuesAndUnknownReasons() {
        val history = """
            startup=requested elapsed_ms=0
            client_info=received model=192.168.1.1 projection_app=192.168.1.1 projection_sdk=001122AABBCC dashboard_firmware_reported=112233445566
            capture_request=99999x100 fps=999 capture_output=800x0
            connection=ERROR reason=private_secret
            connection=STREAMING ip=192.168.1.1
        """.trimIndent()
        val evidence = ConnectionIssueEvidence.fromHistory(history)
        assertEquals(ConnectionIssueClientInfo.RECEIVED, evidence.clientInfo)
        assertNull(evidence.displayModel)
        assertNull(evidence.displayFirmware)
        assertNull(evidence.projectionApp)
        assertNull(evidence.projectionSdk)
        assertNull(evidence.captureRequest)
        assertNull(evidence.captureOutput)
        assertNull(evidence.captureFps)
        assertEquals(listOf(ConnectionIssuePhase(Phase.ERROR, null)), evidence.phaseHistory)
        assertFalse(evidence.structuredSummary().contains("192.168.1.1"))
        assertFalse(evidence.structuredSummary().contains("private_secret"))
        assertFalse(evidence.structuredSummary().contains("112233445566"))
    }

    @Test fun structuredSummaryReportsInvalidClientAndBoundsPhaseHistory() {
        val history = buildString {
            appendLine("startup=requested elapsed_ms=0")
            appendLine("client_info=invalid")
            repeat(6) {
                appendLine("connection=STARTING_AA reason=aa_auto_retry")
                appendLine("connection=RECONNECTING reason=conn_detail_link_lost")
            }
        }
        val evidence = ConnectionIssueEvidence.fromHistory(history)
        assertEquals(ConnectionIssueClientInfo.INVALID, evidence.clientInfo)
        assertEquals(8, evidence.phaseHistory.size)
        assertTrue(evidence.phaseHistoryTruncated)
        assertTrue(evidence.structuredSummary().contains("client_info=invalid"))
        assertTrue(evidence.structuredSummary().contains("phase_history_truncated=true"))
    }

    @Test fun structuredSummaryPreservesAll19OriginalLinesInExactOrderBeforeAppendedFields() {
        val history = """
            2026-11-26T07:41:10+07:00 startup=requested elapsed_ms=0
            2026-11-26T07:41:12+07:00 startup=first_frame elapsed_ms=2850
            2026-11-26T07:41:15+07:00 connection=STOPPED
            2026-11-26T07:43:54.243198+07:00 startup=requested elapsed_ms=0
            2026-11-26T07:43:56+07:00 startup=first_frame elapsed_ms=2601
            2026-11-26T07:43:56+07:00 connection=STOPPED
        """.trimIndent()
        val lines = ConnectionIssueEvidence.fromHistory(history).structuredSummary().lines()
        val expectedOriginalPrefixes = listOf(
            "diagnostics_schema=1",
            "attempt_started_at=",
            "last_stage=",
            "elapsed_ms=",
            "client_info=",
            "client_model=",
            "client_dashboard_firmware=",
            "qr_model=",
            "display_model=",
            "display_firmware=",
            "projection_app=",
            "projection_sdk=",
            "capture_request=",
            "capture_output=",
            "capture_fps=",
            "final_phase=",
            "final_reason=",
            "phase_history=",
            "phase_history_truncated=",
        )
        assertTrue("Expected enriched lines after 19 original lines", lines.size > 19)
        expectedOriginalPrefixes.forEachIndexed { index, prefix ->
            assertTrue("Line $index should start with $prefix but was: ${lines[index]}", lines[index].startsWith(prefix))
        }
        assertTrue(lines[19].startsWith("previous_attempt="))
        assertTrue(lines[20].startsWith("live_dashboard_events="))
        assertTrue(lines[21].startsWith("obd_last_phase="))
    }

    @Test fun previousAttemptCountsRetriesWithinSameProcessAndIgnoresEarlierProcess() {
        val history = """
            2026-11-25T10:00:00+07:00 collection_schema=4 app_version=0.2.13
            2026-11-25T10:01:00+07:00 startup=requested elapsed_ms=0
            2026-11-25T10:01:05+07:00 startup=first_frame elapsed_ms=5000
            2026-11-26T07:40:00+07:00 collection_schema=4 app_version=0.2.14
            2026-11-26T07:40:10+07:00 startup=requested elapsed_ms=0
            2026-11-26T07:40:11+07:00 startup=wifi_requested elapsed_ms=100
            2026-11-26T07:40:15+07:00 connection=ERROR reason=conn_detail_easyconn_offline
            2026-11-26T07:41:10+07:00 startup=requested elapsed_ms=0
            2026-11-26T07:41:12+07:00 startup=pxc_started elapsed_ms=1525
            2026-11-26T07:41:20+07:00 connection=STOPPED
            2026-11-26T07:43:54+07:00 startup=requested elapsed_ms=0
            2026-11-26T07:43:56+07:00 startup=first_frame elapsed_ms=2601
            2026-11-26T07:44:00+07:00 connection=STREAMING
        """.trimIndent()
        val evidence = ConnectionIssueEvidence.fromHistory(history)
        val previous = evidence.previousAttempt
        assertNotNull(previous)
        assertEquals(2, previous!!.count)
        assertEquals(OffsetDateTime.parse("2026-11-26T07:41:10+07:00"), previous.startedAt)
        assertEquals(ConnectionStartupStage.PXC_STARTED, previous.lastStage)
        assertEquals(1525L, previous.elapsedMs)
        assertEquals(Phase.STOPPED, previous.finalPhase)
        assertNull(previous.finalReason)
    }

    @Test fun liveDashboardEventsKeepAllowlistedMilestonesBoundedAndExcludeNewProcess() {
        val history = buildString {
            appendLine("2026-11-26T07:40:00+07:00 collection_schema=4 app_version=0.2.14")
            appendLine("2026-11-26T07:41:00+07:00 startup=requested elapsed_ms=0")
            appendLine("2026-11-26T07:41:01+07:00 live_dashboard=REQUESTED attempt=1764117661000")
            appendLine("2026-11-26T07:41:02+07:00 live_dashboard=REJECTED_NOT_STREAMING attempt=1764117661000")
            appendLine("2026-11-26T07:41:03+07:00 live_dashboard=UNKNOWN_SECRET_EVENT attempt=1 secret=password")
            appendLine("2026-11-26T07:41:04+07:00 live_dashboard=LAYER_ACCEPTED attempt=2 design=racing theme=dark fields=4 gps_permission=granted")
            appendLine("2026-11-26T07:41:05+07:00 live_dashboard=FIRST_BITMAP attempt=2 size=800x368 gps_fresh=false")
            repeat(5) {
                appendLine("2026-11-26T07:41:06+07:00 live_dashboard=SETTINGS_CHANGED attempt=2 design=sport theme=dark fields=4")
            }
            appendLine("2026-11-26T07:41:07+07:00 live_dashboard=VIDEO_SENT_WHILE_ACTIVE attempt=2")
            appendLine("2026-11-26T07:41:08+07:00 live_dashboard=USER_STOP_REQUESTED attempt=2")
            appendLine("2026-11-26T08:00:00+07:00 collection_schema=4 app_version=0.2.14")
            appendLine("2026-11-26T08:00:01+07:00 live_dashboard=GL_FAILED attempt=99")
        }
        val evidence = ConnectionIssueEvidence.fromHistory(history)
        assertEquals(
            listOf(
                "REQUESTED",
                "REJECTED_NOT_STREAMING",
                "LAYER_ACCEPTED:racing",
                "FIRST_BITMAP",
                "SETTINGS_CHANGED",
                "VIDEO_SENT_WHILE_ACTIVE",
                "USER_STOP_REQUESTED",
            ),
            evidence.liveDashboardEvents,
        )
        val summary = evidence.structuredSummary()
        assertTrue(summary.contains("live_dashboard_events=REQUESTED,REJECTED_NOT_STREAMING,LAYER_ACCEPTED:racing,FIRST_BITMAP,SETTINGS_CHANGED,VIDEO_SENT_WHILE_ACTIVE,USER_STOP_REQUESTED"))
        assertFalse(summary.contains("GL_FAILED"))
        assertFalse(summary.contains("UNKNOWN_SECRET_EVENT"))
        assertFalse(summary.contains("1764117661000"))
    }

    @Test fun obdTraceExtractsAllowlistedSummaryAndPreservesDiscoveredPids() {
        val trace = """
            07:41:12.100  [WiFi] Wi-Fi joined: A004DC0D30888555 (network=105)
            07:41:15.000  [OBD] CONNECTING
            07:41:16.200  [OBD] failure stage=elm_init reason=io_error
            07:41:16.210  [OBD] FAILED
            07:43:50.000  [OBD] IDLE
            07:43:57.000  [OBD] CONNECTING
            07:43:58.100  [OBD] WAITING_FOR_ECU
            07:43:58.400  [OBD] command=0100 result=no_data
            07:43:59.000  [OBD] Mode 01 supported_pids=[] missing_range=00
            07:44:01.000  [OBD] command=010C result=parsed
            07:44:01.010  [OBD] LIVE
            07:44:01.050  [OBD] inventory_retry=started max_ranges=3
            07:44:01.600  [OBD] Mode 01 supported_pids=[05 0C 42] missing_range=none
            07:44:02.000  [OBD] sample rpm=1726.0 coolant_c=70.0 ecu_voltage_v=14.0
            07:44:07.000  [OBD] sample rpm=1800.0 coolant_c=72.0 ecu_voltage_v=14.1
            07:44:30.000  [OBD] Mode 01 supported_pids=[] missing_range=00
        """.trimIndent()
        val evidence = ConnectionIssueEvidence.fromHistory("", trace)
        assertFalse(evidence.attemptStarted)
        assertTrue(evidence.hasDiagnosticEvidence)
        assertEquals("LIVE", evidence.obd.lastPhase)
        assertEquals(listOf("CONNECTING", "FAILED", "IDLE", "CONNECTING", "WAITING_FOR_ECU", "LIVE"), evidence.obd.phaseHistory)
        assertEquals(listOf("elm_init:io_error"), evidence.obd.failures)
        assertEquals(listOf("0100:no_data", "010C:parsed"), evidence.obd.commands)
        assertEquals(listOf("05", "0C", "42"), evidence.obd.supportedPids)
        assertEquals(2, evidence.obd.samplesSeen)
        assertEquals(listOf("inventory_retry:started"), evidence.obd.events)

        val summary = evidence.structuredSummary()
        assertTrue(summary.contains("obd_last_phase=LIVE\n"))
        assertTrue(summary.contains("obd_phase_history=CONNECTING,FAILED,IDLE,CONNECTING,WAITING_FOR_ECU,LIVE\n"))
        assertTrue(summary.contains("obd_failures=elm_init:io_error\n"))
        assertTrue(summary.contains("obd_commands=0100:no_data,010C:parsed\n"))
        assertTrue(summary.contains("obd_supported_pids=05,0C,42\n"))
        assertTrue(summary.contains("obd_samples_seen=2\n"))
        assertTrue(summary.contains("obd_events=inventory_retry:started"))
        assertFalse(summary.contains("A004DC0D30888555"))
    }

    @Test fun strictPrivacyStripsMacSsidIpBtNamesAndArbitraryTextFromEnrichedEvidence() {
        val history = """
            2026-11-26T07:40:00+07:00 startup=requested elapsed_ms=0
            2026-11-26T07:40:01+07:00 bike_connection=start qr_model=AA:BB:CC:DD:EE:FF
            2026-11-26T07:40:02+07:00 live_dashboard=LAYER_ACCEPTED attempt=1 design=192.168.43.1 ssid=A004DC0D30888555
            2026-11-26T07:40:03+07:00 live_dashboard=EXFIL_TOKEN attempt=1 token=SuperSecret123
        """.trimIndent()
        val trace = """
            07:40:05.000  [OBD] device="Vgate iCar Pro BT" address=DC:0D:30:08:85:55 ssid=A004DC0D30888555 psk=BikeSecret99
            07:40:06.000  [OBD] failure stage=bluetooth_connect reason=io_error mac=11:22:33:44:55:66 name=OBDII_MyBike ip=192.168.43.1
            07:40:07.000  [OBD] failure stage=AA:BB:CC:DD:EE:FF reason=BikeSecret99
            07:40:08.000  [OBD] command=0100 result=parsed raw_secret=DEADBEEFCAFE0011
            07:40:09.000  [OBD] Mode 01 supported_pids=[05 0C 0011223344556677 DEADBEEF] missing_range=none
            07:40:10.000  [OBD] connect=insecure_fallback attempt=2 delay_ms=3000 device=MyPhoneBT mac=AA:BB:CC:DD:EE:FF
            07:40:11.000  [OBD] retry=SuperSecretValue
        """.trimIndent()

        val summary = ConnectionIssueEvidence.fromHistory(history, trace).structuredSummary()
        val forbidden = listOf(
            "AA:BB:CC:DD:EE:FF",
            "DC:0D:30:08:85:55",
            "11:22:33:44:55:66",
            "A004DC0D30888555",
            "BikeSecret99",
            "192.168.43.1",
            "Vgate",
            "OBDII_MyBike",
            "MyPhoneBT",
            "SuperSecret123",
            "SuperSecretValue",
            "DEADBEEF",
            "0011223344556677",
        )
        for (secret in forbidden) {
            assertFalse("Summary must never leak '$secret':\n$summary", summary.contains(secret, ignoreCase = true))
        }
        assertTrue(summary.contains("live_dashboard_events=LAYER_ACCEPTED\n"))
        assertTrue(summary.contains("obd_failures=bluetooth_connect:io_error\n"))
        assertTrue(summary.contains("obd_supported_pids=05,0C\n"))
        assertTrue(summary.contains("obd_events=connect:insecure_fallback(#2@3000ms)"))
    }

    @Test fun obdTraceRecognizesAllV0214StructuredLifecycleTokensWithoutPii() {
        val trace = """
            07:41:10.000  [OBD] skipped reason=radio_busy
            07:41:11.000  [OBD] aborted reason=radio_busy
            07:41:12.000  [OBD] settle_wait delay_ms=1500
            07:41:13.500  [OBD] CONNECTING
            07:41:14.000  [OBD] rfcomm_fallback=insecure
            07:41:15.000  [OBD] WAITING_FOR_ECU
            07:41:16.000  [OBD] elm_init=recovered banner_resync=true retries=2
            07:41:20.000  [OBD] failure stage=elm_init reason=timeout
            07:41:20.010  [OBD] retry_scheduled delay_ms=3000 attempt=1
            07:41:21.000  [OBD] skipped reason=secret_unlisted_reason
            07:41:22.000  [OBD] aborted reason=AA:BB:CC:DD:EE:FF
        """.trimIndent()

        val evidence = ConnectionIssueEvidence.fromHistory("", trace)
        assertEquals(
            listOf(
                "skipped:radio_busy",
                "aborted:radio_busy",
                "settle_wait(1500ms)",
                "rfcomm_fallback:insecure",
                "elm_init:recovered(resync=true;retries=2)",
                "retry_scheduled(#1@3000ms)",
            ),
            evidence.obd.events,
        )
        val summary = evidence.structuredSummary()
        assertTrue(
            summary.contains(
                "obd_events=skipped:radio_busy,aborted:radio_busy,settle_wait(1500ms),rfcomm_fallback:insecure,elm_init:recovered(resync=true;retries=2),retry_scheduled(#1@3000ms)",
            ),
        )
        assertFalse(summary.contains("secret_unlisted_reason"))
        assertFalse(summary.contains("AA:BB:CC:DD:EE:FF"))
    }
}
