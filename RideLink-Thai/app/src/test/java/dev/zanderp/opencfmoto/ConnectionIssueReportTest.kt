package dev.zanderp.opencfmoto

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionIssueReportTest {

    private object ThaiTextAdapter : ConnectionIssueTextAdapter {
        override val unknown = "ไม่ทราบ"
        override val selectModelLabel = "เลือกรุ่นรถ (ถ้าทราบ)"
        override val otherModelLabel = "รุ่นอื่น (ระบุเอง)"
        override val reportTitle = "รายงานการเชื่อมต่อ Android Auto"
        override val reportModelLabel = "รุ่นรถที่ผู้ใช้ระบุ"
        override val timelineTitle = "ประวัติการเชื่อมต่อรอบล่าสุดที่บันทึก"
        override val timelineEmpty = "ยังไม่มีลำดับการเชื่อมต่อ"
        override val diagnosticsTitle = "ข้อมูลโครงสร้างสำหรับวิเคราะห์"

        override fun stageLabel(stage: ConnectionIssueTimelineStage): String = when (stage) {
            ConnectionIssueTimelineStage.REQUESTED -> "เริ่มคำขอเชื่อมต่อ"
            ConnectionIssueTimelineStage.HEADUNIT_READY -> "Head Unit พร้อม"
            ConnectionIssueTimelineStage.WIFI_REQUESTED -> "ขอเชื่อม Wi-Fi รถ"
            ConnectionIssueTimelineStage.WIFI_AVAILABLE -> "เชื่อม Wi-Fi รถแล้ว"
            ConnectionIssueTimelineStage.PXC_STARTED -> "เริ่มเชื่อมกับจอรถ"
            ConnectionIssueTimelineStage.FIRST_FRAME -> "แอปส่งเฟรมแรกแล้ว"
        }

        override fun displayResultLabel(result: ConnectionIssueDisplayResult): String = when (result) {
            ConnectionIssueDisplayResult.UNKNOWN -> "ยังไม่ได้ตรวจ"
            ConnectionIssueDisplayResult.YES -> "เห็นภาพ"
            ConnectionIssueDisplayResult.NO -> "ไม่เห็นภาพ"
        }

        override fun formatSymptomLine(symptom: String) = "อาการ: $symptom"
        override fun formatDisplayLine(displayLabel: String) = "ภาพบนจอรถที่ผู้ใช้ยืนยัน: $displayLabel"
        override fun formatTimelineStarted(formattedTime: String) = "เวลาเริ่มรอบ: $formattedTime"
        override fun formatStageTime(stageLabel: String, elapsedMs: Long) =
            "$stageLabel · $elapsedMs มิลลิวินาทีหลังเริ่มเชื่อม"

        override fun formatReportDetails(
            appVersion: String,
            deviceInfo: String,
            phaseLabel: String,
            headunitStatus: String,
            lastStep: String,
            qrModel: String,
            displayModel: String,
            displayFirmware: String,
        ): String = listOf(
            "เวอร์ชัน RideLink TH: $appVersion",
            "มือถือ: $deviceInfo",
            "สถานะตอนเปิดรายงาน: $phaseLabel",
            "สถานะ Head Unit ตอนเปิดรายงาน: $headunitStatus",
            "ขั้นสุดท้ายที่บันทึกในรอบนั้น: $lastStep",
            "รหัสรุ่นจาก QR ในรอบนั้น: $qrModel",
            "รุ่นที่จอรถรายงานในรอบนั้น: $displayModel",
            "เฟิร์มแวร์จอที่รถรายงานในรอบนั้น: $displayFirmware",
        ).joinToString("\n")
    }

    private object EnglishTextAdapter : ConnectionIssueTextAdapter {
        override val unknown = "Unknown"
        override val selectModelLabel = "Select bike model (if known)"
        override val otherModelLabel = "Other (enter model)"
        override val reportTitle = "Android Auto connection report"
        override val reportModelLabel = "Bike model supplied by the rider"
        override val timelineTitle = "Latest recorded connection history"
        override val timelineEmpty = "No connection timeline is available yet."
        override val diagnosticsTitle = "Structured diagnostic information"

        override fun stageLabel(stage: ConnectionIssueTimelineStage): String = when (stage) {
            ConnectionIssueTimelineStage.REQUESTED -> "Connection requested"
            ConnectionIssueTimelineStage.HEADUNIT_READY -> "Head Unit ready"
            ConnectionIssueTimelineStage.WIFI_REQUESTED -> "Bike Wi-Fi requested"
            ConnectionIssueTimelineStage.WIFI_AVAILABLE -> "Bike Wi-Fi connected"
            ConnectionIssueTimelineStage.PXC_STARTED -> "Started linking to the bike display"
            ConnectionIssueTimelineStage.FIRST_FRAME -> "App sent the first frame"
        }

        override fun displayResultLabel(result: ConnectionIssueDisplayResult): String = when (result) {
            ConnectionIssueDisplayResult.UNKNOWN -> "Did not check"
            ConnectionIssueDisplayResult.YES -> "Yes, I saw it"
            ConnectionIssueDisplayResult.NO -> "No, I did not see it"
        }

        override fun formatSymptomLine(symptom: String) = "What happened: $symptom"
        override fun formatDisplayLine(displayLabel: String) =
            "Image on the bike display as seen by the rider: $displayLabel"
        override fun formatTimelineStarted(formattedTime: String) = "Attempt started: $formattedTime"
        override fun formatStageTime(stageLabel: String, elapsedMs: Long) =
            "$stageLabel · $elapsedMs ms after starting the connection"

        override fun formatReportDetails(
            appVersion: String,
            deviceInfo: String,
            phaseLabel: String,
            headunitStatus: String,
            lastStep: String,
            qrModel: String,
            displayModel: String,
            displayFirmware: String,
        ): String = listOf(
            "RideLink TH version: $appVersion",
            "Phone: $deviceInfo",
            "Status when opening the report: $phaseLabel",
            "Head Unit status when opening the report: $headunitStatus",
            "Last step recorded in that attempt: $lastStep",
            "QR model code in that attempt: $qrModel",
            "Model reported by the bike display in that attempt: $displayModel",
            "Display firmware reported by the bike in that attempt: $displayFirmware",
        ).joinToString("\n")
    }

    // Exact REGEXEXTRACT / REGEXMATCH patterns verified from Google Sheet 1H_zr35ibMF1xr6rNIw5ShM7scqLFBvhMm3nV_ndUVjA
    private val sheetLastStage = Regex("""(?m)^last_stage=([A-Z_]+)\s*$""")
    private val sheetElapsedMs = Regex("""(?m)^elapsed_ms=([0-9]+)\s*$""")
    private val sheetFinalPhase = Regex("""(?m)^final_phase=([A-Z_]+)\s*$""")
    private val sheetFinalReason = Regex("""(?m)^final_reason=([a-z0-9_]+)\s*$""")
    private val sheetClientInfo = Regex("""(?m)^client_info=([a-z_]+)""")
    private val sheetCaptureRequest = Regex("""(?m)^capture_request=([0-9]+x[0-9]+)""")
    private val sheetCaptureOutput = Regex("""(?m)^capture_output=([0-9]+x[0-9]+)""")
    private val sheetCaptureFps = Regex("""(?m)^capture_fps=([0-9]+)""")
    private val sheetSymptom = Regex("""(?mi)^(?:อาการ|อาการจำลอง|Symptom|What happened):[ \t]*([^\r\n]+)""")
    private val sheetDisplay = Regex(
        """(?mi)^(?:ภาพบนจอรถที่ผู้ใช้ยืนยัน|Display on the bike|Bike display|Display result|Image on the bike display as seen by the rider)[^:\n]*:\s*([^\r\n]+)""",
    )
    private val sheetTestPrefix = Regex("""(?m)^\[ทดสอบ""")

    private val rl0008History = """
        2026-11-26T07:40:00+07:00 collection_schema=4 app_version=0.2.14 build=16
        2026-11-26T07:41:10.100+07:00 startup=requested elapsed_ms=0
        2026-11-26T07:41:10.150+07:00 startup=wifi_requested elapsed_ms=50
        2026-11-26T07:41:11.600+07:00 startup=wifi_available elapsed_ms=1500
        2026-11-26T07:41:12.950+07:00 startup=first_frame elapsed_ms=2850
        2026-11-26T07:41:13.000+07:00 connection=STREAMING
        2026-11-26T07:41:14.000+07:00 live_dashboard=REQUESTED attempt=1
        2026-11-26T07:41:14.100+07:00 live_dashboard=LAYER_ACCEPTED attempt=1 design=racing theme=dark fields=4 gps_permission=granted
        2026-11-26T07:42:00.000+07:00 live_dashboard=USER_STOP_REQUESTED attempt=1
        2026-11-26T07:42:01.000+07:00 connection=STOPPED
        2026-11-26T07:43:54.243198+07:00 startup=requested elapsed_ms=0
        2026-11-26T07:43:54.268+07:00 startup=wifi_requested elapsed_ms=25
        2026-11-26T07:43:54.356+07:00 startup=headunit_ready elapsed_ms=113
        2026-11-26T07:43:55.768+07:00 startup=wifi_available elapsed_ms=1525
        2026-11-26T07:43:55.768+07:00 startup=pxc_started elapsed_ms=1525
        2026-11-26T07:43:55.800+07:00 bike_connection=start app_version=0.2.14 build=16 qr_model=48406
        2026-11-26T07:43:56.000+07:00 client_info=received model=unreported projection_app=1.0.16 projection_sdk=1.0.16 reported_firmware=unreported dashboard_firmware_reported=unreported
        2026-11-26T07:43:56.200+07:00 capture_request=800x368 fps=30 capture_output=800x368
        2026-11-26T07:43:56.844+07:00 startup=first_frame elapsed_ms=2601
        2026-11-26T07:43:56.845+07:00 connection=STARTING_AA
        2026-11-26T07:43:56.850+07:00 connection=STREAMING
        2026-11-26T07:43:57.000+07:00 live_dashboard=LAYER_ACCEPTED attempt=2 design=racing theme=dark fields=4 gps_permission=granted
        2026-11-26T07:45:00.000+07:00 connection=STOPPED
    """.trimIndent()

    private val rl0008Trace = """
        07:41:14.000  [OBD] settle_wait delay_ms=1500
        07:41:14.200  [OBD] CONNECTING
        07:41:14.800  [OBD] rfcomm_fallback=insecure
        07:41:15.500  [OBD] failure stage=elm_init reason=io_error
        07:41:15.510  [OBD] FAILED
        07:41:15.520  [OBD] retry_scheduled delay_ms=3000 attempt=1
        07:42:00.100  [OBD] IDLE
        07:43:58.500  [OBD] CONNECTING
        07:43:59.200  [OBD] WAITING_FOR_ECU
        07:43:59.400  [OBD] elm_init=recovered banner_resync=true retries=1
        07:43:59.700  [OBD] Mode 01 supported_pids=[05 0C 42] missing_range=none
        07:44:00.100  [OBD] command=010C result=parsed
        07:44:00.110  [OBD] LIVE
        07:44:00.500  [OBD] sample rpm=1650.0 coolant_c=68.0 ecu_voltage_v=14.1
    """.trimIndent()

    @Test fun rl0008ThaiReportPreserves5SectionsAndMatchesAllGoogleSheetRegexes() {
        val evidence = ConnectionIssueEvidence.fromHistory(rl0008History, rl0008Trace)
        val z150xIndex = ConnectionIssueReport.modelChoices(ThaiTextAdapter).indexOf("Zontes 150X")
        assertTrue(z150xIndex > 0)

        val result = ConnectionIssueReport.build(
            draft = ConnectionIssueDraft(
                modelChoiceIndex = z150xIndex,
                symptom = "odb2 ไม่เจอตอนเชื่อมครั้งแรกต้องเชื่อมใหม่ถึงได้",
                displayResult = ConnectionIssueDisplayResult.YES,
            ),
            evidence = evidence,
            context = ConnectionIssueContext(
                appVersion = "0.2.14 (16)",
                deviceInfo = "Xiaomi 25102PCBEG · Android 17",
                phaseLabel = "หยุดการเชื่อมต่อแล้ว",
                headunitStatus = "CONNECTED",
            ),
            text = ThaiTextAdapter,
        )
        assertTrue(result is ConnectionIssueBuildResult.Ready)
        val payload = result.payload
        assertEquals("Zontes 150X", payload.model)
        assertEquals(5, payload.report.split("\n\n").size)

        assertEquals("FIRST_FRAME", sheetLastStage.find(payload.report)?.groupValues?.get(1))
        assertEquals("2601", sheetElapsedMs.find(payload.report)?.groupValues?.get(1))
        assertEquals("STOPPED", sheetFinalPhase.find(payload.report)?.groupValues?.get(1))
        assertEquals("unavailable", sheetFinalReason.find(payload.report)?.groupValues?.get(1))
        assertEquals("received", sheetClientInfo.find(payload.report)?.groupValues?.get(1))
        assertEquals("800x368", sheetCaptureRequest.find(payload.report)?.groupValues?.get(1))
        assertEquals("800x368", sheetCaptureOutput.find(payload.report)?.groupValues?.get(1))
        assertEquals("30", sheetCaptureFps.find(payload.report)?.groupValues?.get(1))
        assertEquals("odb2 ไม่เจอตอนเชื่อมครั้งแรกต้องเชื่อมใหม่ถึงได้", sheetSymptom.find(payload.report)?.groupValues?.get(1))
        assertEquals("เห็นภาพ", sheetDisplay.find(payload.report)?.groupValues?.get(1))
        assertFalse(sheetTestPrefix.containsMatchIn(payload.report))

        // Enriched RL-0008 fields appear in Section 5
        assertTrue(payload.report.contains("previous_attempt=count=1;started_at=2026-11-26T07:41:10.100+07:00;last_stage=FIRST_FRAME;elapsed_ms=2850;final_phase=STOPPED;final_reason=unavailable"))
        assertTrue(payload.report.contains("live_dashboard_events=REQUESTED,LAYER_ACCEPTED:racing,USER_STOP_REQUESTED,LAYER_ACCEPTED:racing"))
        assertTrue(payload.report.contains("obd_last_phase=LIVE"))
        assertTrue(payload.report.contains("obd_failures=elm_init:io_error"))
        assertTrue(payload.report.contains("obd_supported_pids=05,0C,42"))
        assertTrue(payload.report.contains("obd_samples_seen=1"))
        assertTrue(
            payload.report.contains(
                "obd_events=settle_wait(1500ms),rfcomm_fallback:insecure,retry_scheduled(#1@3000ms),elm_init:recovered(resync=true;retries=1)",
            ),
        )
    }

    @Test fun englishReportMatchesAllGoogleSheetRegexes() {
        val evidence = ConnectionIssueEvidence.fromHistory(rl0008History, rl0008Trace)
        val z150xIndex = ConnectionIssueReport.modelChoices(EnglishTextAdapter).indexOf("Zontes 150X")
        val result = ConnectionIssueReport.build(
            draft = ConnectionIssueDraft(
                modelChoiceIndex = z150xIndex,
                symptom = "OBD2 failed on first connect",
                displayResult = ConnectionIssueDisplayResult.NO,
            ),
            evidence = evidence,
            context = ConnectionIssueContext(
                appVersion = "0.2.14 (16)",
                deviceInfo = "Xiaomi 25102PCBEG · Android 17",
                phaseLabel = "Disconnected",
                headunitStatus = "CONNECTED",
            ),
            text = EnglishTextAdapter,
        )
        assertTrue(result is ConnectionIssueBuildResult.Ready)
        val report = result.payload.report
        assertEquals(5, report.split("\n\n").size)
        assertEquals("FIRST_FRAME", sheetLastStage.find(report)?.groupValues?.get(1))
        assertEquals("2601", sheetElapsedMs.find(report)?.groupValues?.get(1))
        assertEquals("OBD2 failed on first connect", sheetSymptom.find(report)?.groupValues?.get(1))
        assertEquals("No, I did not see it", sheetDisplay.find(report)?.groupValues?.get(1))
        assertFalse(sheetTestPrefix.containsMatchIn(report))
    }

    @Test fun validationRejectsBlankOtherModelAndEmptyPreConnectionForm() {
        val otherIndex = ConnectionIssueReport.otherModelChoiceIndex(ThaiTextAdapter)
        val context = ConnectionIssueContext("0.2.14 (16)", "Phone · Android 17", "พร้อมเชื่อมต่อ", "STANDBY")
        val emptyEvidence = ConnectionIssueEvidence()

        val missingOther = ConnectionIssueReport.build(
            draft = ConnectionIssueDraft(modelChoiceIndex = otherIndex, otherModel = "   \n  ", symptom = "จอไม่ขึ้น"),
            evidence = emptyEvidence,
            context = context,
            text = ThaiTextAdapter,
        )
        assertTrue(missingOther is ConnectionIssueBuildResult.Invalid)
        assertEquals(
            ConnectionIssueValidationError.OTHER_MODEL_REQUIRED,
            (missingOther as ConnectionIssueBuildResult.Invalid).error,
        )
        assertTrue(missingOther.payload.report.contains("diagnostics_schema=1"))

        val emptyForm = ConnectionIssueReport.build(
            draft = ConnectionIssueDraft(),
            evidence = emptyEvidence,
            context = context,
            text = ThaiTextAdapter,
        )
        assertTrue(emptyForm is ConnectionIssueBuildResult.Invalid)
        assertEquals(
            ConnectionIssueValidationError.DETAILS_REQUIRED,
            (emptyForm as ConnectionIssueBuildResult.Invalid).error,
        )
        assertTrue(emptyForm.payload.report.contains("ยังไม่มีลำดับการเชื่อมต่อ"))
    }

    @Test fun validationAllowsOneTapSendWithDiagnosticEvidenceOrRiderInput() {
        val context = ConnectionIssueContext("0.2.14 (16)", "Phone · Android 17", "หยุดการเชื่อมต่อแล้ว", "CONNECTED")

        // 1-tap default send when a connection attempt exists
        val withAttempt = ConnectionIssueReport.build(
            draft = ConnectionIssueDraft(),
            evidence = ConnectionIssueEvidence.fromHistory("startup=requested elapsed_ms=0"),
            context = context,
            text = ThaiTextAdapter,
        )
        assertTrue(withAttempt is ConnectionIssueBuildResult.Ready)
        assertEquals("ไม่ทราบ", withAttempt.payload.model)

        // 1-tap default send when only OBD trace exists (e.g. manual OBD test)
        val withObdOnly = ConnectionIssueReport.build(
            draft = ConnectionIssueDraft(),
            evidence = ConnectionIssueEvidence.fromHistory("", "[OBD] CONNECTING\n[OBD] FAILED"),
            context = context,
            text = ThaiTextAdapter,
        )
        assertTrue(withObdOnly is ConnectionIssueBuildResult.Ready)

        // Pre-QR send with only symptom typed
        val withSymptomOnly = ConnectionIssueReport.build(
            draft = ConnectionIssueDraft(symptom = "สแกน QR ไม่ได้"),
            evidence = ConnectionIssueEvidence(),
            context = context,
            text = ThaiTextAdapter,
        )
        assertTrue(withSymptomOnly is ConnectionIssueBuildResult.Ready)

        // Custom other model provided
        val otherIndex = ConnectionIssueReport.otherModelChoiceIndex(ThaiTextAdapter)
        val withCustomModel = ConnectionIssueReport.build(
            draft = ConnectionIssueDraft(modelChoiceIndex = otherIndex, otherModel = "Zontes 350T"),
            evidence = ConnectionIssueEvidence(),
            context = context,
            text = ThaiTextAdapter,
        )
        assertTrue(withCustomModel is ConnectionIssueBuildResult.Ready)
        assertEquals("Zontes 350T", withCustomModel.payload.model)
    }

    @Test fun multilineUserInputsAreCollapsedToSingleLinePreventingSheetRegexSpoofing() {
        val evidence = ConnectionIssueEvidence.fromHistory(rl0008History, rl0008Trace)
        val otherIndex = ConnectionIssueReport.otherModelChoiceIndex(ThaiTextAdapter)
        val result = ConnectionIssueReport.build(
            draft = ConnectionIssueDraft(
                modelChoiceIndex = otherIndex,
                otherModel = "  Zontes\n350T\t2026 ",
                symptom = "ต่อ Wi-Fi ได้\nlast_stage=WIFI_REQUESTED\nfinal_phase=ERROR\nแต่ OBD ไม่ขึ้น",
                displayResult = ConnectionIssueDisplayResult.YES,
            ),
            evidence = evidence,
            context = ConnectionIssueContext("0.2.14 (16)", "Phone · Android 17", "หยุดการเชื่อมต่อแล้ว", "CONNECTED"),
            text = ThaiTextAdapter,
        )
        assertTrue(result is ConnectionIssueBuildResult.Ready)
        val payload = result.payload
        assertEquals("Zontes 350T 2026", payload.model)
        assertEquals(
            "ต่อ Wi-Fi ได้ last_stage=WIFI_REQUESTED final_phase=ERROR แต่ OBD ไม่ขึ้น",
            sheetSymptom.find(payload.report)?.groupValues?.get(1),
        )
        // Must still extract real diagnostics_schema=1 values, not injected lines
        assertEquals("FIRST_FRAME", sheetLastStage.find(payload.report)?.groupValues?.get(1))
        assertEquals("STOPPED", sheetFinalPhase.find(payload.report)?.groupValues?.get(1))

        // Verify form encoding round-trip with identical payload
        val encoded = ConnectionIssueSubmission.encode(payload.model, payload.report)
        val fields = encoded.split('&').associate { pair ->
            pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8.name())
        }
        assertEquals(payload.model, fields["entry.17275562"])
        assertEquals(payload.report, fields["entry.1178618371"])
        assertNotNull(fields["entry.1178618371"])
    }
}
