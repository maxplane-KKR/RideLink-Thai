package dev.zanderp.opencfmoto

import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.min

internal data class DashboardTextPlacement(val x: Float, val baseline: Float, val size: Float)
internal data class DashboardCardTextPlan(
    val label: DashboardTextPlacement, val value: DashboardTextPlacement, val stacked: Boolean,
)

/** ใช้ขนาดที่ Sport แสดงจริงหลัง fit และจัดข้อความให้ไม่ชนกัน */
internal object DashboardCardTypography {
    private data class SportReference(
        val size: Float, val labelWidth: Float, val valueWidth: Float, val widestLabel: Float,
    )

    val typeface: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = DashboardCardTypography.typeface }
    private val sportReferences = run {
        val settings = LiveDashboardSettings(visibleFields = LiveDashboardSpec.fields.toSet())
        val labels = SelectedDashboardLayout.cards(LiveDashboardData(), settings).map { it.label }
        (1..7).map { count ->
            val slot = SelectedDashboardLayout.slots(LiveDashboardDesign.SPORT, count).first()
            val size = min(if (slot.width < 150f) 15f else 26f, slot.height * .42f)
            val available = slot.width - 22f - 12f
            val labelWidth = available * .55f
            paint.textSize = size
            SportReference(size, labelWidth, available * .40f,
                labels.maxOf { paint.measureText(it).coerceAtMost(labelWidth) })
        }
    }

    @Synchronized
    fun plan(slot: DashboardSlot, card: DashboardCard, design: LiveDashboardDesign, sportCount: Int): DashboardCardTextPlan {
        require(design != LiveDashboardDesign.SPORT && sportCount in 1..7)
        val sport = sportReferences[sportCount - 1]
        val labelSize = fittedSize(card.label, sport.size, sport.labelWidth)
        val valueSize = fittedSize(card.value, sport.size, sport.valueWidth)

        val touringIcon = design == LiveDashboardDesign.TOURING && slot.height >= 60f && slot.width >= 180f
        val plain = design == LiveDashboardDesign.C09_MINIMAL_BLACK || design == LiveDashboardDesign.C19_STEALTH
        val leftInset = when {
            touringIcon -> 80f
            design == LiveDashboardDesign.RACING -> 27f
            plain -> 8f
            design.isConcept -> 23f
            else -> 22f
        }
        val rightInset = if (design.isConcept || touringIcon) 10f else 14f
        val left = slot.x + leftInset
        val right = slot.x + slot.width - rightInset
        val stacked = touringIcon || slot.width < 150f || sport.widestLabel + sport.valueWidth + 8f > right - left
        if (!stacked) {
            val baseline = slot.y + slot.height * .5f + 8f
            paint.textSize = valueSize
            return DashboardCardTextPlan(
                DashboardTextPlacement(left, baseline, labelSize),
                DashboardTextPlacement(right - paint.measureText(card.value), baseline, valueSize), false,
            )
        }

        return DashboardCardTextPlan(
            DashboardTextPlacement(left, slot.y + slot.height * .5f - 4f, labelSize),
            DashboardTextPlacement(left, slot.y + slot.height * .5f + 19f, valueSize), true,
        )
    }

    private fun fittedSize(value: String, size: Float, maxWidth: Float): Float {
        paint.textSize = size
        val measured = paint.measureText(value)
        return if (measured > maxWidth && measured > 0f) size * maxWidth / measured else size
    }
}
