package dev.zanderp.opencfmoto

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Thin Android UI adapter สำหรับการส่งรายงานปัญหาในขั้นตอนเดียว (1-step):
 * - ปุ่มหลักเป็น "ส่งรายงาน" (`R.string.tft_issue_send`) ส่งได้ทันทีโดยไม่เปิดหน้าต่างซ้อน
 * - มีส่วน "ดูรายละเอียดที่จะส่ง" แบบพับเก็บเริ่มต้น แสดงข้อความตรงกับที่ส่งจริงจาก [ConnectionIssueReport]
 * - ตรวจความถูกต้องผ่าน `setOnShowListener` + positive button listener เพื่อคงหน้าต่างเปิดเมื่อข้อมูลไม่ครบ
 */
internal class ConnectionIssueReportDialog(
    private val activity: AppCompatActivity,
    private val evidence: ConnectionIssueEvidence,
    private val phase: Phase,
    private val headunit: ProjectionHarness.HeadunitLinkStatus,
    private val sendReport: suspend (model: String, report: String) -> Boolean,
    private val showLegacyLog: () -> Unit,
) {
    private val textAdapter = AndroidConnectionIssueTextAdapter(activity)
    private val modelChoices = ConnectionIssueReport.modelChoices(textAdapter)
    private val otherModelIndex = ConnectionIssueReport.otherModelChoiceIndex(textAdapter)
    private val reportContext = ConnectionIssueContext(
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
        phaseLabel = activity.getString(phase.labelRes),
        headunitStatus = headunit.name,
    )

    fun show() {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        fun rounded(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            border?.let { setStroke(dp(1), it) }
        }

        val mutedColor = Color.rgb(174, 193, 216)
        val cyanColor = Color.rgb(103, 205, 255)
        val previewTextColor = Color.rgb(239, 245, 255)
        val successColor = Color.rgb(89, 220, 170)
        val errorColor = Color.rgb(255, 176, 164)

        val otherModel = input(R.string.tft_issue_model_hint, maxLength = 40, multiline = false).apply {
            visibility = View.GONE
        }
        val modelPicker = Spinner(activity, Spinner.MODE_DROPDOWN).apply {
            minimumHeight = dp(48)
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, modelChoices).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(0)
        }
        val symptom = input(R.string.tft_issue_symptom_hint, maxLength = 200, multiline = true)
        val displayChoices = listOf(
            ConnectionIssueDisplayResult.UNKNOWN to R.string.tft_issue_display_unknown,
            ConnectionIssueDisplayResult.YES to R.string.tft_issue_display_yes,
            ConnectionIssueDisplayResult.NO to R.string.tft_issue_display_no,
        )
        val displayGroup = RadioGroup(activity)
        val choiceIds = displayChoices.map { (result, labelRes) ->
            result to RadioButton(activity).apply {
                id = View.generateViewId()
                setText(labelRes)
                displayGroup.addView(this)
            }.id
        }
        displayGroup.check(choiceIds.first().second)

        val previewText = TextView(activity).apply {
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(previewTextColor)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(17, 33, 55), 12, Color.rgb(53, 76, 105))
            visibility = View.GONE
        }

        val statusView = TextView(activity).apply {
            textSize = 14f
            setTextColor(mutedColor)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        var sending = false
        var reportSent = false

        fun currentDraft(): ConnectionIssueDraft {
            val selectedDisplay = choiceIds.firstOrNull { it.second == displayGroup.checkedRadioButtonId }?.first
                ?: ConnectionIssueDisplayResult.UNKNOWN
            return ConnectionIssueDraft(
                modelChoiceIndex = modelPicker.selectedItemPosition.coerceAtLeast(0),
                otherModel = otherModel.text.toString(),
                symptom = symptom.text.toString(),
                displayResult = selectedDisplay,
            )
        }

        fun buildCurrentReport(): ConnectionIssueBuildResult =
            ConnectionIssueReport.build(currentDraft(), evidence, reportContext, textAdapter)

        fun refreshPreviewIfVisible() {
            if (previewText.visibility == View.VISIBLE) {
                previewText.text = buildCurrentReport().payload.report
            }
        }

        fun clearValidationFeedback() {
            otherModel.error = null
            symptom.error = null
            if (!sending && !reportSent) {
                statusView.visibility = View.GONE
            }
        }

        val toggleDetailsButton = Button(activity).apply {
            setText(R.string.tft_issue_details_show)
            isAllCaps = false
            setTextColor(cyanColor)
            minHeight = dp(48)
            background = rounded(Color.rgb(17, 33, 55), 12, Color.rgb(53, 76, 105))
            setOnClickListener {
                val expanding = previewText.visibility != View.VISIBLE
                if (expanding) {
                    previewText.text = buildCurrentReport().payload.report
                }
                previewText.visibility = if (expanding) View.VISIBLE else View.GONE
                setText(if (expanding) R.string.tft_issue_details_hide else R.string.tft_issue_details_show)
            }
        }

        modelPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                otherModel.visibility = if (position == otherModelIndex) View.VISIBLE else View.GONE
                clearValidationFeedback()
                refreshPreviewIfVisible()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                otherModel.visibility = View.GONE
            }
        }
        otherModel.doAfterTextChanged {
            clearValidationFeedback()
            refreshPreviewIfVisible()
        }
        symptom.doAfterTextChanged {
            clearValidationFeedback()
            refreshPreviewIfVisible()
        }
        displayGroup.setOnCheckedChangeListener { _, _ ->
            clearValidationFeedback()
            refreshPreviewIfVisible()
        }

        fun LinearLayout.addSpaced(view: View, topDp: Int = 0) {
            addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(topDp)
                },
            )
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPaddingRelative(padding, padding / 2, padding, padding / 2)
            addSpaced(TextView(activity).apply { setText(R.string.tft_issue_intro) })
            addSpaced(modelPicker, topDp = 8)
            addSpaced(otherModel, topDp = 4)
            addSpaced(symptom, topDp = 4)
            addSpaced(TextView(activity).apply { setText(R.string.tft_issue_display_question) }, topDp = 8)
            addSpaced(displayGroup, topDp = 4)
            addSpaced(toggleDetailsButton, topDp = 12)
            addSpaced(previewText, topDp = 8)
            addSpaced(statusView, topDp = 10)
        }

        fun setInputsEnabled(enabled: Boolean) {
            modelPicker.isEnabled = enabled
            otherModel.isEnabled = enabled
            symptom.isEnabled = enabled
            for (i in 0 until displayGroup.childCount) {
                displayGroup.getChildAt(i).isEnabled = enabled
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.tft_issue_title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.tft_issue_legacy_log) { _, _ -> showLegacyLog() }
            .setPositiveButton(R.string.tft_issue_send, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)

        dialog.setOnShowListener {
            val sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val closeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val legacyButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            sendButton.setOnClickListener {
                if (sending || reportSent) return@setOnClickListener
                val result = buildCurrentReport()
                if (previewText.visibility == View.VISIBLE) {
                    previewText.text = result.payload.report
                }
                when (result) {
                    is ConnectionIssueBuildResult.Invalid -> {
                        val message = when (result.error) {
                            ConnectionIssueValidationError.OTHER_MODEL_REQUIRED -> {
                                val text = activity.getString(R.string.tft_issue_error_other_model)
                                otherModel.visibility = View.VISIBLE
                                otherModel.error = text
                                otherModel.requestFocus()
                                text
                            }
                            ConnectionIssueValidationError.DETAILS_REQUIRED -> {
                                val text = activity.getString(R.string.tft_issue_error_details_required)
                                symptom.error = text
                                symptom.requestFocus()
                                text
                            }
                        }
                        statusView.visibility = View.VISIBLE
                        statusView.setTextColor(errorColor)
                        statusView.text = message
                    }
                    is ConnectionIssueBuildResult.Ready -> {
                        sending = true
                        setInputsEnabled(false)
                        sendButton.isEnabled = false
                        sendButton.alpha = 0.6f
                        closeButton.isEnabled = false
                        legacyButton.isEnabled = false
                        dialog.setCancelable(false)
                        statusView.visibility = View.VISIBLE
                        statusView.setTextColor(mutedColor)
                        statusView.setText(R.string.tft_issue_sending)
                        activity.lifecycleScope.launch {
                            val sent = try {
                                sendReport(result.payload.model, result.payload.report)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@launch
                            dialog.setCancelable(true)
                            closeButton.isEnabled = true
                            legacyButton.isEnabled = true
                            if (sent) {
                                sending = false
                                reportSent = true
                                statusView.setTextColor(successColor)
                                statusView.setText(R.string.tft_issue_send_success)
                            } else {
                                sending = false
                                setInputsEnabled(true)
                                sendButton.isEnabled = true
                                sendButton.alpha = 1f
                                statusView.setTextColor(errorColor)
                                statusView.setText(R.string.tft_issue_send_error)
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun input(@StringRes hintRes: Int, maxLength: Int, multiline: Boolean) = EditText(activity).apply {
        setHint(hintRes)
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
        if (multiline) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setMinLines(2)
            maxLines = 4
        } else {
            setSingleLine()
        }
    }

    private class AndroidConnectionIssueTextAdapter(
        private val activity: AppCompatActivity,
    ) : ConnectionIssueTextAdapter {
        override val unknown: String get() = activity.getString(R.string.tft_issue_unknown)
        override val selectModelLabel: String get() = activity.getString(R.string.tft_issue_model_select)
        override val otherModelLabel: String get() = activity.getString(R.string.tft_issue_model_other)
        override val reportTitle: String get() = activity.getString(R.string.tft_issue_report_title)
        override val reportModelLabel: String get() = activity.getString(R.string.tft_issue_report_model)
        override val timelineTitle: String get() = activity.getString(R.string.tft_issue_timeline)
        override val timelineEmpty: String get() = activity.getString(R.string.tft_issue_timeline_empty)
        override val diagnosticsTitle: String get() = activity.getString(R.string.tft_issue_diagnostics_title)

        override fun stageLabel(stage: ConnectionIssueTimelineStage): String = activity.getString(
            when (stage) {
                ConnectionIssueTimelineStage.REQUESTED -> R.string.tft_issue_stage_requested
                ConnectionIssueTimelineStage.HEADUNIT_READY -> R.string.tft_issue_stage_headunit
                ConnectionIssueTimelineStage.WIFI_REQUESTED -> R.string.tft_issue_stage_wifi_requested
                ConnectionIssueTimelineStage.WIFI_AVAILABLE -> R.string.tft_issue_stage_wifi_available
                ConnectionIssueTimelineStage.PXC_STARTED -> R.string.tft_issue_stage_display
                ConnectionIssueTimelineStage.FIRST_FRAME -> R.string.tft_issue_stage_first_frame
            },
        )

        override fun displayResultLabel(result: ConnectionIssueDisplayResult): String = activity.getString(
            when (result) {
                ConnectionIssueDisplayResult.UNKNOWN -> R.string.tft_issue_display_unknown
                ConnectionIssueDisplayResult.YES -> R.string.tft_issue_display_yes
                ConnectionIssueDisplayResult.NO -> R.string.tft_issue_display_no
            },
        )

        override fun formatSymptomLine(symptom: String): String =
            activity.getString(R.string.tft_issue_report_symptom, symptom)

        override fun formatDisplayLine(displayLabel: String): String =
            activity.getString(R.string.tft_issue_report_display, displayLabel)

        override fun formatTimelineStarted(formattedTime: String): String =
            activity.getString(R.string.tft_issue_timeline_started, formattedTime)

        override fun formatStageTime(stageLabel: String, elapsedMs: Long): String =
            activity.getString(R.string.tft_issue_stage_time, stageLabel, elapsedMs)

        override fun formatReportDetails(
            appVersion: String,
            deviceInfo: String,
            phaseLabel: String,
            headunitStatus: String,
            lastStep: String,
            qrModel: String,
            displayModel: String,
            displayFirmware: String,
        ): String = activity.getString(
            R.string.tft_issue_report_details,
            appVersion,
            deviceInfo,
            phaseLabel,
            headunitStatus,
            lastStep,
            qrModel,
            displayModel,
            displayFirmware,
        )
    }
}
