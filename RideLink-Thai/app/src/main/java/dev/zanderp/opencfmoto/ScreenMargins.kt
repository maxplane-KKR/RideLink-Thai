// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Adapted from ionutradu252/open-cflink.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Black borders and overall scale factor held back from the edges of the dash, in dash pixels / %.
 *
 * Per-edge (top/bottom/left/right) plus [scalePercent] (70..100%) so each bike's template overlay
 * (e.g. Zontes projection HUD bars or 800NK pull-down) can be cleared live in real-time without
 * reconnecting Android Auto.
 */
object ScreenMargins {
    private const val PREF = "screen_margins"
    const val MAX = 240
    const val MIN_SCALE = 70
    const val MAX_SCALE = 100

    @Volatile private var userTop = 0
    @Volatile private var userBottom = 0
    @Volatile private var userLeft = 0
    @Volatile private var userRight = 0
    @Volatile private var userScalePercent = 100

    @Volatile var customised: Boolean = false
        private set

    val top: Int get() = if (customised) userTop else BikeProfileHolder.active.defaultMargins[0]
    val bottom: Int get() = if (customised) userBottom else BikeProfileHolder.active.defaultMargins[1]
    val left: Int get() = if (customised) userLeft else BikeProfileHolder.active.defaultMargins[2]
    val right: Int get() = if (customised) userRight else BikeProfileHolder.active.defaultMargins[3]
    val scalePercent: Int get() = if (customised) userScalePercent else 100

    val any: Boolean
        get() = top != 0 || bottom != 0 || left != 0 || right != 0 || scalePercent != 100

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        customised = wasCustomised(
            hasFlag = p.contains("customised"),
            flag = p.getBoolean("customised", false),
            hasEdgeKeys = p.contains("top") || p.contains("bottom") ||
                p.contains("left") || p.contains("right") || p.contains("scale_pct"),
        )
        userTop = p.getInt("top", 0).coerceIn(0, MAX)
        userBottom = p.getInt("bottom", 0).coerceIn(0, MAX)
        userLeft = p.getInt("left", 0).coerceIn(0, MAX)
        userRight = p.getInt("right", 0).coerceIn(0, MAX)
        userScalePercent = p.getInt("scale_pct", 100).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    internal fun wasCustomised(hasFlag: Boolean, flag: Boolean, hasEdgeKeys: Boolean): Boolean =
        if (hasFlag) flag else hasEdgeKeys

    fun set(
        context: Context,
        t: Int = top,
        b: Int = bottom,
        l: Int = left,
        r: Int = right,
        scalePct: Int = scalePercent,
    ) {
        userTop = t.coerceIn(0, MAX)
        userBottom = b.coerceIn(0, MAX)
        userLeft = l.coerceIn(0, MAX)
        userRight = r.coerceIn(0, MAX)
        userScalePercent = scalePct.coerceIn(MIN_SCALE, MAX_SCALE)
        customised = true
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("customised", true)
            .putInt("top", userTop)
            .putInt("bottom", userBottom)
            .putInt("left", userLeft)
            .putInt("right", userRight)
            .putInt("scale_pct", userScalePercent)
            .apply()
        AaVideoBridge.pipeline?.refreshScreenMargins()
    }

    /** Back to the active profile's defaults (100% scale, profile margins). */
    fun reset(context: Context) {
        customised = false
        userTop = 0
        userBottom = 0
        userLeft = 0
        userRight = 0
        userScalePercent = 100
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("customised", false)
            .remove("top").remove("bottom").remove("left").remove("right").remove("scale_pct")
            .apply()
        AaVideoBridge.pipeline?.refreshScreenMargins()
    }

    fun summary(): String = when {
        !any -> "เต็มจอ 100% (ไม่มีระยะขอบ)"
        else -> "ขนาด ${scalePercent}% · บน $top · ล่าง $bottom · ซ้าย $left · ขวา $right px"
    }
}
