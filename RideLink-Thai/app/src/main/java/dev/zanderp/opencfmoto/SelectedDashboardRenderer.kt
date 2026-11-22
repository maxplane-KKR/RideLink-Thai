package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.min
import kotlin.math.roundToInt

/** วาดภาพสดจากข้อมูลที่มี ไม่ฝังภาพตัวเลขตัวอย่าง */
internal object SelectedDashboardRenderer {
    private const val W = SelectedDashboardLayout.WIDTH
    private const val H = SelectedDashboardLayout.HEIGHT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberFont = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
    private val boldFont = DashboardCardTypography.typeface
    private val labelFont = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private var liveBitmap: Bitmap? = null
    private var liveCanvas: Canvas? = null
    private var staticLayer: Bitmap? = null
    private var staticKey: String? = null

    private data class Palette(
        val bg: Int, val foreground: Int, val muted: Int, val card: Int,
        val edge: Int, val accent: Int, val dim: Int,
    )

    private fun palette(settings: LiveDashboardSettings): Palette {
        val accent = when (settings.design) {
            LiveDashboardDesign.SPORT -> if (settings.light) 0xff0056c8.toInt() else 0xff008bff.toInt()
            LiveDashboardDesign.RACING -> if (settings.light) 0xffc90c27.toInt() else 0xffff1337.toInt()
            LiveDashboardDesign.TOURING -> if (settings.light) 0xff007b82.toInt() else 0xff00d5dc.toInt()
            else -> error("รูปแบบนี้ใช้ตัววาดหน้าปัดชุดใหม่")
        }
        return if (settings.light) Palette(
            0xffeef3f7.toInt(), 0xff111a24.toInt(), 0xff455462.toInt(), Color.WHITE,
            0xff9baebb.toInt(), accent, 0xffd1dce4.toInt(),
        ) else Palette(
            Color.BLACK, Color.WHITE, 0xffa8b6c1.toInt(), 0xff020b13.toInt(),
            0xff314658.toInt(), accent, 0xff202a32.toInt(),
        )
    }

    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun render(
        width: Int, height: Int, data: LiveDashboardData, settings: LiveDashboardSettings,
        noDataLabel: String, standalone: Boolean = false,
    ): Bitmap {
        require(width > 0 && height > 0)
        if (settings.design.isConcept) {
            return ConceptDashboardRenderer.render(width, height, data, settings, standalone)
        }
        val target: Bitmap
        val canvas: Canvas
        if (standalone) {
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            canvas = Canvas(target)
        } else {
            var current = liveBitmap
            if (current == null || current.width != width || current.height != height) {
                // GL อาจกำลังอัปโหลดบิตแมปเก่า จึงไม่ recycle บิตแมปภาพสดที่คืนไปแล้ว
                current = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                liveBitmap = current
                liveCanvas = Canvas(current)
            }
            target = current
            canvas = checkNotNull(liveCanvas)
        }

        val p = palette(settings)
        val cards = SelectedDashboardLayout.cards(data, settings)
        val slots = SelectedDashboardLayout.slots(settings.design, cards.size)
        val scale = min(width / W, height / H)
        val dx = (width - W * scale) / 2f
        val dy = (height - H * scale) / 2f
        val key = "${settings.design.id}|${settings.light}|${width}x$height|${settings.rpmMax}|${settings.visibleFields.sorted()}"
        val background = if (standalone || staticLayer == null || staticKey != key) {
            if (!standalone) staticLayer?.recycle()
            val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val back = Canvas(layer)
            back.drawColor(p.bg)
            back.save()
            back.translate(dx, dy)
            back.scale(scale, scale)
            when (settings.design) {
                LiveDashboardDesign.SPORT -> drawSportBackground(back, p)
                LiveDashboardDesign.RACING -> drawRacingBackground(back, p, settings)
                LiveDashboardDesign.TOURING -> drawTouringBackground(back, p)
                else -> error("รูปแบบนี้ใช้ตัววาดหน้าปัดชุดใหม่")
            }
            slots.forEach { drawCardBackground(back, it, settings.design, p) }
            back.restore()
            if (!standalone) {
                staticLayer = layer
                staticKey = key
            }
            layer
        } else checkNotNull(staticLayer)

        canvas.drawBitmap(background, 0f, 0f, null)
        if (standalone) background.recycle()
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        drawSpeed(canvas, data, settings, p)
        if (settings.design == LiveDashboardDesign.RACING && "rpm" in settings.visibleFields) {
            drawRpm(canvas, data, settings, p)
        }
        val sportCount = LiveDashboardSpec.fields.count { it != "speed" && it in settings.visibleFields }
        cards.zip(slots).forEach { (card, slot) -> drawCardText(canvas, slot, card, settings.design, sportCount, p) }
        canvas.restore()
        return target
    }

    private fun drawSportBackground(c: Canvas, p: Palette) {
        polygon(c, p.accent, 0f, 0f, 192f, 0f, 20f, 70f, 0f, 70f)
        polygon(c, p.accent, 0f, 340f, 275f, 368f, 0f, 368f)
        line(c, 506f, 0f, 366f, H, p.accent, 5f)
        line(c, 510f, 0f, 370f, H, 0xff55d7ff.toInt(), 1.5f)
    }

    private fun drawRacingBackground(c: Canvas, p: Palette, settings: LiveDashboardSettings) {
        if ("rpm" !in settings.visibleFields) return
        polygon(c, p.accent, 16f, 82f, 64f, 20f, 780f, 20f, 755f, 25f, 68f, 25f, 22f, 82f)
        val count = 10
        val startX = 62f
        val segmentWidth = 70f
        for (i in 0 until count) {
            val x = startX + i * segmentWidth
            polygon(c, p.dim, x + 15f, 30f, x + segmentWidth - 2f, 30f, x + segmentWidth - 18f, 57f, x, 57f)
        }
        for (i in 0..5) {
            val x = 62f + i * 140f
            line(c, x, 61f, x, 68f, p.muted, 1.5f)
            text(c, ((settings.rpmMax / 1000) * i / 5).toString(), x, 84f, 17f, p.muted, boldFont, Paint.Align.CENTER)
        }
        line(c, 520f, 132f, 766f, 132f, p.accent, 2f)
    }

    private fun drawTouringBackground(c: Canvas, p: Palette) {
        val start = if (p.bg == Color.BLACK) 0xff006bff.toInt() else 0xff0055c8.toInt()
        val finish = if (p.bg == Color.BLACK) 0xff00ef7c.toInt() else 0xff008f57.toInt()
        for (i in 0..8) {
            val path = Path().apply {
                moveTo(-8f, 252f + i * 4f)
                cubicTo(70f, 142f + i * 4f, 154f, 216f + i * 5f, 226f, 160f + i * 3f)
                cubicTo(302f, 105f + i * 3f, 306f, 12f + i * 4f, 478f, 14f + i * 4f)
                cubicTo(515f, 14f + i * 4f, 550f, 23f + i * 4f, 577f, 30f + i * 4f)
            }
            reset(p.accent)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (i == 0) 3f else 1f
            paint.alpha = if (i == 0) 255 else (150 - i * 13).coerceAtLeast(35)
            paint.shader = LinearGradient(0f, 0f, 490f, 0f, start, finish, Shader.TileMode.CLAMP)
            c.drawPath(path, paint)
            paint.shader = null
        }
    }

    private fun drawCardBackground(c: Canvas, s: DashboardSlot, design: LiveDashboardDesign, p: Palette) {
        if (design == LiveDashboardDesign.SPORT) {
            val slant = min(15f, s.width / 8f)
            val path = Path().apply {
                moveTo(s.x + slant, s.y)
                lineTo(s.x + s.width, s.y)
                lineTo(s.x + s.width - slant, s.y + s.height)
                lineTo(s.x, s.y + s.height)
                close()
            }
            reset(p.card)
            c.drawPath(path, paint)
            reset(p.accent)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            c.drawPath(path, paint)
            polygon(c, p.accent, s.x + slant, s.y + 4f, s.x + slant + 8f, s.y + 4f,
                s.x + 8f, s.y + s.height - 4f, s.x, s.y + s.height - 4f)
        } else {
            val rect = RectF(s.x, s.y, s.x + s.width, s.y + s.height)
            reset(p.card)
            c.drawRoundRect(rect, 10f, 10f, paint)
            reset(if (design == LiveDashboardDesign.TOURING) p.accent else p.edge)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f
            c.drawRoundRect(rect, 10f, 10f, paint)
            if (design == LiveDashboardDesign.RACING) {
                line(c, s.x + 10f, s.y + s.height - 6f, s.x + 25f, s.y + 6f, p.accent, 4f)
            }
        }
    }

    private fun drawSpeed(c: Canvas, data: LiveDashboardData, settings: LiveDashboardSettings, p: Palette) {
        if ("speed" !in settings.visibleFields) return
        val speed = SelectedDashboardLayout.speed(data)
        val freshGps = data.speedFromGps && speed != "—"
        when (settings.design) {
            LiveDashboardDesign.SPORT -> {
                text(c, speed, 42f, 258f, 238f, p.foreground, numberFont, maxWidth = 344f)
                text(c, "km/h", 100f, 315f, 38f, p.foreground, boldFont)
                badge(c, 258f, 281f, freshGps, p, outlined = false)
                progress(c, 50f, 332f, 300f, data.speedKmh, settings.speedMax, p)
            }
            LiveDashboardDesign.RACING -> {
                text(c, speed, 30f, 300f, 215f, p.foreground, numberFont, maxWidth = 355f)
                text(c, "km/h", 390f, 288f, 37f, p.foreground, boldFont)
                badge(c, 408f, 205f, freshGps, p, outlined = false)
                progress(c, 36f, 316f, 355f, data.speedKmh, settings.speedMax, p)
            }
            LiveDashboardDesign.TOURING -> {
                text(c, speed, 413f, 226f, 205f, p.foreground, numberFont, maxWidth = 245f)
                text(c, "km/h", 675f, 188f, 35f, p.foreground, labelFont)
                badge(c, 681f, 76f, freshGps, p, outlined = true)
                progress(c, 423f, 240f, 235f, data.speedKmh, settings.speedMax, p)
            }
            else -> error("รูปแบบนี้ใช้ตัววาดหน้าปัดชุดใหม่")
        }
    }

    private fun drawRpm(c: Canvas, data: LiveDashboardData, settings: LiveDashboardSettings, p: Palette) {
        val rpm = data.rpm?.takeIf { data.obdLive && it.isFinite() && it >= 0.0 }
        val fraction = SelectedDashboardLayout.fraction(rpm, settings.rpmMax)
        if (fraction != null) {
            val lit = (fraction * 10f).toInt().coerceIn(0, 10)
            for (i in 0 until lit) {
                val x = 62f + i * 70f
                polygon(c, p.accent, x + 15f, 30f, x + 68f, 30f, x + 52f, 57f, x, 57f)
            }
        }
        val value = rpm?.roundToInt()?.toString() ?: "—"
        text(c, "RPM  $value", 760f, 123f, 29f, p.foreground, boldFont, Paint.Align.RIGHT, 230f)
    }

    private fun drawCardText(c: Canvas, s: DashboardSlot, card: DashboardCard, design: LiveDashboardDesign,
        sportCount: Int, p: Palette) {
        if (design == LiveDashboardDesign.SPORT) {
            val fontSize = min(if (s.width < 150f) 15f else 26f, s.height * .42f)
            val left = s.x + 22f
            val available = (s.width - (left - s.x) - 12f).coerceAtLeast(12f)
            text(c, card.label, left, s.y + s.height * .63f, fontSize, p.foreground, boldFont,
                maxWidth = available * .55f)
            text(c, card.value, s.x + s.width - 14f, s.y + s.height * .63f, fontSize,
                p.foreground, boldFont, Paint.Align.RIGHT, available * .40f)
            return
        }
        val touringIcon = design == LiveDashboardDesign.TOURING && s.height >= 60f && s.width >= 180f
        if (touringIcon) {
            line(c, s.x + 64f, s.y + 12f, s.x + 64f, s.y + s.height - 12f, p.edge, 1f)
            drawMetricIcon(c, card.id, s.x + 32f, s.y + s.height / 2f, p.muted)
        }
        val plan = DashboardCardTypography.plan(s, card, design, sportCount)
        text(c, card.label, plan.label.x, plan.label.baseline, plan.label.size,
            if (touringIcon) p.muted else p.foreground, boldFont)
        text(c, card.value, plan.value.x, plan.value.baseline, plan.value.size, p.foreground, boldFont)
    }

    private fun drawMetricIcon(c: Canvas, id: String, x: Float, y: Float, color: Int) {
        reset(color)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        when (id) {
            "volt" -> {
                c.drawRoundRect(RectF(x - 16f, y - 11f, x + 16f, y + 11f), 3f, 3f, paint)
                c.drawLine(x - 10f, y, x - 4f, y, paint)
                c.drawLine(x + 5f, y, x + 11f, y, paint)
                c.drawLine(x + 8f, y - 3f, x + 8f, y + 3f, paint)
            }
            "coolant", "intake" -> {
                c.drawRoundRect(RectF(x - 4f, y - 16f, x + 4f, y + 7f), 4f, 4f, paint)
                c.drawCircle(x, y + 11f, 6f, paint)
            }
            else -> {
                c.drawArc(RectF(x - 16f, y - 16f, x + 16f, y + 16f), 180f, 180f, false, paint)
                c.drawCircle(x, y + 4f, 3f, paint)
            }
        }
    }

    private fun badge(c: Canvas, x: Float, y: Float, fresh: Boolean, p: Palette, outlined: Boolean) {
        val color = if (fresh) p.accent else p.muted
        val rect = RectF(x, y, x + 78f, y + 30f)
        reset(color)
        if (outlined) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            c.drawRoundRect(rect, 15f, 15f, paint)
        } else {
            val path = Path().apply {
                moveTo(x + 10f, y); lineTo(x + 78f, y); lineTo(x + 68f, y + 30f)
                lineTo(x, y + 30f); close()
            }
            c.drawPath(path, paint)
        }
        val ink = if (outlined) color else p.bg
        text(c, "GPS", x + 39f, y + 23f, 21f, ink, boldFont, Paint.Align.CENTER)
    }

    private fun progress(c: Canvas, x: Float, y: Float, width: Float, value: Double?, maximum: Int, p: Palette) {
        line(c, x, y, x + width, y, p.dim, 2f)
        SelectedDashboardLayout.fraction(value, maximum)?.let { line(c, x, y, x + width * it, y, p.accent, 2f) }
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
        face: Typeface, align: Paint.Align = Paint.Align.LEFT, maxWidth: Float = Float.MAX_VALUE) {
        reset(color)
        paint.typeface = face
        paint.textAlign = align
        paint.textSize = size
        val measured = paint.measureText(value)
        if (measured > maxWidth && measured > 0f) paint.textSize = size * maxWidth / measured
        c.drawText(value, x, y, paint)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        reset(color)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(x1, y1, x2, y2, paint)
    }

    private fun polygon(c: Canvas, color: Int, vararg points: Float) {
        val path = Path().apply {
            moveTo(points[0], points[1])
            var i = 2
            while (i < points.size) { lineTo(points[i], points[i + 1]); i += 2 }
            close()
        }
        reset(color)
        c.drawPath(path, paint)
    }

    private fun reset(color: Int) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        paint.style = Paint.Style.FILL
    }
}
