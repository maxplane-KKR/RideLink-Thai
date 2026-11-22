package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min
import kotlin.math.roundToInt

/** วาดหน้าปัดที่เลือกจากภาพแนวคิด โดยใช้ข้อมูลสดและเก็บฉากคงที่ไว้เพื่อลดงานต่อเฟรม */
internal object ConceptDashboardRenderer {
    private const val W = SelectedDashboardLayout.WIDTH
    private const val H = SelectedDashboardLayout.HEIGHT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numbers = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
    private val labels = DashboardCardTypography.typeface
    private var liveBitmap: Bitmap? = null
    private var liveCanvas: Canvas? = null
    private var background: Bitmap? = null
    private var backgroundKey: String? = null

    private data class Palette(
        val background: Int, val foreground: Int, val muted: Int,
        val accent: Int, val trim: Int, val dim: Int, val card: Int, val edge: Int,
    )

    private fun palette(settings: LiveDashboardSettings): Palette {
        val design = settings.design
        val color = when (design) {
            LiveDashboardDesign.C01_NEO_SPORT -> 0xff00c9dc.toInt()
            LiveDashboardDesign.C02_CIRCUIT, LiveDashboardDesign.C04_TRACK_FOCUS,
            LiveDashboardDesign.C07_APEX, LiveDashboardDesign.C19_STEALTH -> 0xffff163b.toInt()
            LiveDashboardDesign.C03_ENDURANCE, LiveDashboardDesign.C11_AMBER_CLASSIC -> 0xffffaf15.toInt()
            LiveDashboardDesign.C06_STREET_SPORT -> 0xff00cce9.toInt()
            LiveDashboardDesign.C08_CARBON_RACE -> 0xffff7624.toInt()
            LiveDashboardDesign.C09_MINIMAL_BLACK -> 0xffdde3e8.toInt()
            LiveDashboardDesign.HORIZON -> 0xff00b9f2.toInt()
            else -> error("ไม่ใช่หน้าปัดชุดใหม่")
        }
        val darkAccent = when (design) {
            LiveDashboardDesign.C01_NEO_SPORT, LiveDashboardDesign.C06_STREET_SPORT -> 0xff006a7b.toInt()
            LiveDashboardDesign.C03_ENDURANCE, LiveDashboardDesign.C11_AMBER_CLASSIC -> 0xff8a5300.toInt()
            LiveDashboardDesign.C08_CARBON_RACE -> 0xffa43a00.toInt()
            LiveDashboardDesign.C09_MINIMAL_BLACK -> 0xff303a43.toInt()
            LiveDashboardDesign.HORIZON -> 0xff006aa8.toInt()
            else -> 0xffb10f30.toInt()
        }
        return if (settings.light) Palette(
            0xffedf2f5.toInt(), 0xff101820.toInt(), 0xff485965.toInt(), darkAccent,
            when (design) {
                LiveDashboardDesign.C01_NEO_SPORT -> 0xffa50d2e.toInt()
                LiveDashboardDesign.HORIZON -> 0xff006f82.toInt()
                else -> darkAccent
            },
            0xffcbd4da.toInt(), 0xfff9fbfc.toInt(), 0xff7f919d.toInt(),
        ) else Palette(
            if (design == LiveDashboardDesign.C06_STREET_SPORT) 0xff031229.toInt() else Color.BLACK,
            Color.WHITE, 0xffadbdc7.toInt(), color,
            when (design) {
                LiveDashboardDesign.C01_NEO_SPORT -> 0xffff2347.toInt()
                LiveDashboardDesign.HORIZON -> 0xff42dce8.toInt()
                else -> color
            },
            0xff263039.toInt(), 0xff071018.toInt(), 0xff52636f.toInt(),
        )
    }

    @Synchronized
    fun render(
        width: Int, height: Int, data: LiveDashboardData,
        settings: LiveDashboardSettings, standalone: Boolean,
    ): Bitmap {
        require(width > 0 && height > 0 && settings.design.isConcept)
        val target: Bitmap
        val canvas: Canvas
        if (standalone) {
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            canvas = Canvas(target)
        } else {
            var bitmap = liveBitmap
            if (bitmap == null || bitmap.width != width || bitmap.height != height) {
                // ตัวประมวลผลภาพอาจยังใช้บิตแมปเก่าอยู่
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                liveBitmap = bitmap
                liveCanvas = Canvas(bitmap)
            }
            target = bitmap
            canvas = checkNotNull(liveCanvas)
        }
        val p = palette(settings)
        val cards = SelectedDashboardLayout.cards(data, settings)
        val slots = ConceptDashboardLayout.slots(settings.design, cards.size)
        val scale = min(width / W, height / H)
        val dx = (width - W * scale) / 2f
        val dy = (height - H * scale) / 2f
        val key = "${settings.design.id}|${settings.light}|${width}x$height|${settings.rpmMax}|${settings.visibleFields.sorted()}"
        val staticImage = if (standalone || background == null || backgroundKey != key) {
            if (!standalone) background?.recycle()
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val layer = Canvas(image)
            layer.drawColor(p.background)
            layer.save()
            layer.translate(dx, dy)
            layer.scale(scale, scale)
            drawBackground(layer, settings, p)
            slots.forEach { drawCardBackground(layer, it, settings.design, p) }
            layer.restore()
            if (!standalone) {
                background = image
                backgroundKey = key
            }
            image
        } else checkNotNull(background)
        canvas.drawBitmap(staticImage, 0f, 0f, null)
        if (standalone) staticImage.recycle()
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        drawRpm(canvas, data, settings, p)
        drawSpeed(canvas, data, settings, p)
        val sportCount = LiveDashboardSpec.fields.count { it != "speed" && it in settings.visibleFields }
        cards.zip(slots).forEach { (card, slot) -> drawCardText(canvas, slot, card, settings.design, sportCount, p) }
        canvas.restore()
        return target
    }

    private fun drawBackground(c: Canvas, s: LiveDashboardSettings, p: Palette) {
        when (s.design) {
            LiveDashboardDesign.C01_NEO_SPORT, LiveDashboardDesign.C03_ENDURANCE -> {
                line(c, 18f, 79f, 65f, 20f, p.accent, 4f)
                line(c, 65f, 20f, 780f, 20f, p.accent, 4f)
                if ("rpm" in s.visibleFields) drawGaugeBase(c, s, p)
            }
            LiveDashboardDesign.C02_CIRCUIT -> {
                line(c, 48f, 29f, 752f, 29f, p.accent, 3f)
                if ("rpm" in s.visibleFields) drawGaugeBase(c, s, p)
            }
            LiveDashboardDesign.C04_TRACK_FOCUS -> {
                val curve = Path().apply {
                    moveTo(24f, 161f)
                    cubicTo(90f, 35f, 330f, 20f, 756f, 22f)
                }
                reset(p.accent)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                c.drawPath(curve, paint)
                if ("rpm" in s.visibleFields) drawGaugeBase(c, s, p)
            }
            LiveDashboardDesign.C06_STREET_SPORT -> {
                line(c, 30f, 105f, 130f, 8f, p.accent, 2.5f)
                line(c, 48f, 122f, 165f, 8f, p.dim, 2f)
                line(c, 507f, 82f, 778f, 82f, p.accent, 2f)
            }
            LiveDashboardDesign.C07_APEX -> {
                polygon(c, p.foreground, 0f, 78f, 68f, 14f, 235f, 14f, 217f, 22f, 78f, 22f, 0f, 98f)
                polygon(c, p.accent, 0f, 101f, 83f, 26f, 346f, 26f, 326f, 34f, 90f, 34f, 0f, 119f)
            }
            LiveDashboardDesign.C08_CARBON_RACE -> {
                for (i in -12..38) {
                    val x = i * 32f
                    line(c, x, 0f, x + 210f, 300f, p.dim, 1f)
                    line(c, x + 9f, 0f, x + 219f, 300f, p.dim, .7f)
                }
                line(c, 25f, 21f, 775f, 21f, p.accent, 3f)
            }
            LiveDashboardDesign.C09_MINIMAL_BLACK -> Unit
            LiveDashboardDesign.C11_AMBER_CLASSIC -> {
                line(c, 18f, 32f, 782f, 32f, p.accent, 3f)
                for (i in 0..16) {
                    val x = 235f + i * 19f
                    line(c, x, 9f, x + 8f, 17f, p.dim, 1f)
                    line(c, x + 8f, 17f, x + 16f, 9f, p.dim, 1f)
                }
            }
            LiveDashboardDesign.C19_STEALTH -> {
                polygon(c, p.dim, 0f, 35f, 370f, 35f, 310f, 54f, 0f, 54f)
                line(c, 520f, 65f, 775f, 65f, p.edge, 1f)
            }
            LiveDashboardDesign.HORIZON -> {
                polygon(c, p.dim, 0f, 22f, 800f, 22f, 800f, 45f, 0f, 45f)
                line(c, 24f, 22f, 776f, 22f, p.accent, 3f)
                line(c, 24f, 45f, 455f, 45f, p.trim, 2f)
                line(c, 516f, 70f, 778f, 70f, p.trim, 2f)
                for (i in 0..7) {
                    val x = 31f + i * 22f
                    line(c, x, 31f, x + 12f, 31f, p.accent, 2f)
                }
            }
            else -> error("ไม่ใช่หน้าปัดชุดใหม่")
        }
    }

    private fun drawGaugeBase(c: Canvas, settings: LiveDashboardSettings, p: Palette) {
        if (settings.design == LiveDashboardDesign.C02_CIRCUIT) {
            for (i in 0 until 10) {
                reset(p.dim)
                c.drawRoundRect(RectF(180f + i * 45f, 41f, 215f + i * 45f, 53f), 4f, 4f, paint)
            }
            return
        }
        val top = if (settings.design == LiveDashboardDesign.C04_TRACK_FOCUS) 36f else 31f
        for (i in 0 until 10) {
            val x = 63f + i * 70f
            polygon(c, p.dim, x + 13f, top, x + 65f, top, x + 52f, top + 23f, x, top + 23f)
        }
        for (i in 0..5) {
            val x = 63f + i * 140f
            text(c, ((settings.rpmMax / 1000) * i / 5).toString(), x, top + 43f,
                15f, p.muted, Paint.Align.CENTER)
        }
    }

    private fun drawRpm(c: Canvas, data: LiveDashboardData, s: LiveDashboardSettings, p: Palette) {
        if (!s.design.hasRpmScale || "rpm" !in s.visibleFields) return
        val rpm = data.rpm?.takeIf { data.obdLive && it.isFinite() && it >= 0.0 }
        val active = SelectedDashboardLayout.fraction(rpm, s.rpmMax)?.let { (it * 10).toInt() } ?: 0
        for (i in 0 until active.coerceIn(0, 10)) {
            if (s.design == LiveDashboardDesign.C02_CIRCUIT) {
                reset(p.accent)
                c.drawRoundRect(RectF(180f + i * 45f, 41f, 215f + i * 45f, 53f), 4f, 4f, paint)
            } else {
                val x = 63f + i * 70f
                val top = if (s.design == LiveDashboardDesign.C04_TRACK_FOCUS) 36f else 31f
                polygon(c, p.accent, x + 13f, top, x + 65f, top, x + 52f, top + 23f, x, top + 23f)
            }
        }
        if (s.design.hasSeparateRpmValue) {
            val value = rpm?.roundToInt()?.toString() ?: "—"
            if (s.design == LiveDashboardDesign.C04_TRACK_FOCUS) {
                text(c, "RPM  $value", 28f, 174f, 26f, p.foreground, maxWidth = 420f)
                line(c, 28f, 181f, 452f, 181f, p.accent, 2f)
            } else {
                text(c, "RPM  $value", 772f, 128f, 26f, p.foreground, Paint.Align.RIGHT, 248f)
                line(c, 518f, 135f, 777f, 135f, p.accent, 2f)
            }
        }
    }

    private fun drawSpeed(c: Canvas, data: LiveDashboardData, s: LiveDashboardSettings, p: Palette) {
        if ("speed" !in s.visibleFields) return
        val value = SelectedDashboardLayout.speed(data)
        val fresh = data.speedFromGps && value != "—"
        val split = s.design == LiveDashboardDesign.C02_CIRCUIT || s.design == LiveDashboardDesign.C09_MINIMAL_BLACK
        val right = s.design == LiveDashboardDesign.C04_TRACK_FOCUS
        when {
            split -> {
                text(c, value, 400f, 242f, 185f, p.foreground, Paint.Align.CENTER, 230f, numbers)
                text(c, "km/h", 346f, 281f, 30f, p.foreground)
                badge(c, 452f, 256f, fresh, p)
                progress(c, 280f, 308f, 240f, data.speedKmh, s.speedMax, p)
            }
            right -> {
                text(c, value, 620f, 261f, 190f, p.foreground, Paint.Align.CENTER, 235f, numbers)
                text(c, "km/h", 515f, 299f, 30f, p.foreground)
                badge(c, 678f, 273f, fresh, p)
                progress(c, 514f, 308f, 241f, data.speedKmh, s.speedMax, p)
            }
            else -> {
                text(c, value, 35f, 262f, 202f, p.foreground, maxWidth = 270f, face = numbers)
                text(c, "km/h", 342f, 258f, 30f, p.foreground)
                badge(c, 348f, 195f, fresh, p)
                progress(c, 37f, 308f, 405f, data.speedKmh, s.speedMax, p)
            }
        }
    }

    private fun drawCardBackground(c: Canvas, slot: DashboardSlot, design: LiveDashboardDesign, p: Palette) {
        if (design == LiveDashboardDesign.C09_MINIMAL_BLACK || design == LiveDashboardDesign.C19_STEALTH) {
            line(c, slot.x + 4f, slot.y + slot.height - 3f, slot.x + slot.width - 4f,
                slot.y + slot.height - 3f, p.edge, 1f)
            return
        }
        reset(p.card)
        c.drawRoundRect(RectF(slot.x, slot.y, slot.x + slot.width, slot.y + slot.height), 7f, 7f, paint)
        reset(p.edge)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        c.drawRoundRect(RectF(slot.x, slot.y, slot.x + slot.width, slot.y + slot.height), 7f, 7f, paint)
        line(c, slot.x + 8f, slot.y + slot.height - 7f, slot.x + 17f, slot.y + 7f, p.trim, 3f)
    }

    private fun drawCardText(c: Canvas, slot: DashboardSlot, card: DashboardCard, design: LiveDashboardDesign,
        sportCount: Int, p: Palette) {
        val plan = DashboardCardTypography.plan(slot, card, design, sportCount)
        text(c, card.label, plan.label.x, plan.label.baseline, plan.label.size, p.muted)
        text(c, card.value, plan.value.x, plan.value.baseline, plan.value.size, p.foreground)
    }

    private fun badge(c: Canvas, x: Float, y: Float, fresh: Boolean, p: Palette) {
        val color = if (fresh) p.accent else p.muted
        reset(color)
        c.drawRoundRect(RectF(x, y, x + 62f, y + 25f), 4f, 4f, paint)
        text(c, "GPS", x + 31f, y + 19f, 17f, p.background, Paint.Align.CENTER)
    }

    private fun progress(c: Canvas, x: Float, y: Float, width: Float, value: Double?, max: Int, p: Palette) {
        line(c, x, y, x + width, y, p.dim, 2f)
        SelectedDashboardLayout.fraction(value, max)?.let { line(c, x, y, x + width * it, y, p.accent, 2f) }
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, maxWidth: Float = Float.MAX_VALUE, face: Typeface = labels) {
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
