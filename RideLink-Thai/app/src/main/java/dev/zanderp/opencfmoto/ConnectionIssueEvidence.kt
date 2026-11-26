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

internal data class ConnectionIssuePreviousAttempt(
    val count: Int,
    val startedAt: OffsetDateTime? = null,
    val lastStage: ConnectionStartupStage? = null,
    val elapsedMs: Long? = null,
    val finalPhase: Phase? = null,
    val finalReason: String? = null,
)

internal data class ConnectionIssueObdEvidence(
    val lastPhase: String? = null,
    val phaseHistory: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val supportedPids: List<String> = emptyList(),
    val inventorySeen: Boolean = false,
    val missingRange: String? = null,
    val unqueriedRange: String? = null,
    val samplesSeen: Int = 0,
    val events: List<String> = emptyList(),
    val hasEvidence: Boolean = false,
) {
    fun formattedSupportedPids(unavailable: String): String {
        if (!inventorySeen) return unavailable
        val base = if (supportedPids.isEmpty()) "none" else supportedPids.joinToString(",")
        val extras = buildList {
            if (missingRange != null && missingRange != "none") add("missing=$missingRange")
            if (unqueriedRange != null) add("unqueried=$unqueriedRange")
        }
        return if (extras.isEmpty()) base else "$base(${extras.joinToString(";")})"
    }
}

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
    val previousAttempt: ConnectionIssuePreviousAttempt? = null,
    val liveDashboardEvents: List<String> = emptyList(),
    val obd: ConnectionIssueObdEvidence = ConnectionIssueObdEvidence(),
) {
    val hasDiagnosticEvidence: Boolean
        get() = attemptStarted || liveDashboardEvents.isNotEmpty() || obd.hasEvidence

    fun structuredSummary(): String {
        val unavailable = "unavailable"
        val last = phaseHistory.lastOrNull()
        val stage = lastStage?.name ?: if (attemptStarted) "REQUESTED" else unavailable
        val time = elapsedMs ?: if (attemptStarted) 0L else null
        val phases = phaseHistory.joinToString(",") { "${it.phase.name}:${it.reason ?: unavailable}" }
        val previousSummary = previousAttempt?.let { prev ->
            val prevStage = prev.lastStage?.name ?: "REQUESTED"
            val prevElapsed = prev.elapsedMs ?: 0L
            "count=${prev.count}" +
                ";started_at=${prev.startedAt ?: unavailable}" +
                ";last_stage=$prevStage" +
                ";elapsed_ms=$prevElapsed" +
                ";final_phase=${prev.finalPhase?.name ?: unavailable}" +
                ";final_reason=${prev.finalReason ?: unavailable}"
        } ?: unavailable
        val liveEvents = liveDashboardEvents.joinToString(",").ifEmpty { unavailable }
        val obdLastPhase = obd.lastPhase ?: unavailable
        val obdPhases = obd.phaseHistory.joinToString(",").ifEmpty { unavailable }
        val obdFailures = obd.failures.joinToString(",").ifEmpty { unavailable }
        val obdCommands = obd.commands.joinToString(",").ifEmpty { unavailable }
        val obdPids = obd.formattedSupportedPids(unavailable)
        val obdSamples = if (obd.hasEvidence) obd.samplesSeen.toString() else unavailable
        val obdEvents = obd.events.joinToString(",").ifEmpty { unavailable }
        return listOf(
            // 19 คีย์หลักของ diagnostics_schema=1 — ห้ามเปลี่ยนลำดับหรือรูปแบบเพื่อให้สูตร Google Sheets อ่านได้เดิม 100%
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
            // ข้อมูลเสริมแบบ allowlist หลัง 19 คีย์เดิม (ไม่เก็บ MAC / SSID / IP / ชื่ออุปกรณ์ BT / ข้อความอิสระ)
            "previous_attempt=$previousSummary",
            "live_dashboard_events=$liveEvents",
            "obd_last_phase=$obdLastPhase",
            "obd_phase_history=$obdPhases",
            "obd_failures=$obdFailures",
            "obd_commands=$obdCommands",
            "obd_supported_pids=$obdPids",
            "obd_samples_seen=$obdSamples",
            "obd_events=$obdEvents",
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
        private val liveDashboardEvents = LiveDashboardEvent.entries.map { it.name }.toSet()
        private val liveDashboardDesigns = LiveDashboardDesign.entries.map { it.id }.toSet()
        private val liveDashboardLine = Regex("^live_dashboard=([A-Z_]+)(?:\\s+(.*))?$")
        private val designToken = Regex("""\bdesign=([a-z0-9_]{1,16})\b""")

        private val obdPhases = setOf(
            "IDLE", "CONNECTING", "WAITING_FOR_ECU", "LIVE", "NO_DATA", "UNAVAILABLE", "FAILED",
        )
        private val obdPhaseLine = Regex("^([A-Z_]+)$")
        private val obdFailureStageAllowed = setOf(
            "adapter_lookup", "bluetooth_connect", "bluetooth_connect_secure", "bluetooth_connect_insecure",
            "rfcomm_connect", "rfcomm_secure", "rfcomm_insecure", "elm_init", "elm_reset", "elm_sync",
            "mode01_inventory", "mode01_inventory_retry", "mode01_rpm", "ecu_wait", "session",
        )
        private val obdMode01Stage = Regex("^mode01_[0-9a-f]{2}$")
        private val obdFailureReasonAllowed = setOf(
            "timeout", "io_error", "permission", "rejected_setup", "disconnected", "not_paired",
            "bluetooth_off", "no_data", "no_ecu", "cancelled", "unsupported", "socket_closed",
            "connect_failed", "init_failed", "busy_projection", "radio_deferred", "radio_busy", "unknown",
        )
        private val obdStageToken = Regex("""\bstage=([a-z0-9_]{1,32})\b""")
        private val obdReasonToken = Regex("""\breason=([a-z0-9_]{1,32})\b""")
        private val obdCommandLine = Regex("""\bcommand=(01[0-9A-F]{2}|AT[A-Z0-9]{1,4})\b.*\bresult=([a-z_]{1,24})\b""")
        private val obdCommandResultAllowed = setOf(
            "parsed", "no_data", "unable_to_connect", "bus_error", "unsupported",
            "unrecognized_hex", "empty", "other_text", "timeout", "io_error", "ok", "rejected",
        )
        private val obdInventoryLine = Regex(
            """Mode 01 supported_pids=\[([0-9A-Fa-f ]*)\]\s+missing_range=(none|[0-9A-Fa-f]{2})(?:\s+unqueried_range=([0-9A-Fa-f]{2}))?""",
        )
        private val obdPidHex = Regex("^[0-9A-F]{2}$")
        private val obdInventoryRetry = Regex("""\binventory_retry=([a-z_]{1,24})\b""")
        private val obdRfcommFallback = Regex("""\brfcomm_fallback=(insecure|secure)\b""")
        private val obdElmInitEvent = Regex("""\belm_init=(recovered|ok|retried)\b""")
        private val obdBannerResync = Regex("""\bbanner_resync=(true|false)\b""")
        private val obdRetriesToken = Regex("""\bretries=([0-9]{1,3})\b""")
        private val obdGuardReasonAllowed = setOf(
            "radio_busy", "projection_busy", "no_permission", "not_selected", "stopped", "cancelled", "app_hidden",
        )
        private val obdEventKeyValue = Regex("""\b(retry|backoff|transport|connect|fallback|defer|deferred|gate|gated|settle|ecu_wait|elm_sync|test)=([a-z0-9_]{1,28})\b""")
        private val obdDelayToken = Regex("""\b(?:delay_ms|backoff_ms|settle_ms|wait_ms)=([0-9]{1,7})\b""")
        private val obdAttemptToken = Regex("""\battempt=([0-9]{1,3})\b""")
        private val obdEventValueAllowed = setOf(
            "started", "scheduled", "cancelled", "cleared", "secure", "insecure", "fallback",
            "insecure_fallback", "secure_failed", "deferred", "busy", "projection_busy",
            "wifi_pxc_busy", "radio_busy", "streaming_settle", "manual_override", "manual_test", "waiting",
            "recovered", "ok", "synced", "retry", "exhausted", "skipped", "blocked",
        )

        private const val MAX_PHASES = 8
        private const val MAX_LIVE_DASHBOARD_EVENTS = 10
        private const val MAX_OBD_PHASES = 8
        private const val MAX_OBD_FAILURES = 6
        private const val MAX_OBD_COMMANDS = 6
        private const val MAX_OBD_EVENTS = 8
        private const val MAX_OBD_PIDS = 32

        fun fromHistory(history: String, trace: String = ""): ConnectionIssueEvidence {
            val rawLines = history.lineSequence().map { it.trim() }.toList()
            val lines = rawLines.map { timestamp.replaceFirst(it, "") }
            val start = lines.indexOfLast { it == "startup=requested elapsed_ms=0" }
            val obd = parseObdTrace(trace)

            if (start < 0) {
                val processStart = lines.indexOfLast { it.startsWith("collection_schema=") }
                    .let { if (it >= 0) it + 1 else 0 }
                val liveEvents = parseLiveDashboardEvents(lines.subList(processStart, lines.size))
                if (liveEvents.isEmpty() && !obd.hasEvidence) return ConnectionIssueEvidence()
                return ConnectionIssueEvidence(
                    liveDashboardEvents = liveEvents,
                    obd = obd,
                )
            }

            val processStart = (0 until start).lastOrNull { lines[it].startsWith("collection_schema=") }
                ?.let { it + 1 }
                ?: 0
            val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("collection_schema=") }
                ?: lines.size
            val startedAt = parseTimestamp(rawLines[start])

            val attempt = lines.subList(start + 1, end)
            val parsedAttempt = parseAttemptSummary(attempt)
            val previousAttempt = parsePreviousAttempt(rawLines, lines, processStart, start)
            val liveEvents = parseLiveDashboardEvents(lines.subList(processStart, end))

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
                lastStage = parsedAttempt.lastStage,
                elapsedMs = parsedAttempt.elapsedMs,
                qrModel = safeCode(bike.qrModel),
                displayModel = safeCode(bike.reportedModel),
                displayFirmware = safeCode(bike.dashboardFirmwareReported),
                timeline = parsedAttempt.timeline,
                startedAt = startedAt,
                clientInfo = clientInfo,
                projectionApp = safeVersion(bike.projectionAppVersion),
                projectionSdk = safeVersion(bike.projectionSdkVersion),
                captureRequest = safeCanvas(bike.requestedCanvas),
                captureOutput = safeCanvas(bike.outputCanvas),
                captureFps = bike.requestedFps?.takeIf { it in 1..240 },
                phaseHistory = parsedAttempt.phaseHistory,
                phaseHistoryTruncated = parsedAttempt.phaseHistoryTruncated,
                previousAttempt = previousAttempt,
                liveDashboardEvents = liveEvents,
                obd = obd,
            )
        }

        private data class ParsedAttempt(
            val lastStage: ConnectionStartupStage?,
            val elapsedMs: Long?,
            val timeline: List<ConnectionIssueTimelineEvent>,
            val phaseHistory: List<ConnectionIssuePhase>,
            val phaseHistoryTruncated: Boolean,
        )

        private fun parseAttemptSummary(attempt: List<String>): ParsedAttempt {
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
                    val reason = phaseMatch.groupValues[2].takeIf { it in reasons }
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
            return ParsedAttempt(
                lastStage = lastStage,
                elapsedMs = elapsedMs,
                timeline = timeline,
                phaseHistory = phaseHistory,
                phaseHistoryTruncated = phaseHistoryTruncated,
            )
        }

        private fun parsePreviousAttempt(
            rawLines: List<String>,
            lines: List<String>,
            processStart: Int,
            currentStart: Int,
        ): ConnectionIssuePreviousAttempt? {
            val priorStarts = (processStart until currentStart).filter {
                lines[it] == "startup=requested elapsed_ms=0"
            }
            if (priorStarts.isEmpty()) return null
            val prevStart = priorStarts.last()
            val parsed = parseAttemptSummary(lines.subList(prevStart + 1, currentStart))
            val lastPhase = parsed.phaseHistory.lastOrNull()
            return ConnectionIssuePreviousAttempt(
                count = priorStarts.size,
                startedAt = parseTimestamp(rawLines[prevStart]),
                lastStage = parsed.lastStage,
                elapsedMs = parsed.elapsedMs,
                finalPhase = lastPhase?.phase,
                finalReason = lastPhase?.reason,
            )
        }

        private fun parseLiveDashboardEvents(windowLines: List<String>): List<String> {
            val events = mutableListOf<String>()
            for (line in windowLines) {
                val match = liveDashboardLine.matchEntire(line) ?: continue
                val eventName = match.groupValues[1]
                if (eventName !in liveDashboardEvents) continue
                val tail = match.groupValues[2]
                val token = if (eventName == "LAYER_ACCEPTED") {
                    val design = designToken.find(tail)?.groupValues?.get(1)
                        ?.takeIf { it in liveDashboardDesigns }
                    if (design != null) "$eventName:$design" else eventName
                } else {
                    eventName
                }
                if (eventName == "SETTINGS_CHANGED" && events.lastOrNull() == "SETTINGS_CHANGED") {
                    continue
                }
                if (events.size == MAX_LIVE_DASHBOARD_EVENTS) {
                    events.removeAt(0)
                }
                events.add(token)
            }
            return events
        }

        private fun parseObdTrace(trace: String): ConnectionIssueObdEvidence {
            if (trace.isBlank()) return ConnectionIssueObdEvidence()
            var lastPhase: String? = null
            val phaseHistory = mutableListOf<String>()
            val failures = mutableListOf<String>()
            val commands = mutableListOf<String>()
            var inventorySeen = false
            var supportedPids: List<String> = emptyList()
            var missingRange: String? = null
            var unqueriedRange: String? = null
            var samplesSeen = 0
            val events = mutableListOf<String>()

            for (rawLine in trace.lineSequence()) {
                val trimmed = rawLine.trim()
                val markerIdx = trimmed.indexOf("[OBD]")
                if (markerIdx < 0) continue
                val payload = trimmed.substring(markerIdx + "[OBD]".length).trim()
                if (payload.isEmpty()) continue

                // 1. สถานะของ LiveObd (เช่น [OBD] CONNECTING, [OBD] WAITING_FOR_ECU, [OBD] LIVE)
                val exactPhase = obdPhaseLine.matchEntire(payload)?.groupValues?.get(1)
                if (exactPhase != null && exactPhase in obdPhases) {
                    lastPhase = exactPhase
                    appendBounded(phaseHistory, exactPhase, MAX_OBD_PHASES, deduplicateConsecutive = true)
                    continue
                }

                // 2. ความล้มเหลวของ OBD ([OBD] failure stage=... reason=...)
                if (payload.startsWith("failure ")) {
                    val stage = obdStageToken.find(payload)?.groupValues?.get(1)
                        ?.takeIf { it in obdFailureStageAllowed || obdMode01Stage.matches(it) }
                    val reason = obdReasonToken.find(payload)?.groupValues?.get(1)
                        ?.takeIf { it in obdFailureReasonAllowed }
                    if (stage != null && reason != null) {
                        appendBounded(failures, "$stage:$reason", MAX_OBD_FAILURES, deduplicateConsecutive = false)
                    }
                    continue
                }

                // 3. ผลวินิจฉัยคำสั่ง ELM327/Mode01 ([OBD] command=0100 result=no_data ...)
                val commandMatch = obdCommandLine.find(payload)
                if (commandMatch != null) {
                    val cmd = commandMatch.groupValues[1]
                    val result = commandMatch.groupValues[2]
                    if (result in obdCommandResultAllowed) {
                        appendBounded(commands, "$cmd:$result", MAX_OBD_COMMANDS, deduplicateConsecutive = true)
                    }
                    continue
                }

                // 4. รายการ Mode 01 supported PIDs ([OBD] Mode 01 supported_pids=[05 0C 42] missing_range=none)
                val inventoryMatch = obdInventoryLine.find(payload)
                if (inventoryMatch != null) {
                    val parsedPids = inventoryMatch.groupValues[1]
                        .trim()
                        .split(Regex("\\s+"))
                        .map { it.uppercase() }
                        .filter { obdPidHex.matches(it) }
                        .distinct()
                        .take(MAX_OBD_PIDS)
                    val parsedMissing = inventoryMatch.groupValues[2].let {
                        if (it == "none") "none" else it.uppercase()
                    }
                    val parsedUnqueried = inventoryMatch.groupValues[3].takeIf { it.isNotEmpty() }?.uppercase()
                    // คงรายการ PID ที่ไม่ว่างไว้เมื่อรอบหลังตอบว่าง (ไม่ให้ข้อมูลที่เจอแล้วถูกทับหาย)
                    if (!inventorySeen || parsedPids.isNotEmpty() || supportedPids.isEmpty()) {
                        supportedPids = parsedPids
                        missingRange = parsedMissing
                        unqueriedRange = parsedUnqueried
                    }
                    inventorySeen = true
                    continue
                }

                // 5. นับจำนวนตัวอย่างข้อมูลสดที่อ่านได้โดยไม่เก็บค่าดิบ/ข้อความอิสระ
                if (payload.startsWith("sample ")) {
                    samplesSeen++
                    continue
                }

                // 6. เหตุการณ์วงจร OBD แบบ allowlist (v0.2.13 + v0.2.14 structured tokens)
                val invRetry = obdInventoryRetry.find(payload)?.groupValues?.get(1)
                    ?.takeIf { it in obdEventValueAllowed }
                if (invRetry != null) {
                    appendBounded(events, "inventory_retry:$invRetry", MAX_OBD_EVENTS, deduplicateConsecutive = true)
                    continue
                }

                if (payload.startsWith("settle_wait")) {
                    val delayMs = obdDelayToken.find(payload)?.groupValues?.get(1)
                    val token = if (delayMs != null) "settle_wait(${delayMs}ms)" else "settle_wait"
                    appendBounded(events, token, MAX_OBD_EVENTS, deduplicateConsecutive = true)
                    continue
                }

                val fallbackMode = obdRfcommFallback.find(payload)?.groupValues?.get(1)
                if (fallbackMode != null) {
                    appendBounded(events, "rfcomm_fallback:$fallbackMode", MAX_OBD_EVENTS, deduplicateConsecutive = true)
                    continue
                }

                val elmInitState = obdElmInitEvent.find(payload)?.groupValues?.get(1)
                if (elmInitState != null) {
                    val details = buildList {
                        obdBannerResync.find(payload)?.groupValues?.get(1)?.let { add("resync=$it") }
                        obdRetriesToken.find(payload)?.groupValues?.get(1)?.let { add("retries=$it") }
                    }.joinToString(";")
                    val token = if (details.isEmpty()) "elm_init:$elmInitState" else "elm_init:$elmInitState($details)"
                    appendBounded(events, token, MAX_OBD_EVENTS, deduplicateConsecutive = true)
                    continue
                }

                if (payload.startsWith("retry_scheduled")) {
                    val suffix = buildList {
                        obdAttemptToken.find(payload)?.groupValues?.get(1)?.let { add("#$it") }
                        obdDelayToken.find(payload)?.groupValues?.get(1)?.let { add("${it}ms") }
                    }.joinToString("@")
                    val token = if (suffix.isEmpty()) "retry_scheduled" else "retry_scheduled($suffix)"
                    appendBounded(events, token, MAX_OBD_EVENTS, deduplicateConsecutive = true)
                    continue
                }

                if (payload.startsWith("skipped ") || payload.startsWith("aborted ")) {
                    val verb = payload.substringBefore(' ')
                    val reason = obdReasonToken.find(payload)?.groupValues?.get(1)
                        ?.takeIf { it in obdGuardReasonAllowed }
                    if (reason != null) {
                        appendBounded(events, "$verb:$reason", MAX_OBD_EVENTS, deduplicateConsecutive = true)
                    }
                    continue
                }

                val kvMatch = obdEventKeyValue.find(payload)
                if (kvMatch != null) {
                    val key = kvMatch.groupValues[1]
                    val value = kvMatch.groupValues[2]
                    if (value in obdEventValueAllowed) {
                        val suffix = buildList {
                            obdAttemptToken.find(payload)?.groupValues?.get(1)?.let { add("#$it") }
                            obdDelayToken.find(payload)?.groupValues?.get(1)?.let { add("${it}ms") }
                        }.joinToString("@")
                        val token = if (suffix.isEmpty()) "$key:$value" else "$key:$value($suffix)"
                        appendBounded(events, token, MAX_OBD_EVENTS, deduplicateConsecutive = true)
                    }
                }
            }

            val hasEvidence = lastPhase != null ||
                failures.isNotEmpty() ||
                commands.isNotEmpty() ||
                inventorySeen ||
                samplesSeen > 0 ||
                events.isNotEmpty()
            if (!hasEvidence) return ConnectionIssueObdEvidence()
            return ConnectionIssueObdEvidence(
                lastPhase = lastPhase,
                phaseHistory = phaseHistory,
                failures = failures,
                commands = commands,
                supportedPids = supportedPids,
                inventorySeen = inventorySeen,
                missingRange = missingRange,
                unqueriedRange = unqueriedRange,
                samplesSeen = samplesSeen,
                events = events,
                hasEvidence = true,
            )
        }

        private fun appendBounded(
            list: MutableList<String>,
            item: String,
            limit: Int,
            deduplicateConsecutive: Boolean,
        ) {
            if (deduplicateConsecutive && list.lastOrNull() == item) return
            if (list.size == limit) list.removeAt(0)
            list.add(item)
        }

        private fun parseTimestamp(rawLine: String): OffsetDateTime? =
            timestamp.find(rawLine)?.groupValues?.get(1)?.let {
                runCatching { OffsetDateTime.parse(it) }.getOrNull()
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
