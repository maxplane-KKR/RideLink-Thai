package dev.zanderp.opencfmoto

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
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
import androidx.lifecycle.lifecycleScope
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class ConnectionIssueReportDialog(
    private val activity: AppCompatActivity,
    private val evidence: ConnectionIssueEvidence,
    private val phase: Phase,
    private val headunit: ProjectionHarness.HeadunitLinkStatus,
    private val sendReport: suspend (model: String, report: String) -> Boolean,
    private val showLegacyLog: () -> Unit,
) {
    private val thaiModels = ThaiZontesModel.entries.flatMap { model ->
        when (model) {
            ThaiZontesModel.Z368E -> listOf(model.label, "Zontes 368E ETC")
            ThaiZontesModel.Z368G -> listOf(model.label, "Zontes 368G 2026")
            else -> listOf(model.label)
        }
    }
    private val modelChoices = listOf(activity.getString(R.string.tft_issue_model_select)) + thaiModels +
        activity.getString(R.string.tft_issue_model_other)

    private data class Draft(
        val modelChoice: Int = 0,
        val otherModel: String = "",
        val symptom: String = "",
        val displayResult: Int = 0,
    )

    fun show() = showForm(Draft())

    private fun showForm(draft: Draft) {
        val otherModel = input(R.string.tft_issue_model_hint, draft.otherModel, 40, multiline = false).apply {
            visibility = if (draft.modelChoice == modelChoices.lastIndex) View.VISIBLE else View.GONE
        }
        val modelPicker = Spinner(activity, Spinner.MODE_DROPDOWN).apply {
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, modelChoices).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(draft.modelChoice)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    otherModel.visibility = if (position == modelChoices.lastIndex) View.VISIBLE else View.GONE
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    otherModel.visibility = View.GONE
                }
            }
        }
        val symptom = input(R.string.tft_issue_symptom_hint, draft.symptom, 200, multiline = true)
        val displayChoices = listOf(R.string.tft_issue_display_unknown, R.string.tft_issue_display_yes, R.string.tft_issue_display_no)
        val displayGroup = RadioGroup(activity)
        val choiceIds = displayChoices.map { label ->
            RadioButton(activity).apply {
                id = View.generateViewId()
                setText(label)
                displayGroup.addView(this)
            }.id
        }
        displayGroup.check(choiceIds[draft.displayResult])
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPaddingRelative(padding, padding / 2, padding, padding / 2)
            addView(TextView(activity).apply { setText(R.string.tft_issue_intro) })
            addView(modelPicker)
            addView(otherModel)
            addView(symptom)
            addView(TextView(activity).apply { setText(R.string.tft_issue_display_question) })
            addView(displayGroup)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.tft_issue_title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.tft_issue_legacy_log) { _, _ -> showLegacyLog() }
            .setPositiveButton(R.string.tft_issue_preview) { _, _ ->
                showPreview(Draft(
                    modelPicker.selectedItemPosition.coerceAtLeast(0),
                    otherModel.text.toString().trim(),
                    symptom.text.toString().trim(),
                    choiceIds.indexOf(displayGroup.checkedRadioButtonId).coerceAtLeast(0),
                ), displayChoices)
            }
            .show()
    }

    private fun showPreview(draft: Draft, displayChoices: List<Int>) {
        val unknown = activity.getString(R.string.tft_issue_unknown)
        val model = when (draft.modelChoice) {
            0 -> unknown
            modelChoices.lastIndex -> draft.otherModel.ifBlank { unknown }
            else -> modelChoices[draft.modelChoice]
        }
        val lastStage = activity.getString(when (evidence.lastStage) {
            ConnectionStartupStage.HEADUNIT_READY -> R.string.tft_issue_stage_headunit
            ConnectionStartupStage.WIFI_REQUESTED -> R.string.tft_issue_stage_wifi_requested
            ConnectionStartupStage.WIFI_AVAILABLE -> R.string.tft_issue_stage_wifi_available
            ConnectionStartupStage.PXC_STARTED -> R.string.tft_issue_stage_display
            ConnectionStartupStage.FIRST_FRAME -> R.string.tft_issue_stage_first_frame
            ConnectionStartupStage.REPEAT_REQUEST, null -> if (evidence.attemptStarted) R.string.tft_issue_stage_requested
                else R.string.tft_issue_unknown
        })
        val step = evidence.elapsedMs?.let { activity.getString(R.string.tft_issue_stage_time, lastStage, it) } ?: lastStage
        val modelLabel = activity.getString(R.string.tft_issue_report_model)
        val symptom = activity.getString(R.string.tft_issue_report_symptom, draft.symptom.ifBlank { unknown })
        val display = activity.getString(R.string.tft_issue_report_display, activity.getString(displayChoices[draft.displayResult]))
        val timelineLabel = activity.getString(R.string.tft_issue_timeline)
        val startedAt = if (evidence.attemptStarted) {
            val time = evidence.startedAt?.format(DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss XXX", Locale.ROOT)) ?: unknown
            activity.getString(R.string.tft_issue_timeline_started, time)
        } else null
        val timeline = evidence.timeline.map { event ->
            val stage = activity.getString(when (event.stage) {
                ConnectionIssueTimelineStage.REQUESTED -> R.string.tft_issue_stage_requested
                ConnectionIssueTimelineStage.HEADUNIT_READY -> R.string.tft_issue_stage_headunit
                ConnectionIssueTimelineStage.WIFI_REQUESTED -> R.string.tft_issue_stage_wifi_requested
                ConnectionIssueTimelineStage.WIFI_AVAILABLE -> R.string.tft_issue_stage_wifi_available
                ConnectionIssueTimelineStage.PXC_STARTED -> R.string.tft_issue_stage_display
                ConnectionIssueTimelineStage.FIRST_FRAME -> R.string.tft_issue_stage_first_frame
            })
            activity.getString(R.string.tft_issue_stage_time, stage, event.elapsedMs)
        }.ifEmpty { listOf(activity.getString(R.string.tft_issue_timeline_empty)) }
        val details = activity.getString(R.string.tft_issue_report_details,
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
            activity.getString(phase.labelRes), headunit.name, step,
            evidence.qrModel ?: unknown, evidence.displayModel ?: unknown, evidence.displayFirmware ?: unknown,
        )
        val diagnosticsTitle = activity.getString(R.string.tft_issue_diagnostics_title)
        val diagnostics = evidence.structuredSummary()
        val report = listOf(
            activity.getString(R.string.tft_issue_report_title),
            "$modelLabel: $model\n$symptom\n$display",
            "$timelineLabel\n${(listOfNotNull(startedAt) + timeline).joinToString("\n")}",
            details,
            "$diagnosticsTitle\n$diagnostics",
        ).joinToString("\n\n")

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        fun rounded(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            border?.let { setStroke(dp(1), it) }
        }
        val white = Color.rgb(239, 245, 255)
        val muted = Color.rgb(174, 193, 216)
        val cyan = Color.rgb(103, 205, 255)
        fun text(value: String, size: Float, color: Int = white, bold: Boolean = false) = TextView(activity).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        fun LinearLayout.addSpaced(view: View, top: Int = 0, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) {
            addView(view, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(top)
            })
        }
        fun card() = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.rgb(17, 33, 55), 18, Color.rgb(53, 76, 105))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        content.addSpaced(text(activity.getString(R.string.app_name), 15f, muted, bold = true))
        content.addSpaced(text(activity.getString(R.string.tft_issue_preview), 24f, bold = true), 20)
        content.addSpaced(text(activity.getString(R.string.tft_issue_preview_note), 14f, muted), 6)

        val summary = card()
        summary.addSpaced(text(modelLabel, 13f, muted))
        summary.addSpaced(text(model, 21f, bold = true), 3)
        summary.addSpaced(text(display, 14f, white, bold = true).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(Color.rgb(31, 58, 83), 16)
        }, 10, ViewGroup.LayoutParams.WRAP_CONTENT)
        summary.addSpaced(text(symptom, 15f), 14)
        summary.addSpaced(View(activity).apply { setBackgroundColor(Color.rgb(53, 76, 105)) }, 14)
        summary.getChildAt(summary.childCount - 1).layoutParams.height = dp(1)
        summary.addSpaced(text(timelineLabel, 15f, bold = true), 14)
        if (startedAt != null) summary.addSpaced(text(startedAt, 13f, muted), 6)
        timeline.takeLast(3).forEach { event ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(text("•", 16f, cyan), LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(text(event, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            summary.addSpaced(row, 8)
        }
        if (timeline.size > 3) {
            summary.addSpaced(text(activity.getString(R.string.tft_issue_timeline_short, 3, timeline.size), 13f, muted), 8)
        }
        content.addSpaced(summary, 18)
        val detailCard = card()
        detailCard.addSpaced(text(activity.getString(R.string.tft_issue_details), 15f, bold = true))
        detailCard.addSpaced(text(details, 14f, muted).apply { setTextIsSelectable(true) }, 8)
        detailCard.addSpaced(text(timelineLabel, 15f, bold = true), 16)
        if (startedAt != null) detailCard.addSpaced(text(startedAt, 13f, muted), 6)
        timeline.forEach { detailCard.addSpaced(text(it, 14f, muted), 6) }
        val diagnosticsCard = card()
        diagnosticsCard.addSpaced(text(diagnosticsTitle, 15f, bold = true))
        diagnosticsCard.addSpaced(text(activity.getString(R.string.tft_issue_diagnostics_note), 13f, muted), 8)
        diagnosticsCard.addSpaced(text(diagnostics, 13f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }, 10)
        val fullDetails = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addSpaced(detailCard)
            addSpaced(diagnosticsCard, 12)
        }
        val detailsButton = Button(activity).apply {
            setText(R.string.tft_issue_details_show)
            isAllCaps = false
            setTextColor(cyan)
            minHeight = dp(48)
            background = rounded(Color.rgb(17, 33, 55), 14, Color.rgb(53, 76, 105))
            setOnClickListener {
                val expanding = fullDetails.visibility != View.VISIBLE
                fullDetails.visibility = if (expanding) View.VISIBLE else View.GONE
                setText(if (expanding) R.string.tft_issue_details_hide else R.string.tft_issue_details_show)
            }
        }
        content.addSpaced(detailsButton, 12)
        content.addSpaced(fullDetails, 8)

        val sendButton = Button(activity).apply {
            setText(R.string.tft_issue_send)
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            minHeight = dp(52)
            background = rounded(Color.rgb(22, 105, 183), 14)
        }
        val status = text("", 14f, muted).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val editButton = Button(activity).apply {
            setText(R.string.tft_issue_edit)
            isAllCaps = false
            setTextColor(cyan)
            minHeight = dp(48)
            background = ColorDrawable(Color.TRANSPARENT)
        }
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
            addSpaced(sendButton)
            addSpaced(status, 8)
            addSpaced(editButton, 4)
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(7, 16, 33), 22)
            addView(ScrollView(activity).apply { addView(content) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addSpaced(footer)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(panel)
            .create()
        var reportSent = false
        dialog.setCanceledOnTouchOutside(false)
        editButton.setOnClickListener {
            dialog.dismiss()
            if (!reportSent) showForm(draft)
        }
        sendButton.setOnClickListener {
            sendButton.isEnabled = false
            sendButton.alpha = 0.6f
            editButton.isEnabled = false
            dialog.setCancelable(false)
            status.visibility = View.VISIBLE
            status.setTextColor(muted)
            status.setText(R.string.tft_issue_sending)
            activity.lifecycleScope.launch {
                val sent = try {
                    sendReport(model, report)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!dialog.isShowing) return@launch
                dialog.setCancelable(true)
                editButton.isEnabled = true
                if (sent) {
                    reportSent = true
                    editButton.setText(R.string.close)
                    status.setTextColor(Color.rgb(89, 220, 170))
                    status.setText(R.string.tft_issue_send_success)
                } else {
                    sendButton.isEnabled = true
                    sendButton.alpha = 1f
                    status.setTextColor(Color.rgb(255, 176, 164))
                    status.setText(R.string.tft_issue_send_error)
                }
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((activity.resources.displayMetrics.widthPixels - dp(32)).coerceAtMost(dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }

    private fun input(@StringRes hintRes: Int, value: String, maxLength: Int, multiline: Boolean) = EditText(activity).apply {
        setHint(hintRes)
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
        if (multiline) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setMinLines(2)
            maxLines = 4
        } else setSingleLine()
        setText(value)
    }
}
