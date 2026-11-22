package dev.zanderp.opencfmoto

import java.time.OffsetDateTime

internal enum class ConnectionIssueTimelineStage {
    REQUESTED, HEADUNIT_READY, WIFI_REQUESTED, WIFI_AVAILABLE, PXC_STARTED, FIRST_FRAME
}

internal data class ConnectionIssueTimelineEvent(
    val stage: ConnectionIssueTimelineStage,
    val elapsedMs: Long,
)

internal enum class ConnectionIssueClientInfo { NOT_FOUND, INVALID, RECEIVED }

internal data class ConnectionIssuePhase(val phase: Phase, val reason: String?)

/** ข้อมูลเฉพาะการเชื่อมต่อรอบล่าสุดสำหรับรายงานที่ผู้ใช้ตรวจทานได้ */
internal data class ConnectionIssueEvidence(
    val attemptStarted: Boolean = false,
    val lastStage: ConnectionStartupStage? = null,
    val elapsedMs: Long? = null,
    val qrModel: String? = null,
    val displayModel: String? = null,
    val displayFirmware: String? = null,
    val timeline: List<ConnectionIssueTimelineEvent> = emptyList(),
    val startedAt: OffsetDateTime? = null,
    val clientInfo: ConnectionIssueClientInfo = ConnectionIssueClientInfo.NOT_FOUND,
    val projectionApp: String? = null,
    val projectionSdk: String? = null,
    val captureRequest: String? = null,
    val captureOutput: String? = null,
    val captureFps: Int? = null,
    val phaseHistory: List<ConnectionIssuePhase> = emptyList(),
    val phaseHistoryTruncated: Boolean = false,
) {
    fun structuredSummary(): String {
        val unavailable = "unavailable"
        val last = phaseHistory.lastOrNull()
        val stage = lastStage?.name ?: if (attemptStarted) "REQUESTED" else unavailable
        val time = elapsedMs ?: if (attemptStarted) 0L else null
        val phases = phaseHistory.joinToString(",") { "${it.phase.name}:${it.reason ?: unavailable}" }
        return listOf(
            "diagnostics_schema=1",
            "attempt_started_at=${startedAt ?: unavailable}",
            "last_stage=$stage",
            "elapsed_ms=${time ?: unavailable}",
            "client_info=${clientInfo.name.lowercase()}",
            "client_model=${if (displayModel == null) unavailable else "reported"}",
            "client_dashboard_firmware=${if (displayFirmware == null) unavailable else "reported"}",
            "qr_model=${qrModel ?: unavailable}",
            "display_model=${displayModel ?: unavailable}",
            "display_firmware=${displayFirmware ?: unavailable}",
            "projection_app=${projectionApp ?: unavailable}",
            "projection_sdk=${projectionSdk ?: unavailable}",
            "capture_request=${captureRequest ?: unavailable}",
            "capture_output=${captureOutput ?: unavailable}",
            "capture_fps=${captureFps ?: unavailable}",
            "final_phase=${last?.phase?.name ?: unavailable}",
            "final_reason=${last?.reason ?: unavailable}",
            "phase_history=${phases.ifEmpty { unavailable }}",
            "phase_history_truncated=$phaseHistoryTruncated",
        ).joinToString("\n")
    }

    companion object {
        private val timestamp = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\S+) ")
        private val progress = Regex("startup=([a-z_]+) elapsed_ms=([0-9]+)")
        private val stages = mapOf(
            "headunit_ready" to ConnectionStartupStage.HEADUNIT_READY,
            "wifi_requested" to ConnectionStartupStage.WIFI_REQUESTED,
            "wifi_available" to ConnectionStartupStage.WIFI_AVAILABLE,
            "pxc_started" to ConnectionStartupStage.PXC_STARTED,
            "first_frame" to ConnectionStartupStage.FIRST_FRAME,
        )
        private val shortCode = Regex("[A-Za-z0-9][A-Za-z0-9 ._/-]{0,31}")
        private val longIdentifier = Regex("(?:[A-Fa-f0-9]{12,}|(?:[0-9]{1,3}\\.){3}[0-9]{1,3})")
        private val versionCode = Regex("[vV]?[0-9]{1,4}(?:\\.[0-9]{1,4}){0,3}(?:[-_][A-Za-z0-9]{1,12})?")
        private val canvas = Regex("([0-9]{1,5})x([0-9]{1,5})")
        private val phaseRecord = Regex("^connection=([A-Z_]+)(?: reason=([a-z][a-z0-9_]{0,63}))?(?: projection_app=[A-Za-z0-9._/+() -]{1,64})?(?: projection_sdk=[A-Za-z0-9._/+() -]{1,64})?$")
        private val phases = Phase.entries.associateBy { it.name }
        private val reasons = setOf(
            "aa_auto_retry", "wifi_auto_retry", "headunit_refused", "headunit_wedged",
            "headunit_header_standby", "headunit_header_checking", "conn_joining_wifi",
            "conn_detail_wifi_picker", "conn_detail_vpn", "conn_detail_cfmoto", "conn_detail_easyconn_offline",
            "conn_detail_link_lost", "conn_detail_attempt", "bike_screen_dimensions",
        )
        private const val MAX_PHASES = 8

        fun fromHistory(history: String): ConnectionIssueEvidence {
            val rawLines = history.lineSequence().map { it.trim() }.toList()
            val lines = rawLines.map { timestamp.replaceFirst(it, "") }
            val start = lines.indexOfLast { it == "startup=requested elapsed_ms=0" }
            if (start < 0) return ConnectionIssueEvidence()
            val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("collection_schema=") }
                ?: lines.size
            val startedAt = timestamp.find(rawLines[start])?.groupValues?.get(1)?.let {
                runCatching { OffsetDateTime.parse(it) }.getOrNull()
            }

            val attempt = lines.subList(start + 1, end)
            var lastStage: ConnectionStartupStage? = null
            var elapsedMs: Long? = null
            val timeline = mutableListOf(ConnectionIssueTimelineEvent(ConnectionIssueTimelineStage.REQUESTED, 0L))
            val seen = mutableSetOf<ConnectionStartupStage>()
            val phaseHistory = mutableListOf<ConnectionIssuePhase>()
            var phaseHistoryTruncated = false
            for (line in attempt) {
                val phaseMatch = phaseRecord.matchEntire(line)
                val phase = phaseMatch?.groupValues?.get(1)?.let(phases::get)
                if (phase != null) {
                    val reason = phaseMatch?.groupValues?.get(2)?.takeIf { it in reasons }
                    val event = ConnectionIssuePhase(phase, reason)
                    if (phaseHistory.lastOrNull() != event) {
                        if (phaseHistory.size == MAX_PHASES) {
                            phaseHistory.removeAt(0)
                            phaseHistoryTruncated = true
                        }
                        phaseHistory.add(event)
                    }
                }
                val event = progress.matchEntire(line) ?: continue
                val stage = stages[event.groupValues[1]] ?: continue
                val elapsed = event.groupValues[2].toLongOrNull() ?: continue
                if (!seen.add(stage)) continue
                lastStage = stage
                elapsedMs = elapsed
                val timelineStage = when (stage) {
                    ConnectionStartupStage.HEADUNIT_READY -> ConnectionIssueTimelineStage.HEADUNIT_READY
                    ConnectionStartupStage.WIFI_REQUESTED -> ConnectionIssueTimelineStage.WIFI_REQUESTED
                    ConnectionStartupStage.WIFI_AVAILABLE -> ConnectionIssueTimelineStage.WIFI_AVAILABLE
                    ConnectionStartupStage.PXC_STARTED -> ConnectionIssueTimelineStage.PXC_STARTED
                    ConnectionStartupStage.FIRST_FRAME -> ConnectionIssueTimelineStage.FIRST_FRAME
                    ConnectionStartupStage.REPEAT_REQUEST -> continue
                }
                timeline.add(ConnectionIssueTimelineEvent(timelineStage, elapsed))
            }

            val bikeStart = attempt.indexOfLast { it.startsWith("bike_connection=start ") }
            val events = (if (bikeStart < 0) attempt else attempt.drop(bikeStart)).filter { line ->
                line.startsWith("bike_connection=start ") ||
                    line == "client_info=invalid" || line == "client_info=received" ||
                    line.startsWith("client_info=received ") || line.startsWith("capture_request=") ||
                    (phaseRecord.matchEntire(line)?.groupValues?.get(1)?.let(phases::containsKey) == true)
            }
            val bike = TftDiagnostics.inspectEvidence(events.joinToString("\n"))
            val clientInfo = when (bike.clientInfoStatus) {
                TftDiagnostics.ClientInfoStatus.NOT_FOUND -> ConnectionIssueClientInfo.NOT_FOUND
                TftDiagnostics.ClientInfoStatus.INVALID_PACKET -> ConnectionIssueClientInfo.INVALID
                TftDiagnostics.ClientInfoStatus.UNREPORTED, TftDiagnostics.ClientInfoStatus.REPORTED -> ConnectionIssueClientInfo.RECEIVED
            }
            return ConnectionIssueEvidence(
                attemptStarted = true,
                lastStage = lastStage,
                elapsedMs = elapsedMs,
                qrModel = safeCode(bike.qrModel),
                displayModel = safeCode(bike.reportedModel),
                displayFirmware = safeCode(bike.dashboardFirmwareReported),
                timeline = timeline,
                startedAt = startedAt,
                clientInfo = clientInfo,
                projectionApp = safeVersion(bike.projectionAppVersion),
                projectionSdk = safeVersion(bike.projectionSdkVersion),
                captureRequest = safeCanvas(bike.requestedCanvas),
                captureOutput = safeCanvas(bike.outputCanvas),
                captureFps = bike.requestedFps?.takeIf { it in 1..240 },
                phaseHistory = phaseHistory,
                phaseHistoryTruncated = phaseHistoryTruncated,
            )
        }

        private fun safeCode(value: String?): String? = value?.takeIf {
            shortCode.matches(it) && !longIdentifier.containsMatchIn(it)
        }

        private fun safeVersion(value: String?): String? = value?.takeIf {
            versionCode.matches(it) && !longIdentifier.containsMatchIn(it)
        }

        private fun safeCanvas(value: String?): String? {
            val match = value?.let(canvas::matchEntire) ?: return null
            val width = match.groupValues[1].toIntOrNull() ?: return null
            val height = match.groupValues[2].toIntOrNull() ?: return null
            return if (width in 1..8192 && height in 1..8192) "${width}x$height" else null
        }
    }
}
