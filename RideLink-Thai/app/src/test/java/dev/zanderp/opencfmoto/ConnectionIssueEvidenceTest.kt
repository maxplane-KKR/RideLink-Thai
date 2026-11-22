package dev.zanderp.opencfmoto

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
