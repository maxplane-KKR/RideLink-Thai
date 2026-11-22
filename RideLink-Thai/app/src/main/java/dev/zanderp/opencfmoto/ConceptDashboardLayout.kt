package dev.zanderp.opencfmoto

import kotlin.math.ceil

/** พื้นที่ข้อมูลของหน้าปัดใหม่ เว้นด้านล่างให้แถบเรือนไมล์เดิม */
internal object ConceptDashboardLayout {
    fun slots(design: LiveDashboardDesign, count: Int): List<DashboardSlot> {
        require(design.isConcept && count in 0..7)
        if (count == 0) return emptyList()
        if (design == LiveDashboardDesign.C02_CIRCUIT || design == LiveDashboardDesign.C09_MINIMAL_BLACK) {
            val rows = ceil(count / 2f).toInt()
            val gap = 8f
            val height = (204f - (rows - 1) * gap) / rows
            return List(count) { i ->
                DashboardSlot(if (i % 2 == 0) 18f else 557f,
                    94f + (i / 2) * (height + gap), 225f, height)
            }
        }
        val (x, y, width, height, columns) = when (design) {
            LiveDashboardDesign.C01_NEO_SPORT, LiveDashboardDesign.C03_ENDURANCE ->
                listOf(518f, 140f, 260f, 158f, if (count <= 3) 1f else 2f)
            LiveDashboardDesign.C04_TRACK_FOCUS ->
                listOf(26f, 190f, 430f, 108f, if (count == 1) 1f else 2f)
            else -> listOf(516f, 82f, 262f, 216f, if (count <= 3) 1f else 2f)
        }
        val cols = columns.toInt()
        val rows = ceil(count.toFloat() / cols).toInt()
        val gapX = 8f
        val gapY = 2f
        val cardWidth = (width - (cols - 1) * gapX) / cols
        val cardHeight = (height - (rows - 1) * gapY) / rows
        return List(count) { i ->
            DashboardSlot(x + (i % cols) * (cardWidth + gapX),
                y + (i / cols) * (cardHeight + gapY), cardWidth, cardHeight)
        }
    }
}
