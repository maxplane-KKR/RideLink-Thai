package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Manages fine touch calibration offsets (e.g. Y-axis compensation) for in-app preview
 * and bike touch events to eliminate mismatch between visual position and input coordinates.
 */
object TouchPrefs {
    private const val PREFS = "ridelink_touch_prefs"
    private const val KEY_Y_OFFSET = "touch_offset_y_px"

    /** Default -24px offset shifts touch points UP towards the actual search bar / top elements */
    const val DEFAULT_Y_OFFSET = -24
    const val MIN_OFFSET = -100
    const val MAX_OFFSET = 100

    @Volatile
    var yOffset: Int = DEFAULT_Y_OFFSET
        private set

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        yOffset = p.getInt(KEY_Y_OFFSET, DEFAULT_Y_OFFSET).coerceIn(MIN_OFFSET, MAX_OFFSET)
    }

    fun setYOffset(ctx: Context, offset: Int) {
        val clamped = offset.coerceIn(MIN_OFFSET, MAX_OFFSET)
        yOffset = clamped
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_Y_OFFSET, clamped)
            .apply()
    }
}
