package dev.zanderp.opencfmoto

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

internal enum class ClockAlignment { LEFT, CENTER, RIGHT }

internal data class DashboardClockSlot(
    val anchorX: Float,
    val baselineY: Float,
    val textSize: Float,
    val align: ClockAlignment,
    val bounds: DashboardSlot,
)

/**
 * Pure layout & 24-hour clock formatting policy shared across all 14 live dashboard designs.
 * Keeps every clock placement strictly inside the visible TFT area (above y=320) with zero
 * overlap against gauges, speed digits, GPS badges, or OBD telemetry cards.
 */
internal object DashboardClock {
    private const val DEFAULT_TEXT_SIZE = 26f
    private const val COMPACT_TEXT_SIZE = 25f
    private const val RESERVED_WIDTH = 104f
    private const val RESERVED_HEIGHT = 28f
    internal const val MAX_SAFE_BOTTOM_Y = 316f

    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    @Volatile private var cachedMinute: Long = Long.MIN_VALUE
    @Volatile private var cachedOffsetMs: Int = Int.MIN_VALUE
    @Volatile private var cachedText: String = "00:00"

    fun format(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(formatter)

    fun formatNow(nowMillis: Long = System.currentTimeMillis()): String {
        val minute = Math.floorDiv(nowMillis, 60_000L)
        val offset = TimeZone.getDefault().getOffset(nowMillis)
        if (minute == cachedMinute && offset == cachedOffsetMs) return cachedText
        val formatted = format(nowMillis)
        cachedMinute = minute
        cachedOffsetMs = offset
        cachedText = formatted
        return formatted
    }

    fun resolve(clockText: String?, nowMillis: Long = System.currentTimeMillis()): String =
        clockText?.trim()?.takeIf { it.isNotEmpty() } ?: formatNow(nowMillis)

    fun slot(design: LiveDashboardDesign): DashboardClockSlot = when (design) {
        LiveDashboardDesign.SPORT -> slotAt(775f, 50f, DEFAULT_TEXT_SIZE, ClockAlignment.RIGHT)
        LiveDashboardDesign.RACING -> slotAt(30f, 123f, DEFAULT_TEXT_SIZE, ClockAlignment.LEFT)
        LiveDashboardDesign.TOURING -> slotAt(28f, 52f, DEFAULT_TEXT_SIZE, ClockAlignment.LEFT)
        LiveDashboardDesign.C01_NEO_SPORT,
        LiveDashboardDesign.C03_ENDURANCE -> slotAt(342f, 128f, DEFAULT_TEXT_SIZE, ClockAlignment.LEFT)
        LiveDashboardDesign.C02_CIRCUIT -> slotAt(400f, 88f, COMPACT_TEXT_SIZE, ClockAlignment.CENTER)
        LiveDashboardDesign.C04_TRACK_FOCUS -> slotAt(452f, 174f, DEFAULT_TEXT_SIZE, ClockAlignment.RIGHT)
        LiveDashboardDesign.C06_STREET_SPORT,
        LiveDashboardDesign.C07_APEX -> slotAt(776f, 58f, DEFAULT_TEXT_SIZE, ClockAlignment.RIGHT)
        LiveDashboardDesign.C08_CARBON_RACE -> slotAt(774f, 62f, DEFAULT_TEXT_SIZE, ClockAlignment.RIGHT)
        LiveDashboardDesign.C09_MINIMAL_BLACK -> slotAt(400f, 66f, DEFAULT_TEXT_SIZE, ClockAlignment.CENTER)
        LiveDashboardDesign.C11_AMBER_CLASSIC -> slotAt(776f, 66f, COMPACT_TEXT_SIZE, ClockAlignment.RIGHT)
        LiveDashboardDesign.C19_STEALTH -> slotAt(774f, 52f, DEFAULT_TEXT_SIZE, ClockAlignment.RIGHT)
        LiveDashboardDesign.HORIZON -> slotAt(35f, 86f, DEFAULT_TEXT_SIZE, ClockAlignment.LEFT)
    }

    /** Renders a minimal vector clock glyph + `HH:mm` readout inside [slot.bounds]. */
    fun draw(
        canvas: Canvas,
        paint: Paint,
        typeface: Typeface,
        slot: DashboardClockSlot,
        clockText: String,
        foregroundColor: Int,
        accentColor: Int,
    ) {
        val iconRadius = slot.textSize * 0.30f
        val iconDiameter = iconRadius * 2f
        val iconGap = 7f
        val maxTextWidth = (slot.bounds.width - iconDiameter - iconGap).coerceAtLeast(24f)

        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = typeface
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = slot.textSize
        val measured = paint.measureText(clockText)
        val fittedSize = if (measured > maxTextWidth && measured > 0f) {
            slot.textSize * maxTextWidth / measured
        } else {
            slot.textSize
        }
        paint.textSize = fittedSize
        val textWidth = paint.measureText(clockText)
        val totalWidth = iconDiameter + iconGap + textWidth

        val startX = when (slot.align) {
            ClockAlignment.LEFT -> slot.anchorX
            ClockAlignment.CENTER -> slot.anchorX - totalWidth / 2f
            ClockAlignment.RIGHT -> slot.anchorX - totalWidth
        }

        val iconCx = startX + iconRadius
        val iconCy = slot.baselineY - fittedSize * 0.34f
        paint.color = accentColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(iconCx, iconCy, iconRadius, paint)
        canvas.drawLine(iconCx, iconCy, iconCx, iconCy - iconRadius * 0.54f, paint)
        canvas.drawLine(iconCx, iconCy, iconCx + iconRadius * 0.44f, iconCy + iconRadius * 0.12f, paint)

        paint.style = Paint.Style.FILL
        paint.color = foregroundColor
        canvas.drawText(clockText, startX + iconDiameter + iconGap, slot.baselineY, paint)
    }

    private fun slotAt(
        anchorX: Float,
        baselineY: Float,
        textSize: Float,
        align: ClockAlignment,
    ): DashboardClockSlot {
        val left = when (align) {
            ClockAlignment.LEFT -> anchorX
            ClockAlignment.CENTER -> anchorX - RESERVED_WIDTH / 2f
            ClockAlignment.RIGHT -> anchorX - RESERVED_WIDTH
        }
        val top = baselineY - RESERVED_HEIGHT + 2f
        val bounds = DashboardSlot(left, top, RESERVED_WIDTH, RESERVED_HEIGHT)
        require(bounds.x >= 0f && bounds.x + bounds.width <= SelectedDashboardLayout.WIDTH)
        require(bounds.y >= 0f && bounds.y + bounds.height <= MAX_SAFE_BOTTOM_Y)
        return DashboardClockSlot(anchorX, baselineY, textSize, align, bounds)
    }
}
