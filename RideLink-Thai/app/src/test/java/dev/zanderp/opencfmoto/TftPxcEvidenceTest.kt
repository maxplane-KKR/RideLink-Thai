package dev.zanderp.opencfmoto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TftPxcEvidenceTest {
    @Test fun recordsOnlySafeMetadataAndValidPorts() {
        val summary = TftPxcEvidence.format(
            cmd = PxcFrame.CMD_OTA_FTP_INFO,
            channel = TftPxcEvidence.Channel.CAR_DATA,
            payloadSize = 48,
            kind = TftPxcEvidence.PayloadKind.JSON,
            keys = listOf("port", "fileName", "userName", "pwd", "unexpectedSensitiveField"),
            port = 11021,
        )
        assertEquals(
            "pxc_frame=0x103a0 channel=CAR_DATA bytes=48 payload=JSON" +
                " fields=fileName,port auth_fields=present advertised_port=11021",
            summary,
        )
        assertFalse(summary.contains("userName"))
        assertFalse(summary.contains("pwd"))
        assertFalse(summary.contains("unexpectedSensitiveField"))
        assertTrue(
            TftPxcEvidence.format(1, TftPxcEvidence.Channel.UNKNOWN, 0, TftPxcEvidence.PayloadKind.EMPTY, port = 70000)
                .contains("auth_fields=unexamined advertised_port=unreported"),
        )
    }

    @Test fun capsDeduplicatesAndResetsPerSession() {
        val gate = TftPxcFrameGate(maxFrames = 2)
        val ctrl = TftPxcEvidence.Channel.CAR_CTRL
        val data = TftPxcEvidence.Channel.CAR_DATA
        assertEquals(TftPxcFrameGate.Decision.SKIP, gate.accept(PxcFrame.CMD_HEARTBEAT, ctrl, "heartbeat"))
        assertEquals(TftPxcFrameGate.Decision.RECORD, gate.accept(PxcFrame.CMD_OTA_FTP_INFO, ctrl, "empty"))
        assertEquals(TftPxcFrameGate.Decision.SKIP, gate.accept(PxcFrame.CMD_OTA_FTP_INFO, ctrl, "empty"))
        assertEquals(TftPxcFrameGate.Decision.RECORD, gate.accept(PxcFrame.CMD_OTA_FTP_INFO, ctrl, "port=1234"))
        assertEquals(TftPxcFrameGate.Decision.RECORD, gate.accept(PxcFrame.CMD_OTA_FTP_INFO, ctrl, "port=4321"))
        assertEquals(TftPxcFrameGate.Decision.SKIP, gate.accept(PxcFrame.CMD_OTA_FTP_INFO, ctrl, "port=5678"))
        assertEquals(TftPxcFrameGate.Decision.LIMIT, gate.accept(PxcFrame.CMD_QUERY_SPEED, data, "speed"))
        assertEquals(TftPxcFrameGate.Decision.SKIP, gate.accept(PxcFrame.CMD_QUERY_SPEED, data, "speed"))
        assertEquals(TftPxcFrameGate.Decision.RECORD, gate.accept(PxcFrame.CMD_SOCK_SERVER_INFO, data, "port=1234"))
        gate.reset()
        assertEquals(TftPxcFrameGate.Decision.RECORD, gate.accept(PxcFrame.CMD_SOCK_SERVER_INFO, data, "port=1234"))
    }

    @Test fun acceptsOnlyIntegerPortsWithinRange() {
        assertEquals(11021, TftPxcEvidence.safePort("11021"))
        assertEquals(23, TftPxcEvidence.safePort(23))
        assertNull(TftPxcEvidence.safePort(-1))
        assertNull(TftPxcEvidence.safePort(65536L))
        assertNull(TftPxcEvidence.safePort(23.5))
        assertNull(TftPxcEvidence.safePort("23\nother=1"))
    }

    @Test fun unknownControlStillAcknowledgesWithoutLoggingPayload() {
        val marker = "private_fixture_654321"
        val payload = """{"pwd":"$marker"}""".toByteArray()
        val logs = mutableListOf<String>()
        val output = ByteArrayOutputStream()
        val handled = Cfdl26PortraitProfile.handleUnknownControl(
            "test", PxcFrame(PxcFrame.CMD_OTA_FTP_INFO, payload), output, logs::add,
        )
        assertTrue(handled)
        val response = PxcFrame.read(ByteArrayInputStream(output.toByteArray()))
        assertNotNull(response)
        assertEquals(PxcFrame.CMD_OTA_FTP_INFO + 1, response!!.cmd)
        assertEquals(0, response.payload.size)
        val printed = logs.joinToString("\n")
        assertFalse(printed.contains(marker))
        assertFalse(printed.contains("hex="))
    }

    @Test fun masksHexAndOldRawProtocolLogsBeforeSharing() {
        val marker = "private_fixture_654321"
        val encoded = marker.toByteArray().joinToString("") { "%02x".format(it) }
        val redacted = LogRedactor.redact("""{"pwd":"$marker","userName":"$marker"} hex=$encoded""")
        assertFalse(redacted.contains(marker))
        assertFalse(redacted.contains(encoded))
        val oldTrace = """
            12:00:00.000 [PXC] *** CLIENT_INFO from bike *** {"unknown":"$marker"}
            12:00:01.000 [PXC] CFDL26 ctrl 0x103a0 (OTA_FTP_INFO) len=28 {"pwd":"$marker"} hex=$encoded
            12:00:02.000 [PXC] → CHECK_SN_RESULT {"id":"$marker"}
        """.trimIndent()
        val report = TftDiagnostics.formatQuickReport("", oldTrace, "phone", "demo", "profile", null, null, "ready", "idle")
        assertFalse(report.contains(marker))
        assertFalse(report.contains(encoded))
        assertTrue(report.contains("0x103a0"))
    }

    @Test fun quickReportKeepsEarlyPxcEventsAfterLaterActivity() {
        val diagnostics = buildString {
            appendLine("2026-11-11T12:00:00+07:00 bike_connection=start qr_model=150X")
            appendLine("2026-11-11T12:00:01+07:00 pxc_frame=0x103a0 channel=CAR_DATA bytes=20 payload=JSON")
            repeat(30) { appendLine("2026-11-11T12:01:00+07:00 connection=RECONNECTING") }
        }
        val report = TftDiagnostics.formatQuickReport(diagnostics, "", "phone", "demo", "profile", null, null, "ready", "stopped")
        assertTrue(report.contains("pxc_frame=0x103a0"))
    }
}
