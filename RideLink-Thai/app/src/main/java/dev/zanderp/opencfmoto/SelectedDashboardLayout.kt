package dev.zanderp.opencfmoto

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class DashboardCard(val id: String, val label: String, val value: String)
internal data class DashboardSlot(val x: Float, val y: Float, val width: Float, val height: Float)

/** คำนวณเฉพาะข้อมูลและตำแหน่ง ไม่สร้างค่ารถที่ยังไม่ได้รับจริง */
internal object SelectedDashboardLayout {
    const val WIDTH = 800f
    const val HEIGHT = 368f

    fun speed(data: LiveDashboardData): String = data.speedKmh
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.roundToInt()?.toString() ?: "—"

    fun fraction(value: Double?, maximum: Int): Float? {
        if (value == null || !value.isFinite() || value < 0 || maximum <= 0) return null
        return (value / maximum).toFloat().coerceIn(0f, 1f)
    }

    fun cards(data: LiveDashboardData, settings: LiveDashboardSettings): List<DashboardCard> {
        fun value(number: Double?, unit: String = "", decimals: Boolean = false): String {
            if (!data.obdLive || number == null || !number.isFinite()) return "—"
            val text = if (decimals) String.format(Locale.US, "%.1f", number) else number.roundToInt().toString()
            return if (unit.isEmpty()) text else "$text $unit"
        }
        val available = listOf(
            DashboardCard("rpm", "RPM", value(data.rpm)),
            DashboardCard("coolant", "TEMP", value(data.coolant, "°C")),
            DashboardCard("volt", "VOLT", value(data.volt, "V", decimals = true)),
            DashboardCard("load", "LOAD", value(data.load, "%")),
            DashboardCard("intake", "INTAKE", value(data.intake, "°C")),
            DashboardCard("baro", "MAP", value(data.manifoldPressureKpa, "kPa")),
            DashboardCard("throttle", "THROTTLE", value(data.relativeThrottle, "%")),
        )
        return available.filter { it.id in settings.visibleFields &&
            !(settings.design.hasSeparateRpmValue && it.id == "rpm") }
    }

    fun slots(design: LiveDashboardDesign, count: Int): List<DashboardSlot> {
        require(count in 0..7)
        if (count == 0) return emptyList()
        if (design.isConcept) return ConceptDashboardLayout.slots(design, count)
        val (x, y, width, height) = when (design) {
            LiveDashboardDesign.SPORT -> listOf(455f, 72f, 320f, 236f)
            LiveDashboardDesign.RACING -> listOf(530f, 140f, 245f, 180f)
            LiveDashboardDesign.TOURING -> listOf(25f, 257f, 750f, 84f)
            else -> error("รูปแบบหน้าปัดไม่ถูกต้อง")
        }
        val columns = when (design) {
            LiveDashboardDesign.TOURING -> if (count <= 3) count else 4
            else -> if (count <= 3) 1 else 2
        }
        val rows = ceil(count.toFloat() / columns).toInt()
        val gapX = 8f
        val gapY = if (design == LiveDashboardDesign.SPORT) 8f else 2f
        val cardWidth = (width - (columns - 1) * gapX) / columns
        val cardHeight = (height - (rows - 1) * gapY) / rows
        return List(count) { index ->
            DashboardSlot(x + (index % columns) * (cardWidth + gapX),
                y + (index / columns) * (cardHeight + gapY), cardWidth, cardHeight)
        }
    }
}
