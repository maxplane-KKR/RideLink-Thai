package dev.zanderp.opencfmoto

import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class ConnectionIssueDisplayResult {
    UNKNOWN, YES, NO
}

internal data class ConnectionIssueDraft(
    val modelChoiceIndex: Int = 0,
    val otherModel: String = "",
    val symptom: String = "",
    val displayResult: ConnectionIssueDisplayResult = ConnectionIssueDisplayResult.UNKNOWN,
)

internal data class ConnectionIssueContext(
    val appVersion: String,
    val deviceInfo: String,
    val phaseLabel: String,
    val headunitStatus: String,
)

internal enum class ConnectionIssueValidationError {
    OTHER_MODEL_REQUIRED,
    DETAILS_REQUIRED,
}

internal data class ConnectionIssueSubmissionPayload(
    val model: String,
    val report: String,
)

internal sealed interface ConnectionIssueBuildResult {
    val payload: ConnectionIssueSubmissionPayload

    data class Ready(
        override val payload: ConnectionIssueSubmissionPayload,
    ) : ConnectionIssueBuildResult

    data class Invalid(
        val error: ConnectionIssueValidationError,
        override val payload: ConnectionIssueSubmissionPayload,
    ) : ConnectionIssueBuildResult
}

/**
 * Seam สำหรับข้อความแปลภาษาของรายงานปัญหา — แยก pure-Kotlin report assembly
 * ออกจาก Android Resources เพื่อใช้ได้ทั้งใน UI จริงและ JUnit tests โดยไม่ต้องพึ่ง Instrumentation
 */
internal interface ConnectionIssueTextAdapter {
    val unknown: String
    val selectModelLabel: String
    val otherModelLabel: String
    val reportTitle: String
    val reportModelLabel: String
    val timelineTitle: String
    val timelineEmpty: String
    val diagnosticsTitle: String

    fun stageLabel(stage: ConnectionIssueTimelineStage): String
    fun displayResultLabel(result: ConnectionIssueDisplayResult): String
    fun formatSymptomLine(symptom: String): String
    fun formatDisplayLine(displayLabel: String): String
    fun formatTimelineStarted(formattedTime: String): String
    fun formatStageTime(stageLabel: String, elapsedMs: Long): String
    fun formatReportDetails(
        appVersion: String,
        deviceInfo: String,
        phaseLabel: String,
        headunitStatus: String,
        lastStep: String,
        qrModel: String,
        displayModel: String,
        displayFirmware: String,
    ): String
}

/**
 * Deep pure-Kotlin module รวมศูนย์การตรวจฟอร์มและการประกอบข้อความรายงาน 5 ส่วน
 * ให้พรีวิวแบบพับได้และข้อความที่ส่งเข้า Google Forms มาจากแหล่งเดียวกันเสมอ 100%
 */
internal object ConnectionIssueReport {
    private val startedAtFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss XXX", Locale.ROOT)
    private val whitespaceRun = Regex("""[\r\n\t ]+""")

    private val thaiModels: List<String> = ThaiZontesModel.entries.flatMap { model ->
        when (model) {
            ThaiZontesModel.Z368E -> listOf(model.label, "Zontes 368E ETC")
            ThaiZontesModel.Z368G -> listOf(model.label, "Zontes 368G 2026")
            else -> listOf(model.label)
        }
    }

    fun modelChoices(text: ConnectionIssueTextAdapter): List<String> =
        listOf(text.selectModelLabel) + thaiModels + text.otherModelLabel

    fun otherModelChoiceIndex(text: ConnectionIssueTextAdapter): Int =
        modelChoices(text).lastIndex

    fun build(
        draft: ConnectionIssueDraft,
        evidence: ConnectionIssueEvidence,
        context: ConnectionIssueContext,
        text: ConnectionIssueTextAdapter,
    ): ConnectionIssueBuildResult {
        val choices = modelChoices(text)
        val otherIndex = choices.lastIndex
        val safeChoice = draft.modelChoiceIndex.coerceIn(0, otherIndex)
        val normalizedOtherModel = normalizeSingleLine(draft.otherModel)
        val normalizedSymptom = normalizeSingleLine(draft.symptom)

        val resolvedModel = when (safeChoice) {
            0 -> text.unknown
            otherIndex -> normalizedOtherModel.ifBlank { text.unknown }
            else -> choices[safeChoice]
        }

        val lastStageLabel = when (evidence.lastStage) {
            ConnectionStartupStage.HEADUNIT_READY -> text.stageLabel(ConnectionIssueTimelineStage.HEADUNIT_READY)
            ConnectionStartupStage.WIFI_REQUESTED -> text.stageLabel(ConnectionIssueTimelineStage.WIFI_REQUESTED)
            ConnectionStartupStage.WIFI_AVAILABLE -> text.stageLabel(ConnectionIssueTimelineStage.WIFI_AVAILABLE)
            ConnectionStartupStage.PXC_STARTED -> text.stageLabel(ConnectionIssueTimelineStage.PXC_STARTED)
            ConnectionStartupStage.FIRST_FRAME -> text.stageLabel(ConnectionIssueTimelineStage.FIRST_FRAME)
            ConnectionStartupStage.REPEAT_REQUEST, null ->
                if (evidence.attemptStarted) text.stageLabel(ConnectionIssueTimelineStage.REQUESTED)
                else text.unknown
        }
        val lastStep = evidence.elapsedMs?.let { text.formatStageTime(lastStageLabel, it) } ?: lastStageLabel

        val symptomLine = text.formatSymptomLine(normalizedSymptom.ifBlank { text.unknown })
        val displayLine = text.formatDisplayLine(text.displayResultLabel(draft.displayResult))
        val startedAtLine = if (evidence.attemptStarted) {
            val formattedTime = evidence.startedAt?.format(startedAtFormatter) ?: text.unknown
            text.formatTimelineStarted(formattedTime)
        } else {
            null
        }
        val timelineLines = evidence.timeline.map { event ->
            text.formatStageTime(text.stageLabel(event.stage), event.elapsedMs)
        }.ifEmpty { listOf(text.timelineEmpty) }

        val detailsSection = text.formatReportDetails(
            appVersion = context.appVersion,
            deviceInfo = context.deviceInfo,
            phaseLabel = context.phaseLabel,
            headunitStatus = context.headunitStatus,
            lastStep = lastStep,
            qrModel = evidence.qrModel ?: text.unknown,
            displayModel = evidence.displayModel ?: text.unknown,
            displayFirmware = evidence.displayFirmware ?: text.unknown,
        )
        val diagnostics = evidence.structuredSummary()
        val report = listOf(
            text.reportTitle,
            "${text.reportModelLabel}: $resolvedModel\n$symptomLine\n$displayLine",
            "${text.timelineTitle}\n${(listOfNotNull(startedAtLine) + timelineLines).joinToString("\n")}",
            detailsSection,
            "${text.diagnosticsTitle}\n$diagnostics",
        ).joinToString("\n\n")

        val payload = ConnectionIssueSubmissionPayload(model = resolvedModel, report = report)

        if (safeChoice == otherIndex && normalizedOtherModel.isBlank()) {
            return ConnectionIssueBuildResult.Invalid(ConnectionIssueValidationError.OTHER_MODEL_REQUIRED, payload)
        }
        val hasRiderInput = safeChoice != 0 ||
            normalizedSymptom.isNotBlank() ||
            draft.displayResult != ConnectionIssueDisplayResult.UNKNOWN
        if (!evidence.hasDiagnosticEvidence && !hasRiderInput) {
            return ConnectionIssueBuildResult.Invalid(ConnectionIssueValidationError.DETAILS_REQUIRED, payload)
        }
        return ConnectionIssueBuildResult.Ready(payload)
    }

    private fun normalizeSingleLine(input: String): String =
        whitespaceRun.replace(input.trim(), " ").trim()
}
