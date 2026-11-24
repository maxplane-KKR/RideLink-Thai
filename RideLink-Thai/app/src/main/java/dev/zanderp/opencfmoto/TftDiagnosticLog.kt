package dev.zanderp.opencfmoto

import android.content.res.Resources
import android.os.SystemClock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TftDiagnosticLog(private val file: File, private val maxChars: Int = 32 * 1024) {
    fun read(): String = if (file.isFile) file.readText() else ""

    fun append(event: String) {
        val entries = read() + event + "\n"
        val overflow = entries.length - maxChars
        val first = if (overflow > 0) entries.indexOf('\n', overflow - 1) + 1 else 0
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText(entries.substring(first))
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

}

/** Persists selected diagnostic fields rather than the raw protocol log. */
internal object TftDiagnostics {
    private const val DIAG_SUMMARY_TAIL_LINES = 30
    private const val TRACE_TAIL_LINES = 50
    private const val STARTUP_WINDOW_LINES = 20
    private const val BIKE_WINDOW_LINES = 24
    private const val BIKE_JOIN_LOOKBACK_LINES = 10

    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(worker)
    private lateinit var store: TftDiagnosticLog
    private lateinit var traceStore: TftDiagnosticLog
    private lateinit var resources: Resources
    private val versionPattern = Regex("[A-Za-z0-9][A-Za-z0-9 ._/+()-]{0,63}")
    private val standbyRefusalPattern = Regex("""head unit server :5277 no answer \(try (\d+)\)""")
    private val quotedSecretPattern = Regex(
        """\b(pwd|password|psk|passphrase)\s*[:=]\s*(['"])([^'"]*)\2""",
        RegexOption.IGNORE_CASE,
    )
    private val macAddressPattern = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")
    private val wifiJoinRequestSsidPattern = Regex("""(\brequesting Wi-Fi join:\s*)(.+?)(\s*(?:…|\.\.\.|\(|—|--|\s+-|$))""")
    private val wifiJoinedSsidPattern = Regex(
        """(\bWi-Fi (?:joined via system auto-connect|joined|re-acquired|already joined):\s*)(.+?)(\s*(?:\(|—|--|\s+-|$))""",
    )
    private val labeledQuotedSsidPattern = Regex(
        """(\b(?:ssid|group name)\s*[:=]\s*)(['"])([^'"]*)\2""",
        RegexOption.IGNORE_CASE,
    )
    private val labeledSsidPattern = Regex(
        """(\b(?:ssid|group name)=)([^\s,'"]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val carbitHexSsidPattern = Regex("""\b[0-9A-Fa-f]{16}\b""")
    private val startupTiming = ConnectionStartupTiming { event ->
        if (::store.isInitialized) record(event)
    }

    fun initialize(directory: File, appResources: Resources) {
        store = TftDiagnosticLog(File(directory, "tft-diagnostics.txt"))
        traceStore = TftDiagnosticLog(File(directory, "tft-session-trace.txt"), 128 * 1024)
        resources = appResources
        record("collection_schema=4 app_version=${BuildConfig.VERSION_NAME} build=${BuildConfig.VERSION_CODE}")
    }

    fun recordTraceLine(line: String) {
        if (!::traceStore.isInitialized) return
        if (!isDiagnosticTraceLine(line)) return
        val sanitized = safeTraceLine(line.trim()).replace(Regex("""[\r\n]+"""), " ")
        scope.launch {
            runCatching { traceStore.append(sanitized) }
        }
    }

    private fun isStandbyRefusalSpam(line: String): Boolean =
        standbyRefusalPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 5 } == true

    internal fun isDiagnosticTraceLine(line: String): Boolean {
        val markers = listOf(
            "[Session]", "[WiFi]", "[PXC]", "[PROBE]", "[DISC]", "[profile]", "[panel]",
            "CLIENT_INFO", "CHECK_SN", "CAR_CTRL", "CAR_DATA", "REQ_CONFIG_CAPTURE",
            "REQ_RV_DATA_START", "sent frame #1 ", "linkProps", "our IP=", "[state]",
            "[reconnect]", "!!", "[AA]", "[DASHBOARD]", "[OBD]",
        )
        if (
            line.contains("TOUCH MOVE") ||
            line.contains("hb#") ||
            line.contains("heartbeat #") ||
            (line.contains("sent frame #") && !line.contains("sent frame #1 ")) ||
            line.contains("decode fps=") ||
            isStandbyRefusalSpam(line)
        ) return false
        return markers.any { line.contains(it) }
    }

    fun recordConnection(phase: Phase, reason: Int?, versions: ConnectionState.ProjectionVersions?) {
        val reasonName = reason?.let { runCatching { resources.getResourceEntryName(it) }.getOrNull() }
        val event = buildString {
            append("connection=").append(phase.name)
            if (reasonName != null) append(" reason=").append(reasonName)
            formatVersion(versions?.client)?.let { append(" projection_app=").append(it) }
            formatVersion(versions?.sdk)?.let { append(" projection_sdk=").append(it) }
        }
        record(event)
    }

    fun recordFactoryCopy(status: TftFactoryBackup.CopyStatus) = record("factory_copy=${status.name}")

    internal fun recordLiveDashboard(event: String) {
        if (::store.isInitialized) record(event)
    }

    internal fun beginConnectionStartup(requestedAtMs: Long) = startupTiming.begin(requestedAtMs)

    internal fun markConnectionStartup(stage: ConnectionStartupStage, nowMs: Long = SystemClock.elapsedRealtime()) =
        startupTiming.mark(stage, nowMs)

    internal fun stopConnectionStartup() = startupTiming.stop()

    fun recordWrappedHeadunitIdentity() = record("headunit_identity=wrapped_loaded")

    fun recordBikeConnectionStart(modelId: String?) {
        val model = formatVersion(modelId) ?: "unreported"
        record("bike_connection=start app_version=${BuildConfig.VERSION_NAME} build=${BuildConfig.VERSION_CODE} qr_model=$model")
    }

    fun recordProjectionClientInfo(field: (String) -> String?) {
        record(projectionClientInfo(field))
        record(projectionClientMetadata(field))
    }

    internal fun projectionClientMetadata(field: (String) -> String?): String {
        fun value(key: String) = formatVersion(field(key)) ?: "unreported"
        fun flag(key: String) = when (field(key)?.lowercase()) {
            "true" -> "true"
            "false" -> "false"
            else -> "unreported"
        }
        return "client_metadata=received" +
            " car_brand=${value("carBrand")}" +
            " car_model=${value("carModel")}" +
            " product_type=${value("productType")}" +
            " screen_type=${value("screenType")}" +
            " pxc_version=${value("pxcVersion")}" +
            " support_rv_adb=${flag("supportRVForAdb")}" +
            " support_ota=${flag("supportOTAUpdate")}" +
            " support_third_party=${flag("supportThirdPartyApp")}"
    }

    fun recordClientInfoFields(keys: Sequence<String>) {
        record(clientInfoFields(keys))
    }

    internal fun clientInfoFields(keys: Sequence<String>): String {
        val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,47}")
        val relevant = Regex("firmware|software|version|model|resolution|display|screen|dashboard|tft|adb|ota|launcher", RegexOption.IGNORE_CASE)
        val names = keys.filter { keyPattern.matches(it) && relevant.containsMatchIn(it) }
            .distinct().sorted().take(40).toList()
        return "client_fields=" + names.joinToString(",").ifEmpty { "none" }
    }

    fun recordPortProbe(port: Int, result: TftPortProbe.Result) {
        record("bike_port=$port result=${result.name}")
    }

    fun recordPxcFrame(event: String) = record(event)

    /** Also sanitize old persisted traces before they enter the copied support report. */
    internal fun safeTraceLine(line: String): String {
        val preMasked = quotedSecretPattern.replace(line) { m ->
            "${m.groupValues[1]}=${m.groupValues[2]}«redacted»${m.groupValues[2]}"
        }
        val safe = LogRedactor.redact(preMasked)
        val rawMarkers = listOf(
            "*** CLIENT_INFO from bike ***", "→ CLIENT_INFO reply", "CHECK_SN from bike:", "→ CHECK_SN_RESULT",
        )
        for (marker in rawMarkers) {
            val at = safe.indexOf(marker)
            if (at >= 0) return maskTraceIdentifiers(safe.substring(0, at + marker.length) + " [payload omitted]")
        }
        if (safe.contains("CFDL26 ctrl ")) {
            val prefix = Regex("""^.*?CFDL26 ctrl\s+0x[0-9a-fA-F]+.*?\blen=\d+""").find(safe)
            if (prefix != null) return maskTraceIdentifiers(prefix.value + " [payload omitted]")
        }
        if (safe.contains("cmd=0x")) {
            val prefix = Regex("""^.*?\bcmd=0x[0-9a-fA-F]+.*?\blen=\d+""").find(safe)
            if (prefix != null) return maskTraceIdentifiers(prefix.value + " [payload omitted]")
        }
        return maskTraceIdentifiers(safe)
    }

    private fun maskTraceIdentifiers(text: String): String {
        var out = macAddressPattern.replace(text, "••:••:••:••:••:••")
        out = wifiJoinRequestSsidPattern.replace(out) { m ->
            m.groupValues[1] + ProjectionHarness.maskIdentifier(m.groupValues[2], masked = true) + m.groupValues[3]
        }
        out = wifiJoinedSsidPattern.replace(out) { m ->
            m.groupValues[1] + ProjectionHarness.maskIdentifier(m.groupValues[2], masked = true) + m.groupValues[3]
        }
        out = labeledQuotedSsidPattern.replace(out) { m ->
            m.groupValues[1] + m.groupValues[2] +
                ProjectionHarness.maskIdentifier(m.groupValues[3], masked = true) + m.groupValues[2]
        }
        out = labeledSsidPattern.replace(out) { m ->
            m.groupValues[1] + ProjectionHarness.maskIdentifier(m.groupValues[2], masked = true)
        }
        out = carbitHexSsidPattern.replace(out) { m ->
            ProjectionHarness.maskIdentifier(m.value, masked = true)
        }
        return out
    }

    internal fun projectionClientInfo(field: (String) -> String?): String {
        fun value(vararg keys: String) =
            keys.firstNotNullOfOrNull { formatVersion(field(it)) } ?: "unreported"

        // Keep explicit dashboard metadata separate from generic firmware and projection versions.
        val dashboardVersion = sequenceOf(
            "dashboardFirmwareVersion", "dashboard_firmware_version",
            "dashboardVersion", "dashboard_version",
            "tftFirmwareVersion", "tft_firmware_version", "tftVersion", "tft_version",
        ).firstNotNullOfOrNull { key -> formatVersion(field(key))?.let { key to it } }

        return buildString {
            append("client_info=received")
            append(" model=").append(value("modelId", "model_id", "model"))
            append(" projection_app=").append(value("version_name"))
            append(" projection_sdk=").append(value("sdkVersion"))
            append(" reported_firmware=").append(value("firmwareVersion", "firmware_version", "firmware"))
            append(" reported_hardware=").append(value("hardwareVersion", "hardware_version"))
            append(" dashboard_firmware_reported=").append(dashboardVersion?.second ?: "unreported")
            dashboardVersion?.let { append(" dashboard_firmware_field=").append(it.first) }
        }
    }

    fun recordInvalidProjectionClientInfo() = record("client_info=invalid")

    fun recordProjectionCanvas(width: Int, height: Int, fps: Int, outputWidth: Int, outputHeight: Int) {
        record("capture_request=${width}x$height fps=$fps capture_output=${outputWidth}x$outputHeight")
    }

    fun formatVersion(input: String?): String? = input?.trim()?.takeIf(versionPattern::matches)

    enum class ClientInfoStatus { NOT_FOUND, INVALID_PACKET, UNREPORTED, REPORTED }

    data class EvidenceSummary(
        val clientInfoStatus: ClientInfoStatus,
        val qrModel: String? = null,
        val reportedModel: String? = null,
        val projectionAppVersion: String? = null,
        val projectionSdkVersion: String? = null,
        val genericFirmwareVersion: String? = null,
        val hardwareVersion: String? = null,
        val dashboardFirmwareReported: String? = null,
        val dashboardFirmwareField: String? = null,
        val manualModelOnly: String? = null,
        val manualFirmwareVersion: String? = null,
        val requestedCanvas: String? = null,
        val outputCanvas: String? = null,
        val requestedFps: Int? = null,
        val profileCanvas: String = "800x480",
        val previewCanvas: String = "640x384",
    ) {
        val hasConfirmedDashboardFirmware: Boolean
            get() = clientInfoStatus == ClientInfoStatus.REPORTED &&
                dashboardFirmwareReported != null &&
                (manualFirmwareVersion == null || manualFirmwareVersion.equals(dashboardFirmwareReported, ignoreCase = true))

        val hasFirmwareConflict: Boolean
            get() = dashboardFirmwareReported != null &&
                manualFirmwareVersion != null &&
                !manualFirmwareVersion.equals(dashboardFirmwareReported, ignoreCase = true)
    }

    internal fun inspectEvidence(logText: String): EvidenceSummary {
        var clientInfoStatus = ClientInfoStatus.NOT_FOUND
        var qrModel: String? = null
        var reportedModel: String? = null
        var projectionAppVersion: String? = null
        var projectionSdkVersion: String? = null
        var genericFirmwareVersion: String? = null
        var hardwareVersion: String? = null
        var dashboardFirmwareReported: String? = null
        var dashboardFirmwareField: String? = null
        var manualModelOnly: String? = null
        var manualFirmwareVersion: String? = null
        var requestedCanvas: String? = null
        var outputCanvas: String? = null
        var requestedFps: Int? = null

        val knownKeys = setOf(
            "bike_connection", "app_version", "build", "qr_model",
            "client_info", "model", "projection_app", "projection_sdk",
            "reported_firmware", "reported_hardware", "dashboard_firmware_reported", "dashboard_firmware_field",
            "dashboard_firmware_manual", "capture_request", "fps", "capture_output",
            "connection", "reason", "factory_copy", "headunit_identity",
        )

        fun parseFields(line: String): Map<String, String> {
            val payload = line.substringAfter(' ', missingDelimiterValue = line).let {
                if (line.firstOrNull()?.isDigit() == true && line.contains("T")) it else line
            }
            val result = linkedMapOf<String, String>()
            val tokenRegex = Regex("""([a-z_]+)=""")
            val tokens = tokenRegex.findAll(payload).filter { it.groupValues[1] in knownKeys }.toList()
            for (i in tokens.indices) {
                val key = tokens[i].groupValues[1]
                val valueStart = tokens[i].range.last + 1
                val valueEnd = if (i + 1 < tokens.size) tokens[i + 1].range.first else payload.length
                val rawVal = payload.substring(valueStart, valueEnd).trim()
                result[key] = rawVal
            }
            return result
        }

        fun cleanReported(value: String?): String? =
            value?.takeUnless { it == "unreported" }?.let(::formatVersion)

        for (rawLine in logText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val fields = parseFields(line)
            if (fields["bike_connection"] == "start") {
                qrModel = null
                clientInfoStatus = ClientInfoStatus.NOT_FOUND
                reportedModel = null
                projectionAppVersion = null
                projectionSdkVersion = null
                genericFirmwareVersion = null
                hardwareVersion = null
                dashboardFirmwareReported = null
                dashboardFirmwareField = null
                requestedCanvas = null
                outputCanvas = null
                requestedFps = null
            }
            fields["qr_model"]?.let { qrModel = cleanReported(it) }
            when (fields["client_info"]) {
                "invalid" -> {
                    clientInfoStatus = ClientInfoStatus.INVALID_PACKET
                    reportedModel = null
                    dashboardFirmwareReported = null
                    dashboardFirmwareField = null
                }
                "received" -> {
                    reportedModel = cleanReported(fields["model"])
                    projectionAppVersion = cleanReported(fields["projection_app"]) ?: projectionAppVersion
                    projectionSdkVersion = cleanReported(fields["projection_sdk"]) ?: projectionSdkVersion
                    genericFirmwareVersion = cleanReported(fields["reported_firmware"])
                    hardwareVersion = cleanReported(fields["reported_hardware"])
                    val dashFw = cleanReported(fields["dashboard_firmware_reported"])
                    val dashField = cleanReported(fields["dashboard_firmware_field"])
                    if (dashFw != null) {
                        clientInfoStatus = ClientInfoStatus.REPORTED
                        dashboardFirmwareReported = dashFw
                        dashboardFirmwareField = dashField
                    } else {
                        clientInfoStatus = ClientInfoStatus.UNREPORTED
                        dashboardFirmwareReported = null
                        dashboardFirmwareField = null
                    }
                }
            }
            if (fields["client_info"] == null) {
                cleanReported(fields["projection_app"])?.let { projectionAppVersion = it }
                cleanReported(fields["projection_sdk"])?.let { projectionSdkVersion = it }
            }
            fields["dashboard_firmware_manual"]?.let { manualRaw ->
                val formatted = formatVersion(manualRaw)
                if (formatted != null) {
                    if (formatted.equals("zontes 150x", ignoreCase = true) || formatted.equals("150x", ignoreCase = true)) {
                        manualModelOnly = formatted
                    } else {
                        manualFirmwareVersion = formatted
                    }
                }
            }
            fields["capture_request"]?.let { requestedCanvas = it }
            fields["capture_output"]?.let { outputCanvas = it }
            fields["fps"]?.toIntOrNull()?.let { requestedFps = it }
        }

        return EvidenceSummary(
            clientInfoStatus = clientInfoStatus,
            qrModel = qrModel,
            reportedModel = reportedModel,
            projectionAppVersion = projectionAppVersion,
            projectionSdkVersion = projectionSdkVersion,
            genericFirmwareVersion = genericFirmwareVersion,
            hardwareVersion = hardwareVersion,
            dashboardFirmwareReported = dashboardFirmwareReported,
            dashboardFirmwareField = dashboardFirmwareField,
            manualModelOnly = manualModelOnly,
            manualFirmwareVersion = manualFirmwareVersion,
            requestedCanvas = requestedCanvas,
            outputCanvas = outputCanvas,
            requestedFps = requestedFps,
        )
    }

    internal fun formatQuickReport(
        diagnosticsText: String,
        traceText: String,
        phoneInfo: String,
        appVersion: String,
        activeProfileName: String,
        savedQrModel: String?,
        maskedSsid: String?,
        headunitStatus: String,
        connectionPhase: String,
    ): String {
        val summary = inspectEvidence(diagnosticsText)
        val diagLines = diagnosticsText.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val traceLines = traceText.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot(::isStandbyRefusalSpam)
            .map(::safeTraceLine)
            .toList()
        val sessionStart = diagLines.indexOfLast { it.contains("bike_connection=start") }
        val currentSession = if (sessionStart >= 0) diagLines.drop(sessionStart) else emptyList()
        val liveLines = diagLines.filter { it.substringAfter(' ', it).startsWith("live_dashboard=") }.takeLast(24)
        val obdLines = traceLines.filter { it.contains("[OBD]") }
        val startupIndex = diagLines.indexOfLast { it.contains("startup=requested ") }
        val startupLines = if (startupIndex >= 0) {
            diagLines.drop(startupIndex).filter { it.substringAfter(' ', it).startsWith("startup=") }.take(8)
        } else emptyList()

        val clientInfoLabel = when (summary.clientInfoStatus) {
            ClientInfoStatus.REPORTED ->
                "ได้รับข้อมูล (model=${summary.reportedModel ?: "-"}, app=${summary.projectionAppVersion ?: "-"}, sdk=${summary.projectionSdkVersion ?: "-"}, dash_fw=${summary.dashboardFirmwareReported} [${summary.dashboardFirmwareField ?: "-"}])"
            ClientInfoStatus.UNREPORTED ->
                "ได้รับข้อมูล (model=${summary.reportedModel ?: "-"}, app=${summary.projectionAppVersion ?: "-"}, sdk=${summary.projectionSdkVersion ?: "-"}, generic_fw=${summary.genericFirmwareVersion ?: "-"}, dash_fw=unreported)"
            ClientInfoStatus.INVALID_PACKET -> "แพ็กเก็ต CLIENT_INFO อ่านไม่ได้ (invalid)"
            ClientInfoStatus.NOT_FOUND -> "ยังไม่พบแพ็กเก็ต CLIENT_INFO จากจอรถ"
        }

        return buildString {
            appendLine("=== รายงานตรวจสอบ Zontes / RideLink TH ===")
            appendLine("แอป: v$appVersion | มือถือ: $phoneInfo")
            appendLine("สถานะล่าสุด: $connectionPhase | Head Unit: $headunitStatus")
            appendLine("โปรไฟล์ที่เลือก: $activeProfileName | QR รุ่น: ${savedQrModel ?: summary.qrModel ?: "-"} | Wi-Fi: ${maskedSsid ?: "-"}")
            appendLine("ข้อมูลจากจอรถ (CLIENT_INFO): $clientInfoLabel")
            appendLine(
                "ขนาดภาพที่จอรถขอ: ${summary.requestedCanvas ?: "-"} @ ${summary.requestedFps ?: "-"}fps" +
                    " -> ส่งออก ${summary.outputCanvas ?: "-"} (โปรไฟล์ ${summary.profileCanvas})",
            )
            currentSession.lastOrNull { it.contains("client_metadata=received") }?.let { appendLine(it) }
            currentSession.lastOrNull { it.contains("client_fields=") }?.let { appendLine(it) }
            for (port in listOf(23, 5555)) {
                currentSession.lastOrNull { it.contains("bike_port=$port ") }?.let { appendLine(it) }
            }
            val pxcLines = currentSession.filter { it.contains("pxc_frame=") || it.contains("pxc_frame_limit=") }
            if (pxcLines.isNotEmpty()) {
                appendLine("--- แพ็กเก็ตควบคุมจากรถ (ไม่เก็บ payload) ---")
                pxcLines.forEach { appendLine(it) }
            }
            if (liveLines.isNotEmpty()) {
                appendLine("--- หน้าปัดสด (สถานะในแอป ไม่ยืนยันภาพบนจอรถ) ---")
                liveLines.forEach { appendLine(it) }
            }
            if (startupLines.isNotEmpty()) {
                appendLine("--- เวลาตั้งแต่กดเริ่มเชื่อม (มิลลิวินาที) ---")
                startupLines.forEach { appendLine(it) }
            }
            if (obdLines.isNotEmpty()) {
                appendLine("--- บันทึก OBD ล่าสุด ---")
                val inventoryIndex = obdLines.indexOfLast {
                    it.contains("supported_pids=") && !it.endsWith("missing_range=00")
                }
                val sampleIndex = obdLines.indexOfLast { it.contains("[OBD] sample ") }
                val recentStart = (obdLines.size - 20).coerceAtLeast(0)
                obdLines.forEachIndexed { index, line ->
                    if (index >= recentStart || index == inventoryIndex || index == sampleIndex) appendLine(line)
                }
            }
            appendLine()
            appendLine("--- บันทึกสถานะย่อ (${diagLines.size.coerceAtMost(DIAG_SUMMARY_TAIL_LINES)} รายการล่าสุด) ---")
            if (diagLines.isEmpty()) {
                appendLine("(ยังไม่มีบันทึกการเชื่อมรถ)")
            } else {
                diagLines.takeLast(DIAG_SUMMARY_TAIL_LINES).forEach { appendLine(it) }
            }
            if (traceLines.isNotEmpty()) {
                val tailStart = (traceLines.size - TRACE_TAIL_LINES).coerceAtLeast(0)
                val startupRange = latestStartupTraceRange(traceLines, tailStart)
                val startupTrace = startupRange?.let { traceLines.subList(it.first, it.last + 1) }.orEmpty()
                if (startupTrace.isNotEmpty()) {
                    appendLine()
                    appendLine("--- ช่วงเริ่มเชื่อมล่าสุด ---")
                    startupTrace.forEach { appendLine(it) }
                }
                val bikeSelection = latestBikeSessionTraceRange(traceLines)
                if (bikeSelection != null) {
                    val (bikeRange, anchorIdx) = bikeSelection
                    val bikeWindow = traceLines.subList(bikeRange.first, bikeRange.last + 1)
                    val coveredByStartup = startupRange != null &&
                        bikeRange.first in startupRange &&
                        anchorIdx in startupRange
                    if (bikeWindow.isNotEmpty() && bikeRange.first < tailStart && bikeWindow != startupTrace && !coveredByStartup) {
                        appendLine()
                        appendLine("--- ช่วงเชื่อมรถล่าสุด (Wi-Fi / PXC) ---")
                        bikeWindow.forEach { appendLine(it) }
                    }
                }
                appendLine()
                appendLine("--- ลำดับเหตุการณ์ Wi-Fi / PXC (${traceLines.size.coerceAtMost(TRACE_TAIL_LINES)} บรรทัดล่าสุด) ---")
                traceLines.takeLast(TRACE_TAIL_LINES).forEach { appendLine(it) }
            }
        }.trimEnd()
    }

    private fun isBikeSessionAnchor(line: String): Boolean =
        line.contains("[PXC] our IP=") ||
            line.contains("[WiFi] Wi-Fi joined") ||
            line.contains("[PROBE] *** accepted") ||
            line.contains("sent frame #1 ")

    internal fun latestBikeSessionTraceWindow(traceLines: List<String>): List<String> =
        latestBikeSessionTraceRange(traceLines)?.let { (range, _) ->
            traceLines.subList(range.first, range.last + 1)
        } ?: emptyList()

    private fun latestBikeSessionTraceRange(traceLines: List<String>): Pair<IntRange, Int>? {
        val anchorIdx = traceLines.indexOfLast(::isBikeSessionAnchor)
        if (anchorIdx < 0) return null

        val lookbackStart = (anchorIdx - BIKE_JOIN_LOOKBACK_LINES).coerceAtLeast(0)
        val joinIdx = (lookbackStart..anchorIdx)
            .lastOrNull { traceLines[it].contains("[WiFi] requesting Wi-Fi join:") }
        val startIdx = if (joinIdx != null) {
            joinIdx
        } else {
            val prevFirstFrame = (lookbackStart until anchorIdx)
                .lastOrNull { traceLines[it].contains("sent frame #1 ") }
            val sessionSearchStart = if (prevFirstFrame != null) prevFirstFrame + 1 else lookbackStart
            (sessionSearchStart..anchorIdx).firstOrNull { isBikeSessionAnchor(traceLines[it]) } ?: anchorIdx
        }

        val nextJoinAfterAnchor = ((anchorIdx + 1) until traceLines.size)
            .firstOrNull { traceLines[it].contains("[WiFi] requesting Wi-Fi join:") }
        val endExclusive = minOf(
            traceLines.size,
            startIdx + BIKE_WINDOW_LINES,
            nextJoinAfterAnchor ?: traceLines.size,
        )
        return (startIdx until endExclusive) to anchorIdx
    }

    private fun latestStartupTraceRange(traceLines: List<String>, tailStart: Int): IntRange? {
        val lastJoinIdx = traceLines.indexOfLast { it.contains("[WiFi] requesting Wi-Fi join:") }
        val lastIpIdx = traceLines.indexOfLast { it.contains("[PXC] our IP=") }
        val lastStartIdx = maxOf(lastJoinIdx, lastIpIdx)
        val firstFrameIdx = traceLines.indexOfLast { it.contains("sent frame #1 ") }
        val startIdx = if (firstFrameIdx >= 0 && lastStartIdx <= firstFrameIdx) {
            (0..firstFrameIdx).lastOrNull { traceLines[it].contains("[WiFi] requesting Wi-Fi join:") }
                ?: lastStartIdx
        } else if (lastJoinIdx >= 0 && lastIpIdx > lastJoinIdx) {
            lastJoinIdx
        } else {
            lastStartIdx
        }
        if (startIdx < 0 || startIdx >= tailStart) return null
        val firstFrameAfterStart = (startIdx until traceLines.size)
            .firstOrNull { traceLines[it].contains("sent frame #1 ") }
        val endExclusive = if (firstFrameAfterStart != null) {
            minOf(traceLines.size, firstFrameAfterStart + 2, startIdx + STARTUP_WINDOW_LINES)
        } else {
            minOf(traceLines.size, startIdx + STARTUP_WINDOW_LINES)
        }
        return startIdx until endExclusive
    }

    suspend fun read(): String = withContext(worker) { store.read() }

    suspend fun readTrace(): String = withContext(worker) {
        if (::traceStore.isInitialized) traceStore.read() else ""
    }

    private fun record(event: String) {
        val entry = "${OffsetDateTime.now()} $event"
        scope.launch {
            runCatching { store.append(entry) }
        }
    }
}
