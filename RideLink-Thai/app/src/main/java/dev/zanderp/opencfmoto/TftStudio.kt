package dev.zanderp.opencfmoto

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** พรีวิว 150X เดิมเก็บไว้เพื่อย้อนกลับ แต่ไม่ได้เปิดจากหน้าจอแอปรุ่นปัจจุบัน */
internal class TftStudio(
    private val activity: AppCompatActivity,
    private val selectPhoto: () -> Unit,
    private val onFactoryRestore: () -> Unit,
    private val onChooseBikeModel: () -> Unit,
    private val onToggleLiveDashboard: () -> Unit,
) {
    private companion object {
        val gallerySurface = 0xff12171c.toInt()
        val galleryBorder = 0xff3b464f.toInt()
        val gallerySelected = 0xffd8e4eb.toInt()
    }

    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundResource(R.drawable.card_liquid_glass)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private val prefs = activity.getSharedPreferences("tft_studio", Context.MODE_PRIVATE)
    private val presets = TftStyle.entries.filter(TftStyle::is150xPreset)
    private val labels = activity
    private var settings = prefs.readTftStudioSettings()
    private var photo: Bitmap? = null
    private var photoRevision = 0
    private lateinit var editPhotoCropButton: Button
    private var editorOpen = settings.custom
    private val preview = TftPreviewView(activity)
    private val framedPreview = meterFrame(preview)
    private var previewState = TftPreviewState()
    private val thumbnails = mutableMapOf<TftStyle, TftPreviewView>()
    private lateinit var photoStatus: TextView
    private var readoutPicker: Button? = null
    private val factoryPreviewStatus = label(R.string.tft_factory_preview_restored, 13f)
    private val factoryCopyStatus = label(R.string.tft_backup_preparing, 13f).apply {
        text = ""
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    init {
        rebuild()
        settings.photoUri?.let { loadPhoto(Uri.parse(it), remember = false) }
    }

    private fun rebuild() {
        view.removeAllViews()
        thumbnails.clear()
        readoutPicker = null
        view.addView(label(R.string.tft_studio_title, 20f))
        view.addView(label(R.string.tft_demo_only, 13f))
        row(R.string.ridescreen_choose_bike, onChooseBikeModel, R.string.ridescreen_toggle_live, onToggleLiveDashboard)
        view.addView(label(R.string.ridescreen_auto_model_hint, 13f))
        view.addView(label(R.string.ridescreen_live_explanation, 13f))
        view.addView(framedPreview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        row(
            R.string.tft_back_to_factory, {
                choosePreset(TftStyle.FACTORY)
                onFactoryRestore()
            },
            R.string.tft_use_custom, { applySettings(settings.useCustom()) },
        )
        view.addView(factoryPreviewStatus)
        view.addView(factoryCopyStatus)
        view.addView(label(R.string.tft_factory_vehicle_guide, 13f).apply {
            setPadding(0, dp(6), 0, 0)
        })
        if (settings.style != TftStyle.MIRROR) addReadoutControls()
        view.addView(LinearLayout(activity).apply {
            setBaselineAligned(false)
            addView(button(R.string.tft_day) { setLight(true) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button(R.string.tft_night) { setLight(false) }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(6)
            })
            addView(button(R.string.tft_fullscreen, ::showFullscreen), LinearLayout.LayoutParams(0, -2, 1.3f).apply {
                marginStart = dp(6)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addGallery()
        val canCustomize = settings.custom || settings.style.is150xPreset
        if (!canCustomize) view.addView(label(R.string.tft_choose_style_first, 13f))
        view.addView(button(R.string.tft_customize, ::toggleEditor).apply {
            isEnabled = canCustomize
            alpha = if (canCustomize) 1f else .55f
        })
        if (editorOpen) addEditor()
        render()
    }

    private fun addReadoutControls() {
        val metrics = TftReadout.entries
        val picker = button(settings.readout.label) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.tft_mod)
                .setSingleChoiceItems(metrics.map { labels.getString(it.label) }.toTypedArray(), settings.readout.ordinal) { dialog, index ->
                    setReadout(metrics[index])
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.tft_cancel, null)
                .show()
        }
        readoutPicker = picker
        val cycle = button(R.string.tft_cycle_readout) {
            setReadout(metrics[(settings.readout.ordinal + 1) % metrics.size])
        }
        view.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBaselineAligned(false)
            addView(picker, LinearLayout.LayoutParams(0, -2, 1f))
            addView(cycle, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun setReadout(value: TftReadout) {
        readoutPicker?.setText(value.label)
        applySettings(settings.copy(readout = value))
    }

    private fun addGallery() {
        view.addView(label(R.string.tft_select_style, 17f).apply {
            setPadding(0, dp(10), 0, dp(6))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        val columns = if (activity.resources.configuration.screenWidthDp < 400) 1 else 2
        presets.chunked(columns).forEach { rowPresets ->
            val cards = LinearLayout(activity).apply { setBaselineAligned(false) }
            rowPresets.forEach { preset ->
                val selected = settings.style == preset
                val thumbnail = TftPreviewView(activity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                thumbnails[preset] = thumbnail
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(4))
                    minimumHeight = dp(48)
                    val outline = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), galleryOutline(gallerySelected, 2))
                        addState(intArrayOf(android.R.attr.state_selected), galleryOutline(gallerySelected, 2))
                        addState(intArrayOf(), galleryOutline(galleryBorder, 1))
                    }
                    background = RippleDrawable(ColorStateList.valueOf(0x24ffffff), outline, null)
                    isSelected = selected
                    isFocusable = true
                    contentDescription = labels.getString(preset.label)
                    setOnClickListener { choosePreset(preset) }
                    addView(meterFrame(thumbnail), LinearLayout.LayoutParams(-1, -2))
                    addView(LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(52)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        addView(label(preset.label, 13f).apply {
                            setPadding(dp(4), dp(8), dp(4), dp(8))
                            setTextColor(Color.WHITE)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(AppCompatRadioButton(activity).apply {
                            isChecked = selected
                            isClickable = false
                            isFocusable = false
                            minimumWidth = 0
                            minimumHeight = 0
                            setPadding(0, 0, 0, 0)
                            buttonTintList = ColorStateList.valueOf(if (selected) gallerySelected else 0xff81909d.toInt())
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(dp(28), dp(32)))
                    })
                }
                cards.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (rowPresets.first() != preset) marginStart = dp(8)
                })
            }
            view.addView(cards, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun galleryOutline(color: Int, strokeDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(gallerySurface)
        setStroke(dp(strokeDp), color)
    }

    private fun meterFrame(panel: TftPreviewView) = FrameLayout(activity).apply {
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xff424a51.toInt(), 0xff1a1f23.toInt(), 0xff282e34.toInt()),
        ).apply {
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), 0xff4d565e.toInt())
        }
        addView(panel, FrameLayout.LayoutParams(-1, -2))
    }

    private fun showFullscreen() {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val fullscreen = TftPreviewView(activity).apply {
            render(previewState, preview.contentDescription.toString())
        }
        dialog.setContentView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(button(R.string.close) { dialog.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END }
            })
            addView(fullscreen, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        dialog.show()
    }

    private fun addEditor() {
        row(
            R.string.tft_accent, {
                chooseColor(R.string.tft_accent, settings.accent, settings.base.accent) {
                    applySettings(settings.useCustom().copy(accent = it))
                }
            },
            R.string.tft_digit_color, {
                chooseColor(R.string.tft_digit_color, settings.digits, Color.WHITE) {
                    applySettings(settings.useCustom().copy(digits = it))
                }
            }
        )
        row(R.string.tft_pick_photo, selectPhoto, R.string.tft_remove_photo, ::removePhoto)
        photoStatus = label(R.string.tft_photo_none, 12f)
        view.addView(photoStatus)
        editPhotoCropButton = button(R.string.tft_crop_title, ::editPhotoCrop)
        view.addView(editPhotoCropButton)
        spinner(R.string.tft_photo_fit, listOf(R.string.tft_fill, R.string.tft_contain), settings.fit) {
            applySettings(settings.useCustom().copy(fit = it))
        }
        val positions = listOf(R.string.tft_center, R.string.tft_top, R.string.tft_bottom, R.string.tft_left, R.string.tft_right)
        val savedCrop = settings.crop
        val positionChoices = if (savedCrop == null) positions else positions + R.string.tft_crop_title
        val selectedPosition = if (savedCrop == null) settings.position else positions.size
        spinner(R.string.tft_photo_position, positionChoices, selectedPosition) {
            applySettings(settings.useCustom().copy(position = it, crop = null), refreshEditor = savedCrop != null)
        }
        val strengthLabel = labels.getString(R.string.tft_photo_strength)
        val strengthValue = label(R.string.tft_photo_strength, 14f)
        strengthValue.text = labels.getString(R.string.tft_photo_strength_value, settings.strength)
        view.addView(strengthValue)
        view.addView(SeekBar(activity).apply {
            min = TftStudioSettings.MIN_PHOTO_STRENGTH
            max = TftStudioSettings.MAX_PHOTO_STRENGTH
            progress = settings.strength
            contentDescription = strengthLabel
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    strengthValue.text = labels.getString(R.string.tft_photo_strength_value, value)
                    applySettings(settings.useCustom().copy(strength = value))
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        })
        view.addView(button(R.string.tft_reset_custom, ::confirmResetCustom))
    }

    private fun previewFor(settings: TftStudioSettings, bitmap: Bitmap?) = with(settings) {
        TftPreviewState(
            style, day, if (custom && accent != Color.TRANSPARENT) accent else style.accent,
            digits.takeIf { custom && it != Color.TRANSPARENT }, readout, bitmap.takeIf { custom },
            strength, fit == 0, photoCrop,
        )
    }

    fun setFactoryCopyStatus(@StringRes text: Int) {
        factoryCopyStatus.setText(text)
        updateFactoryStatusVisibility()
    }

    private fun updateFactoryStatusVisibility() {
        val visible = settings.style == TftStyle.FACTORY && factoryCopyStatus.text.isNotEmpty()
        factoryPreviewStatus.visibility = if (visible) View.VISIBLE else View.GONE
        factoryCopyStatus.visibility = factoryPreviewStatus.visibility
    }

    private fun render(): Unit = with(settings) {
        updateFactoryStatusVisibility()
        previewState = previewFor(settings, photo)
        preview.render(
            previewState,
            labels.getString(
                R.string.tft_accessibility, labels.getString(style.label),
                previewState.readoutSample
            )
        )
        thumbnails.forEach { (preset, thumbnail) ->
            thumbnail.render(TftPreviewState(style = preset, day = day, readout = readout), "")
        }
        if (editorOpen) {
            photoStatus.setText(if (photo == null) R.string.tft_photo_none else R.string.tft_photo_loaded)
            editPhotoCropButton.visibility = if (photo == null) View.GONE else View.VISIBLE
        }
    }

    private fun setLight(value: Boolean) {
        applySettings(settings.copy(day = value))
    }

    private fun choosePreset(value: TftStyle) {
        val wasEditing = editorOpen
        editorOpen = false
        applySettings(settings.preview(value), refreshEditor = wasEditing)
    }

    private fun toggleEditor() {
        editorOpen = !editorOpen
        val next = if (editorOpen) settings.customizeSelected() else settings
        applySettings(next, refreshEditor = true)
    }

    fun importPhoto(uri: Uri) = loadPhoto(uri, remember = true)

    private fun loadPhoto(uri: Uri, remember: Boolean) {
        val revision = ++photoRevision
        activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = activity.contentResolver
                    val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
                    require(length <= 10_000_000)
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                        val longest = max(info.size.width, info.size.height)
                        if (longest > 1280) {
                            val scale = 1280f / longest
                            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                                (info.size.height * scale).roundToInt().coerceAtLeast(1))
                        }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.getOrNull()
            }
            if (revision != photoRevision) return@launch
            if (bitmap == null) {
                if (remember) Toast.makeText(labels, R.string.tft_photo_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (remember) {
                showPhotoCrop(uri, bitmap, revision)
            } else {
                photo = bitmap
                render()
            }
        }
    }

    private fun editPhotoCrop() {
        val uri = settings.photoUri ?: return
        val bitmap = photo ?: return
        showPhotoCrop(Uri.parse(uri), bitmap, ++photoRevision)
    }

    private fun showPhotoCrop(uri: Uri, bitmap: Bitmap, revision: Int) {
        val previousUri = settings.photoUri
        val uriString = uri.toString()
        val base = settings.style.takeIf { it.is150xPreset } ?: settings.base
        val draft = settings.useCustom(base).let {
            if (uriString == previousUri) it else it.copy(crop = null, position = 0)
        }
        showTftPhotoCrop(activity, previewFor(draft, bitmap)) { crop ->
            if (revision != photoRevision) return@showTftPhotoCrop
            val readGrant = activity.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (!readGrant &&
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isFailure
            ) {
                Toast.makeText(labels, R.string.tft_photo_error, Toast.LENGTH_SHORT).show()
                return@showTftPhotoCrop
            }
            photo = bitmap
            applySettings(draft.copy(photoUri = uriString, fit = 0, position = 0, crop = crop), refreshEditor = true)
            if (uriString != previousUri) releasePhotoGrant(previousUri)
        }
    }

    private fun releasePhotoGrant(uri: String?) {
        if (uri != null) {
            runCatching {
                activity.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun removePhoto() {
        clearPhoto(settings.copy(photoUri = null, crop = null))
    }

    private fun confirmResetCustom() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.tft_reset_custom)
            .setMessage(R.string.tft_reset_custom_message)
            .setNegativeButton(R.string.tft_cancel, null)
            .setPositiveButton(R.string.tft_reset_custom_confirm) { _, _ ->
                clearPhoto(settings.resetCustom(), refreshEditor = true)
            }
            .show()
    }

    private fun clearPhoto(next: TftStudioSettings, refreshEditor: Boolean = false) {
        photoRevision++
        val previousUri = settings.photoUri
        photo = null
        applySettings(next, refreshEditor)
        releasePhotoGrant(previousUri)
    }

    private fun applySettings(next: TftStudioSettings, refreshEditor: Boolean = false) {
        val refresh = refreshEditor || next.style != settings.style || next.custom != settings.custom
        if (next != settings) {
            settings = next
            prefs.writeTftStudioSettings(next)
        }
        if (refresh) rebuild() else render()
    }

    private fun chooseColor(title: Int, current: Int, fallback: Int, update: (Int) -> Unit) {
        val hsv = FloatArray(3)
        val initial = current.takeUnless { it == Color.TRANSPARENT } ?: fallback
        Color.colorToHSV(initial, hsv)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            isFocusableInTouchMode = true
        }
        val sample = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), galleryBorder)
            setColor(initial)
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
            setSelectAllOnFocus(true)
            setHint(R.string.tft_custom_hex)
            setText(String.format(Locale.ROOT, "#%06X", initial and 0xffffff))
        }
        panel.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(activity).apply { background = sample }, LinearLayout.LayoutParams(dp(48), dp(40)))
            addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        })

        fun track(vararg colors: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            setSize(-1, dp(6))
            cornerRadius = dp(3).toFloat()
            setStroke(dp(1), galleryBorder)
        }
        val sliders = listOf(R.string.tft_color_hue, R.string.tft_color_saturation, R.string.tft_color_brightness)
            .mapIndexed { index, caption ->
                val slider = SeekBar(activity).apply {
                    id = View.generateViewId()
                    contentDescription = labels.getString(caption)
                    max = if (index == 0) 359 else 100
                    minimumHeight = dp(48)
                }
                panel.addView(label(caption, 13f).apply { labelFor = slider.id })
                panel.addView(slider, LinearLayout.LayoutParams(-1, -2))
                slider
            }
        sliders[0].progressDrawable = track(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
        )
        fun refreshSliders(color: Int) {
            sliders[0].progress = hsv[0].roundToInt().coerceAtMost(359)
            sliders[1].progress = (hsv[1] * 100).roundToInt()
            sliders[2].progress = (hsv[2] * 100).roundToInt()
            sliders[1].progressDrawable = track(Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))
            sliders[2].progressDrawable = track(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            sample.setColor(color)
        }
        fun inputColor(): Int? {
            val hex = input.text.toString().trim().removePrefix("#")
            val rgb = hex.takeIf { it.length == 6 }?.toIntOrNull(16)?.takeIf { it in 0..0xffffff }
            return rgb?.or(0xff000000.toInt())
        }
        refreshSliders(initial)
        var fromSlider = false
        input.doAfterTextChanged {
            input.error = null
            if (!fromSlider) inputColor()?.let { color ->
                Color.colorToHSV(color, hsv)
                refreshSliders(color)
            }
        }
        sliders.forEachIndexed { index, slider ->
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    hsv[index] = if (index == 0) progress.toFloat() else progress / 100f
                    val color = Color.HSVToColor(hsv)
                    refreshSliders(color)
                    fromSlider = true
                    input.setText(String.format(Locale.ROOT, "#%06X", color and 0xffffff))
                    fromSlider = false
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }

        val dialog = AlertDialog.Builder(activity).setTitle(title)
            .setView(ScrollView(activity).apply { addView(panel) })
            .setNegativeButton(R.string.tft_cancel, null)
            .setNeutralButton(R.string.tft_color_default) { _, _ ->
                update(Color.TRANSPARENT)
            }
            .setPositiveButton(R.string.tft_apply, null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            panel.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val color = inputColor()
                if (color == null) {
                    input.error = labels.getString(R.string.tft_hex_error)
                    return@setOnClickListener
                }
                val isDigitColor = title == R.string.tft_digit_color
                val surface = when {
                    !isDigitColor -> 0xff242424.toInt()

                    else -> Color.BLACK
                }
                val minimumContrast = if (isDigitColor) 4.5 else 3.0
                if (ColorUtils.calculateContrast(color, surface) < minimumContrast) {
                    input.error = labels.getString(R.string.tft_color_contrast)
                    return@setOnClickListener
                }
                update(color)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

private fun label(resource: Int, size: Float) = TextView(labels).apply {
        setText(resource)
        textSize = size
        setTextColor(if (size >= 20) Color.WHITE else 0xffb3c4d5.toInt())
    }

    private fun button(resource: Int, action: () -> Unit) = AppCompatButton(labels).apply {
        val fill = when (resource) {
            R.string.tft_day, R.string.tft_cycle_readout -> R.drawable.btn_liquid_amber
            R.string.tft_night, R.string.tft_back_to_factory,
            R.string.tft_accent, R.string.tft_digit_color -> R.drawable.btn_liquid_sapphire
            R.string.tft_fullscreen, R.string.tft_customize,
            R.string.tft_pick_photo -> R.drawable.btn_liquid_teal_dark
            R.string.tft_use_custom -> R.drawable.btn_liquid_emerald
            R.string.tft_remove_photo, R.string.tft_reset_custom -> R.drawable.btn_liquid_ruby
            else -> R.drawable.btn_liquid_pill
        }
        val textColor = if (fill == R.drawable.btn_liquid_amber) R.color.amber_accent else R.color.text_primary
        setText(resource)
        setTextColor(ContextCompat.getColor(labels, textColor))
        textSize = 13f
        isAllCaps = false
        minHeight = dp(48)
        setMinWidth(0)
        minimumWidth = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setBackgroundResource(fill)
        backgroundTintList = null
        setOnClickListener { action() }
    }

    private fun row(left: Int, leftAction: () -> Unit, right: Int, rightAction: () -> Unit) {
        view.addView(LinearLayout(activity).apply {
            addView(button(left, leftAction), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button(right, rightAction), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        })
    }

    private fun spinner(title: Int, items: List<Int>, initial: Int, action: (Int) -> Unit) {
        view.addView(label(title, 13f).apply { setPadding(0, dp(6), 0, 0) })
        view.addView(Spinner(labels).apply {
            contentDescription = labels.getString(title)
            adapter = ArrayAdapter(labels, android.R.layout.simple_spinner_item, items.map(labels::getString))
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(initial, false)
            var selected = initial
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, child: View?, position: Int, id: Long) {
                    if (position == selected) return
                    selected = position
                    action(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
}
