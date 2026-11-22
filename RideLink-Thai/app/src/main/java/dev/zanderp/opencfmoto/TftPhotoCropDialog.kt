package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.min
import kotlin.math.roundToInt

// The preview supports dragging only. Position presets and zoom are separate accessible controls.
@SuppressLint("ClickableViewAccessibility")
internal fun showTftPhotoCrop(
    activity: AppCompatActivity,
    state: TftPreviewState,
    onApply: (TftPhotoCrop) -> Unit,
) {
    val photo = requireNotNull(state.photo)
    var crop = state.photoCrop
    val preview = TftPreviewView(activity)
    val theme = activity.getString(state.style.label)
    val description = activity.getString(R.string.tft_crop_preview, theme)
    val zoomLabel = TextView(activity)
    val spacing = (16 * activity.resources.displayMetrics.density).roundToInt()

    fun updatePreview() {
        preview.render(state.copy(fillPhoto = true, photoCrop = crop), description)
        zoomLabel.text = activity.getString(R.string.tft_crop_zoom_value, (crop.zoom * 100).roundToInt())
    }

    var lastX = 0f
    var lastY = 0f
    preview.setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.parent.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val scale = min(preview.width / TftPhotoCrop.WIDTH, preview.height / TftPhotoCrop.HEIGHT)
                if (event.pointerCount == 1 && scale > 0f) {
                    crop = crop.drag((event.x - lastX) / scale, (event.y - lastY) / scale, photo.width, photo.height)
                    updatePreview()
                }
                lastX = event.x
                lastY = event.y
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.parent.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> true
        }
    }

    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(spacing, spacing / 2, spacing, 0)
        addView(TextView(activity).apply {
            text = activity.getString(R.string.tft_crop_hint, theme)
            setPadding(0, 0, 0, spacing / 2)
        })
        addView(preview, LinearLayout.LayoutParams(-1, -2))
        addView(zoomLabel)
        addView(SeekBar(activity).apply {
            min = 100
            max = (TftPhotoCrop.MAX_ZOOM * 100).roundToInt()
            progress = (crop.zoom * 100).roundToInt()
            contentDescription = activity.getString(R.string.tft_crop_zoom)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    crop = crop.copy(zoom = value / 100f)
                    updatePreview()
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        })
    }
    updatePreview()
    AlertDialog.Builder(activity)
        .setTitle(R.string.tft_crop_title)
        .setView(ScrollView(activity).apply { addView(content) })
        .setNegativeButton(R.string.tft_cancel, null)
        .setPositiveButton(R.string.tft_crop_apply) { _, _ -> onApply(crop) }
        .show()
}
