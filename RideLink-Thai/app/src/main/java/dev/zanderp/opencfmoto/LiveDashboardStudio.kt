package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** ตัวเลือกหน้าปัดสดที่ใช้วาดภาพส่งไปจอรถ */
internal class LiveDashboardStudio(
    private val activity: AppCompatActivity,
    private val onFactoryRestore: () -> Unit,
    private val onChooseBikeModel: () -> Unit,
    private val onToggleLiveDashboard: () -> Unit,
    private val onChooseObd: () -> Unit,
    private val onTestObd: () -> Unit,
) {
    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundResource(R.drawable.card_liquid_glass)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private val prefs = activity.getSharedPreferences("tft_studio", Context.MODE_PRIVATE)
    private var liveSettings = prefs.readLiveDashboardSettings()
    private val labels = activity
    private val liveStyleStatus = label(R.string.ridescreen_live_style_title, 15f)
    private val livePreview = ImageView(activity).apply {
        contentDescription = labels.getString(R.string.ridescreen_live_style_preview)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val factoryCopyStatus = label(R.string.tft_backup_preparing, 13f).apply { text = "" }
    private val liveStatus = label(R.string.ridescreen_live_status_off, 13f)
    private val obdStatus = label(R.string.ridescreen_obd_idle, 13f)
    private val obdInventory = label(R.string.ridescreen_obd_inventory_none, 13f)
    private val toggleLiveButton = button(R.string.ridescreen_live_open, onToggleLiveDashboard)

    init {
        view.addView(label(R.string.ridescreen_live_style_title, 20f))
        row(button(R.string.ridescreen_choose_bike, onChooseBikeModel), toggleLiveButton)
        view.addView(liveStatus)
        row(button(R.string.ridescreen_obd_choose, onChooseObd), button(R.string.ridescreen_obd_test, onTestObd))
        view.addView(obdStatus)
        view.addView(obdInventory)
        view.addView(label(R.string.ridescreen_auto_model_hint, 13f))
        view.addView(label(R.string.ridescreen_live_explanation, 13f))
        view.addView(label(R.string.ridescreen_live_unknown, 13f))
        view.addView(label(R.string.ridescreen_live_style_hint, 13f).apply { setPadding(0, dp(8), 0, 0) })
        view.addView(liveStyleStatus)
        view.addView(livePreview, LinearLayout.LayoutParams(-1, dp(165)))
        row(R.string.ridescreen_live_choose_style, ::chooseLiveDesign,
            R.string.ridescreen_live_choose_theme, ::chooseLiveTheme)
        row(R.string.ridescreen_live_choose_fields, ::chooseLiveFields,
            R.string.ridescreen_live_choose_scale, ::chooseLiveScale)
        view.addView(button(R.string.ridescreen_return_to_vehicle, onFactoryRestore).apply {
            setBackgroundResource(R.drawable.btn_liquid_ruby)
        })
        view.addView(label(R.string.ridescreen_return_to_vehicle_hint, 13f))
        view.addView(factoryCopyStatus)
        setLiveActive(RideScreenLiveDashboard.active)
        setObdStatus(LiveObd.status.value)
        setObdInventory(LiveObd.inventory.value)
        refreshLivePreview()
    }

    fun setFactoryCopyStatus(resource: Int) = factoryCopyStatus.setText(resource)

    fun setObdInventory(inventory: ObdInventory?) {
        obdInventory.visibility = if (inventory == null) View.GONE else View.VISIBLE
        if (inventory == null) return
        if (inventory.missingRange == 0) {
            obdInventory.setText(R.string.ridescreen_obd_inventory_none)
            return
        }
        val ids = inventory.pids.joinToString(" ") { it.obdHex() }.ifEmpty { "—" }
        val listed = labels.getString(R.string.ridescreen_obd_inventory, inventory.pids.size, ids)
        obdInventory.text = inventory.missingRange?.let {
            "$listed\n${labels.getString(R.string.ridescreen_obd_inventory_partial, it.obdHex())}"
        } ?: listed
    }

    fun setObdStatus(status: LiveObd.Status) {
        obdStatus.text = when (status.phase) {
            LiveObd.Phase.IDLE -> labels.getString(R.string.ridescreen_obd_idle)
            LiveObd.Phase.CONNECTING -> labels.getString(R.string.ridescreen_obd_connecting)
            LiveObd.Phase.WAITING_FOR_ECU -> labels.getString(R.string.ridescreen_obd_waiting)
            LiveObd.Phase.NO_DATA -> labels.getString(R.string.ridescreen_obd_no_data)
            LiveObd.Phase.UNAVAILABLE -> labels.getString(R.string.ridescreen_obd_unavailable)
            LiveObd.Phase.FAILED -> labels.getString(R.string.ridescreen_obd_failed)
            LiveObd.Phase.LIVE -> {
                val sample = status.sample
                val base = labels.getString(
                    R.string.ridescreen_obd_sample,
                    sample.rpm?.roundToInt()?.toString() ?: "—",
                    sample.coolant?.roundToInt()?.toString() ?: "—",
                    sample.volt?.let { String.format("%.2f", it) } ?: "—",
                )
                val extra = labels.getString(
                    R.string.ridescreen_obd_extra_sample,
                    sample.load?.roundToInt()?.toString() ?: "—",
                    sample.intake?.roundToInt()?.toString() ?: "—",
                    sample.manifoldPressureKpa?.roundToInt()?.toString() ?: "—",
                    sample.relativeThrottle?.roundToInt()?.toString() ?: "—",
                )
                "$base\n$extra"
            }
        }
    }

    fun setLiveActive(active: Boolean) {
        val state = LiveDashboardButtonState.forActive(active)
        toggleLiveButton.setText(state.action)
        toggleLiveButton.setBackgroundResource(state.background)
        liveStatus.setText(state.status)
        liveStatus.setTextColor(ContextCompat.getColor(labels, state.statusColor))
    }

    private fun refreshLivePreview() {
        val theme = if (liveSettings.light) R.string.ridescreen_live_light else R.string.ridescreen_live_dark
        liveStyleStatus.text = labels.getString(R.string.ridescreen_live_style_selected,
            liveSettings.design.label, labels.getString(theme))
        val image = SelectedDashboardRenderer.render(
            400, 184, LiveDashboardData(clockText = DashboardClock.formatNow()), liveSettings,
            labels.getString(R.string.ridescreen_live_no_vehicle_data), standalone = true,
        )
        livePreview.setImageBitmap(image)
    }

    private fun setLiveSettings(value: LiveDashboardSettings) {
        liveSettings = value
        prefs.writeLiveDashboardSettings(value)
        RideScreenLiveDashboard.updateSettings(value)
        refreshLivePreview()
    }

    private fun chooseLiveDesign() {
        val designs = LiveDashboardDesign.entries
        val settings = liveSettings
        val thumbnails = mutableListOf<ImageView>()
        val gallery = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(activity).apply { addView(gallery) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.ridescreen_live_choose_style)
            .setView(scroll)
            .setNegativeButton(R.string.tft_cancel, null)
            .create()
        val worker = Executors.newSingleThreadExecutor()

        designs.chunked(2).forEach { pair ->
            val row = LinearLayout(activity)
            pair.forEachIndexed { column, design ->
                val selected = design == settings.design
                val thumbnail = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                thumbnails.add(thumbnail)
                val title = TextView(activity).apply {
                    text = design.label
                    textSize = 13f
                    setTextColor(if (selected) 0xff70ebc2.toInt() else Color.WHITE)
                    setPadding(0, dp(5), 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(6), dp(6), dp(8))
                    background = GradientDrawable().apply {
                        setColor(0xff101d2a.toInt())
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(if (selected) 2 else 1), if (selected) 0xff35d99f.toInt() else 0xff34495b.toInt())
                    }
                    isSelected = selected
                    isFocusable = true
                    contentDescription = title.text
                    addView(thumbnail, LinearLayout.LayoutParams(-1, dp(80)))
                    addView(title)
                    setOnClickListener {
                        setLiveSettings(liveSettings.copy(design = design))
                        dialog.dismiss()
                    }
                }
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (column > 0) marginStart = dp(8)
                })
            }
            if (pair.size == 1) row.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(8) })
            gallery.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        dialog.setOnDismissListener {
            worker.shutdownNow()
            thumbnails.forEach { it.setImageDrawable(null) }
        }
        dialog.show()
        designs.forEachIndexed { index, design ->
            worker.execute {
                if (Thread.currentThread().isInterrupted) return@execute
                val image = SelectedDashboardRenderer.render(
                    320, 147, LiveDashboardData(clockText = DashboardClock.formatNow()), settings.copy(design = design),
                    labels.getString(R.string.ridescreen_live_no_vehicle_data), standalone = true,
                )
                activity.runOnUiThread {
                    if (dialog.isShowing) thumbnails[index].setImageBitmap(image) else image.recycle()
                }
            }
        }
    }

    private fun chooseLiveTheme() {
        val themes = arrayOf(labels.getString(R.string.ridescreen_live_light), labels.getString(R.string.ridescreen_live_dark))
        AlertDialog.Builder(activity).setTitle(R.string.ridescreen_live_choose_theme)
            .setSingleChoiceItems(themes, if (liveSettings.light) 0 else 1) { dialog, index ->
                setLiveSettings(liveSettings.copy(light = index == 0))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.tft_cancel, null).show()
    }

    private fun chooseLiveFields() {
        val names = listOf(
            R.string.ridescreen_live_metric_speed, R.string.ridescreen_live_metric_rpm,
            R.string.ridescreen_live_metric_coolant, R.string.ridescreen_live_metric_volt,
            R.string.ridescreen_live_metric_load, R.string.ridescreen_live_metric_intake,
            R.string.ridescreen_live_metric_baro, R.string.ridescreen_live_metric_throttle,
        ).map(labels::getString).toTypedArray()
        val fields = LiveDashboardSpec.fields
        val checked = BooleanArray(fields.size) { fields[it] in liveSettings.visibleFields }
        AlertDialog.Builder(activity).setTitle(R.string.ridescreen_live_choose_fields)
            .setMultiChoiceItems(names, checked) { _, index, selected -> checked[index] = selected }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setLiveSettings(liveSettings.copy(visibleFields = fields.filterIndexed { index, _ -> checked[index] }.toSet()))
            }
            .setNegativeButton(R.string.tft_cancel, null).show()
    }

    private fun chooseLiveScale() {
        if (!liveSettings.design.hasRpmScale) {
            chooseScale(isSpeed = true)
            return
        }
        val labelsForScale = arrayOf(labels.getString(R.string.ridescreen_live_scale_speed),
            labels.getString(R.string.ridescreen_live_scale_rpm))
        AlertDialog.Builder(activity).setTitle(R.string.ridescreen_live_choose_scale)
            .setItems(labelsForScale) { _, selected -> chooseScale(isSpeed = selected == 0) }
            .setNegativeButton(R.string.tft_cancel, null).show()
    }

    private fun chooseScale(isSpeed: Boolean) {
        val values = if (isSpeed) LiveDashboardSpec.speedOptions else LiveDashboardSpec.rpmOptions
        val current = if (isSpeed) liveSettings.speedMax else liveSettings.rpmMax
        val title = if (isSpeed) R.string.ridescreen_live_scale_speed else R.string.ridescreen_live_scale_rpm
        AlertDialog.Builder(activity).setTitle(title)
            .setSingleChoiceItems(values.map(Int::toString).toTypedArray(), values.indexOf(current)) { dialog, index ->
                val next = if (isSpeed) liveSettings.copy(speedMax = values[index]) else liveSettings.copy(rpmMax = values[index])
                setLiveSettings(next)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.tft_cancel, null).show()
    }

    private fun label(resource: Int, size: Float) = TextView(labels).apply {
        setText(resource)
        textSize = size
        setTextColor(if (size >= 20) Color.WHITE else 0xffb3c4d5.toInt())
    }

    private fun button(resource: Int, action: () -> Unit) = AppCompatButton(labels).apply {
        setText(resource)
        setTextColor(ContextCompat.getColor(labels, R.color.text_primary))
        textSize = 13f
        isAllCaps = false
        minHeight = dp(48)
        setMinWidth(0)
        minimumWidth = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setBackgroundResource(R.drawable.btn_liquid_pill)
        backgroundTintList = null
        setOnClickListener { action() }
    }

    private fun row(left: Int, leftAction: () -> Unit, right: Int, rightAction: () -> Unit) {
        row(button(left, leftAction), button(right, rightAction))
    }

    private fun row(left: AppCompatButton, right: AppCompatButton) {
        view.addView(LinearLayout(activity).apply {
            isBaselineAligned = false
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(6)
            })
        })
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
}
