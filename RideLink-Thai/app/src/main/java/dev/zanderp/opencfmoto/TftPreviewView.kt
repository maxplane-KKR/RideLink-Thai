package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class TftStyle(val label: Int, val accent: Int, val is150xPreset: Boolean = false) {
    FACTORY(R.string.tft_factory, 0xff55c9fb.toInt()),
    APEX(R.string.tft_apex, 0xffffa856.toInt()),
    VECTOR(R.string.tft_vector, 0xff69d4b4.toInt()),
    GRID(R.string.tft_grid, 0xff75c5f3.toInt()),
    CYBERPUNK(R.string.tft_cyberpunk, 0xff48dff2.toInt()),
    MIRROR(R.string.tft_mirror, 0xff55e0c8.toInt()),
    BLUE_ARC(R.string.tft_blue_arc, 0xff64c4ff.toInt(), true),
    AMBER_SWEEP(R.string.tft_amber_sweep, 0xffffb34c.toInt(), true),
    SPLIT_BARS(R.string.tft_split_bars, 0xff43efed.toInt(), true),
    TWIN_DIALS(R.string.tft_twin_dials, 0xffffba6a.toInt(), true)
}

internal enum class TftReadout(val label: Int, val sample: String) {
    ODO(R.string.tft_odo, "ODO 2,480 km"),
    TRIP(R.string.tft_trip, "TRIP 18.4 km"),
    RANGE(R.string.tft_range, "RANGE 223 km"),
    VOLTAGE(R.string.tft_voltage, "BAT 12.8 V"),
    CONSUMPTION(R.string.tft_consumption, "LPK AVG 2.3 L/100 km"),
    AVG_SPEED(R.string.tft_avg_speed, "AVG 42 km/h"),
    TPMS(R.string.tft_tpms, "F 2.4 bar 30°C   R 2.4 bar 30°C")
}

internal data class TftPreviewState(
    val style: TftStyle = TftStyle.FACTORY,
    val day: Boolean = false,
    val accent: Int = style.accent,
    val digits: Int? = null,
    val readout: TftReadout = TftReadout.ODO,
    val photo: Bitmap? = null,
    val photoOpacity: Int = 32,
    val fillPhoto: Boolean = true,
    val photoCrop: TftPhotoCrop = TftPhotoCrop(),
) {
    val readoutSample get() = if (style == TftStyle.FACTORY && readout == TftReadout.ODO) {
        "ODO  999999 km"
    } else {
        readout.sample
    }
}

internal class TftPreviewView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val sweepPath = Path()
    private val sweepMeasure = PathMeasure()
    private val sweepPoint = FloatArray(2)
    private val amberTrack = Path().apply {
        moveTo(151f, 214f)
        cubicTo(235f, 104f, 404f, 93f, 611f, 90f)
        lineTo(605f, 102f)
        cubicTo(413f, 105f, 244f, 124f, 151f, 214f)
        close()
    }
    private var amberShaderAccent: Int? = null
    private var amberShader: LinearGradient? = null
    private val bounds = RectF()
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val speedFace = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val presetFace = Typeface.create("sans-serif", Typeface.BOLD)
    private val sportFace = Typeface.create(speedFace, 900, false)
    // Outline of the fixed 64 km/h demo reading in the square display style of references 02 and 03.
    private val squareSpeedSample = PathParser.createPathFromPathData(
        "M31,0 H75 Q101,0 101,27 H86 Q86,15 74,15 H31 Q16,15 16,31 V45 " +
            "Q22,39 34,39 H74 Q103,39 103,69 Q103,100 74,100 H32 Q0,100 0,69 " +
            "V31 Q0,0 31,0 Z M32,54 Q16,54 16,70 Q16,85 32,85 H73 Q87,85 87,70 " +
            "Q87,54 73,54 Z M172,0 H192 V64 H206 V80 H192 V100 H175 V80 H111 V66 Z " +
            "M175,18 L130,64 H175 Z",
    ).apply { fillType = Path.FillType.EVEN_ODD }
    private val stockIndicators = checkNotNull(ContextCompat.getDrawable(context, R.drawable.tft_stock_indicators)).mutate()
    // Measured against the Thai 150X owner's manual, page 6-2, on the 640 × 384 preview canvas.
    private val factoryRpmX = 452f
    private val factoryRpmY = 262f
    private val factoryRpmRadius = 234f
    private val factoryRpmStart = 151f
    private val factoryRpmStep = 17f
    private val factoryChrome = 0xffe4e7ea.toInt()
    private val factoryRed = 0xfffb4d46.toInt()
    private val factoryRpmTrack = factoryRpmGradient(0xff1b2b37.toInt(), 0xff576b7a.toInt(), 0xff272d32.toInt())
    private val factoryRpmBlue = factoryRpmGradient(0xff174476.toInt(), 0xff0b2747.toInt(), 0xff46b0ef.toInt())
    private val factoryRpmRed = factoryRpmGradient(0xff321716.toInt(), 0xff672923.toInt(), 0xff9a473d.toInt())
    private var state = TftPreviewState()
    private val foreground get() = if (state.day) Color.WHITE else 0xffe0ebf6.toInt()
    private val secondary get() = if (state.day) 0xffe0edf7.toInt() else 0xffa6bacb.toInt()
    private val rail get() = if (state.day) 0xff496072.toInt() else 0xff2f4457.toInt()
    private val accent get() = state.accent.takeIf { ColorUtils.calculateContrast(it, 0xff242424.toInt()) >= 3 }
        ?: state.style.accent

    fun render(value: TftPreviewState, accessibilitySummary: String) {
        state = value
        contentDescription = accessibilitySummary
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(width * 3 / 5, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        val scale = min(width / TftPhotoCrop.WIDTH, height / TftPhotoCrop.HEIGHT)
        canvas.translate((width - TftPhotoCrop.WIDTH * scale) / 2f, (height - TftPhotoCrop.HEIGHT * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, TftPhotoCrop.WIDTH, TftPhotoCrop.HEIGHT)
        drawPhoto(canvas)
        when (state.style) {
            TftStyle.FACTORY -> factory(canvas)
            TftStyle.APEX -> apex(canvas)
            TftStyle.VECTOR -> vector(canvas)
            TftStyle.GRID -> grid(canvas)
            TftStyle.CYBERPUNK -> cyberpunk(canvas)
            TftStyle.MIRROR -> mirror(canvas)
            TftStyle.BLUE_ARC -> blueArc(canvas)
            TftStyle.AMBER_SWEEP -> amberSweep(canvas)
            TftStyle.SPLIT_BARS -> splitBars(canvas)
            TftStyle.TWIN_DIALS -> twinDials(canvas)
        }
        if (state.style != TftStyle.FACTORY) drawStockHeader(canvas)
        if (state.style.is150xPreset) {
            presetReadouts(canvas)
        } else if (state.style != TftStyle.MIRROR && state.style != TftStyle.FACTORY) {
            rect(canvas, 14f, 345f, 626f, 384f, Color.BLACK)
            rect(canvas, 24f, 346f, 616f, 348f, rail)
            text(canvas, state.readout.sample, 320f, 373f, 22f)
        }
        canvas.restore()
    }

    private fun drawStockHeader(canvas: Canvas) {
        val preset = state.style.is150xPreset
        rect(canvas, 0f, 0f, 640f, if (preset) 39f else 65f, Color.BLACK)
        stockIndicators.setBounds(8, 4, 632, 36)
        stockIndicators.draw(canvas)
        text(canvas, "08:00", 70f, if (preset) 65f else 59f, if (preset) 28f else 23f, foreground, if (preset) presetFace else mono)
        bluetoothIcon(canvas, if (preset) 133f else 601f, if (preset) 55f else 50f)
    }

    private fun bluetoothIcon(canvas: Canvas, x: Float, y: Float, scale: Float = 1f) {
        canvas.save()
        canvas.scale(scale, scale, x, y)
        path.reset()
        path.addRoundRect(x - 11f, y - 12f, x + 11f, y + 12f, 3f, 3f, Path.Direction.CW)
        path.moveTo(x - 6f, y - 6f)
        path.lineTo(x + 6f, y + 4f)
        path.lineTo(x, y + 8f)
        path.lineTo(x, y - 8f)
        path.lineTo(x + 6f, y - 4f)
        path.lineTo(x - 6f, y + 6f)
        paint.color = 0xff8dd477.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()
    }

    private fun drawPhoto(canvas: Canvas) {
        val bitmap = state.photo ?: return
        val frame = state.photoCrop.frame(bitmap.width, bitmap.height, state.fillPhoto)
        bounds.set(frame.left, frame.top, frame.left + frame.width, frame.top + frame.height)
        paint.color = Color.WHITE
        paint.alpha = state.photoOpacity * 255 / 100
        canvas.drawBitmap(bitmap, null, bounds, paint)
        paint.alpha = 255
    }

    private fun factory(canvas: Canvas) {
        stockIndicators.setBounds(0, 0, 640, 28)
        stockIndicators.draw(canvas)
        factoryText(canvas, "08:00", 94f, 94f, 43f)
        factoryFuel(canvas)
        factoryTachometer(canvas)

        // The manufacturer's colour reference shows a sample speed of 120 km/h.
        factoryText(canvas, "120", 456f, 311f, 158f, maxWidth = 184f)
        factoryText(canvas, "km/h", 590f, 311f, 22f)
        bluetoothIcon(canvas, 589f, 247f, scale = .84f)

        path.reset()
        path.moveTo(257f, 384f)
        path.lineTo(306f, 358f)
        path.quadTo(330f, 345f, 353f, 345f)
        path.lineTo(640f, 345f)
        strokePath(canvas, path, 1.7f, factoryChrome)
        factoryText(canvas, state.readoutSample, 454f, 374f, 22f, maxWidth = 300f)
    }

    private fun factoryFuel(canvas: Canvas) {
        factoryText(canvas, "F", 46f, 139f, 20f)
        factoryText(canvas, "E", 46f, 288f, 20f)
        fuelIcon(canvas, 46f, 205f)
        repeat(6) { index ->
            val y = 125f + index * 29f
            path.reset()
            path.addRoundRect(74f, y, 151f, y + 16f, 2.5f, 2.5f, Path.Direction.CW)
            if (index >= 2) {
                paint.color = 0xffb5b9bf.toInt()
                canvas.drawPath(path, paint)
            }
            strokePath(canvas, path, 2f, factoryChrome)
        }
    }

    private fun factoryTachometer(canvas: Canvas) {
        val x = factoryRpmX
        val y = factoryRpmY
        val radius = factoryRpmRadius
        val start = factoryRpmStart
        val step = factoryRpmStep
        val sweep = 10 * step
        factoryRpmBand(canvas, start, sweep, factoryRpmTrack)
        factoryRpmBand(canvas, start, 5 * step, factoryRpmBlue)
        factoryRpmBand(canvas, start + 8 * step, 2 * step, factoryRpmRed)
        arc(canvas, x - radius, y - radius, x + radius, y + radius, start, sweep, 2.4f, factoryChrome)

        val inner = 160f
        arc(canvas, x - inner, y - inner, x + inner, y + inner, start + 6, sweep - 10, 2.4f, 0xff8f2926.toInt())
        arc(canvas, x - inner + 3, y - inner + 3, x + inner - 3, y + inner - 3, start + 7, sweep - 12, 1.3f, factoryRed)
        for (rpm in 0..10) {
            val angle = Math.toRadians((start + rpm * step).toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val labelRadius = if (rpm == 0) 210f else 202f
            dialTick(canvas, x, y, radius, angle, 10f, 2.2f, factoryChrome)
            factoryText(
                canvas, rpm.toString(), x + dx * labelRadius, y + dy * labelRadius + 7f,
                if (rpm == 5) 27f else 21f, if (rpm >= 8) factoryRed else Color.WHITE,
            )
        }

        val needle = Math.toRadians((start + 5 * step).toDouble())
        val nx = cos(needle).toFloat()
        val ny = sin(needle).toFloat()
        line(canvas, x + nx * 145f, y + ny * 145f, x + nx * 232f, y + ny * 232f, 7f, 0xffad211e.toInt())
        line(canvas, x + nx * 148f, y + ny * 148f, x + nx * 231f, y + ny * 231f, 2.6f, factoryRed)
        factoryText(canvas, "5", x + nx * 202f, y + ny * 202f + 7f, 27f)
        factoryText(canvas, "rpm", 611f, 171f, 18f)
        factoryText(canvas, "x1000", 611f, 188f, 15f)
    }

    private fun factoryRpmGradient(inner: Int, middle: Int, outer: Int) = RadialGradient(
        factoryRpmX, factoryRpmY, factoryRpmRadius,
        intArrayOf(Color.BLACK, inner, middle, outer), floatArrayOf(.65f, .72f, .86f, 1f),
        Shader.TileMode.CLAMP,
    )

    private fun factoryRpmBand(canvas: Canvas, start: Float, sweep: Float, shader: Shader) {
        val x = factoryRpmX
        val y = factoryRpmY
        val radius = 197f
        paint.shader = shader
        arc(canvas, x - radius, y - radius, x + radius, y + radius, start, sweep, 74f, Color.WHITE)
        paint.shader = null
    }

    private fun factoryText(
        canvas: Canvas, value: String, x: Float, y: Float, size: Float,
        color: Int = Color.WHITE, maxWidth: Float = Float.POSITIVE_INFINITY,
    ) {
        text(canvas, value, x, y, size, color, speedFace, maxWidth = maxWidth, minSize = 0f)
    }


    private fun blueArc(canvas: Canvas) {
        val x = 425f
        val y = 228f
        val radius = 177f
        val start = 150f
        val sweep = 178f
        val rpmFraction = .35f
        val track = radius - 21f
        arc(canvas, x - track, y - track, x + track, y + track, start, sweep, 42f, 0xff202e3c.toInt())
        arc(canvas, x - track, y - track, x + track, y + track, start, sweep * rpmFraction, 42f, ColorUtils.blendARGB(accent, Color.BLACK, .53f))
        arc(canvas, x - track, y - track, x + track, y + track, start + sweep * .8f, sweep * .2f, 42f, ColorUtils.blendARGB(redline, Color.BLACK, .71f))
        val lip = radius - 9f
        arc(canvas, x - lip, y - lip, x + lip, y + lip, start, sweep, 16f, 0x5004090d)
        arc(canvas, x - lip, y - lip, x + lip, y + lip, start, sweep * rpmFraction, 5f, ColorUtils.blendARGB(accent, Color.BLACK, .27f))
        val rim = radius
        arc(canvas, x - rim, y - rim, x + rim, y + rim, start, sweep, 3f, foreground)
        val inner = radius - 40f
        arc(canvas, x - inner, y - inner, x + inner, y + inner, start, sweep * rpmFraction, 3f, accent)
        for (tick in 1..19 step 2) {
            val angle = Math.toRadians((start + sweep * tick / 20f).toDouble())
            dialTick(canvas, x, y, rim, angle, 9f, 1.2f, secondary)
        }
        dialTicks(canvas, x, y, rim, start, sweep, redFrom = 9)
        val needle = Math.toRadians((start + sweep * rpmFraction).toDouble())
        dialTick(canvas, x, y, rim - 5f, needle, 61f, 5f, redline)
        dialTick(canvas, x, y, rim - 8f, needle, 53f, 1.6f, Color.WHITE)
        text(canvas, "×1000", 595f, 183f, 22f, foreground, presetFace)
        text(canvas, "rpm", 595f, 206f, 22f, foreground, presetFace)
        sportSpeed(canvas, x, 287f, 150f, unitX = 552f)
        text(canvas, "F", 91f, 153f, 22f)
        path.reset()
        path.addRoundRect(103f, 143f, 145f, 291f, 6f, 6f, Path.Direction.CW)
        strokePath(canvas, path, 1.5f, foreground)
        repeat(6) { index ->
            val top = 148f + index * 23f
            paint.color = if (index >= 2) accent else rail
            canvas.drawRoundRect(108f, top, 140f, top + 20f, 2f, 2f, paint)
        }
        text(canvas, "E", 91f, 291f, 22f)
        fuelIcon(canvas, 66f, 207f)
    }

    private fun amberSweep(canvas: Canvas) {
        sweepPath.reset()
        sweepPath.moveTo(151f, 214f)
        sweepPath.cubicTo(235f, 111f, 404f, 99f, 609f, 96f)
        sweepMeasure.setPath(sweepPath, false)
        val length = sweepMeasure.length
        if (amberShaderAccent != accent) {
            amberShaderAccent = accent
            amberShader = LinearGradient(
                151f, 0f, 611f, 0f,
                intArrayOf(accent, ColorUtils.blendARGB(accent, 0xffe77d28.toInt(), .34f), redline, redline),
                floatArrayOf(0f, .79f, .81f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        paint.shader = amberShader
        canvas.drawPath(amberTrack, paint)
        paint.shader = null
        for (rpm in 0..10 step 2) {
            sweepMeasure.getPosTan(length * rpm / 10f, sweepPoint, null)
            val (x, y) = sweepPoint
            line(canvas, x, y - 8f, x, y + 7f, 2f, foreground)
            text(canvas, rpm.toString(), x, y - 18f, 22f, if (rpm == 10) redline else foreground, presetFace)
        }
        text(canvas, "×1000 rpm", 562f, 128f, 22f, foreground, presetFace)
        squareSpeed(canvas, 431f, 269f, 107f, 1f, 579f)
    }

    private fun splitBars(canvas: Canvas) {
        line(canvas, 298f, 71f, 246f, 326f, 2f, accent)
        squareSpeed(canvas, 111f, 285f, 128f, .71f, 224f)
        for (rpm in 0..10 step 2) {
            text(canvas, rpm.toString(), 310f + rpm * 30f, 108f, 22f, if (rpm >= 8) redline else foreground, presetFace)
        }
        line(canvas, 314f, 114f, 552f, 114f, 1.5f, accent)
        line(canvas, 552f, 114f, 610f, 114f, 1.5f, redline)
        repeat(10) { index ->
            val x = 299f + index * 30f
            path.reset()
            path.moveTo(x + 12f, 120f)
            path.lineTo(x + 39f, 120f)
            path.lineTo(x + 27f, 145f)
            path.lineTo(x, 145f)
            path.close()
            paint.color = if (index < 6) accent else rail
            canvas.drawPath(path, paint)
            line(canvas, x + 13f, 121f, x + 37f, 121f, 1f, if (index < 6) 0x44ffffff else 0x38ffffff)
        }
        text(canvas, "×1000 rpm", 560f, 169f, 22f, foreground, presetFace)
        path.reset()
        path.moveTo(389f, 200f)
        path.lineTo(561f, 200f)
        path.lineTo(551f, 223f)
        path.lineTo(379f, 223f)
        path.close()
        strokePath(canvas, path, 1.2f, foreground)
        presetFuel(canvas, 385f, 204f, step = 29f, fill = accent)
    }

    private fun twinDials(canvas: Canvas) {
        val speedX = 189f
        val rpmX = 640f - speedX
        val y = 176f
        val radius = 116f
        val start = 145f
        val sweep = 250f
        arc(canvas, speedX - radius, y - radius, speedX + radius, y + radius, 128f, 284f, 12f, 0xff151c25.toInt())
        val inner = radius - 8f
        arc(canvas, speedX - inner, y - inner, speedX + inner, y + inner, 134f, 272f, 1.5f, 0xff2b3239.toInt())
        arc(canvas, speedX - radius, y - radius, speedX + radius, y + radius, 128f, 284f, 3f, foreground)
        for (angle in 190..350 step 40) {
            dialTick(canvas, speedX, y, radius, Math.toRadians(angle.toDouble()), 14f, 2f)
        }
        // The forward slant shifts the visible glyphs to the right of the text advance.
        sportSpeed(canvas, speedX - 5f, 228f, 147f, unitX = speedX, unitBelow = true, skew = -.12f)
        arc(canvas, rpmX - radius, y - radius, rpmX + radius, y + radius, start, sweep, 9f, ColorUtils.blendARGB(accent, Color.BLACK, .72f))
        arc(canvas, rpmX - radius, y - radius, rpmX + radius, y + radius, start, sweep, 3.5f, accent)
        arc(canvas, rpmX - radius, y - radius, rpmX + radius, y + radius, start + sweep * .8f, sweep * .2f, 3.5f, redline)
        for (tick in 1 until 50) {
            if (tick % 5 == 0) continue
            val angle = Math.toRadians((start + sweep * tick / 50f).toDouble())
            dialTick(canvas, rpmX, y, radius - 4f, angle, 5f, 1f, if (tick >= 40) redline else secondary)
        }
        dialTicks(canvas, rpmX, y, radius, start, sweep)
        val angle = Math.toRadians((start + sweep * .26f).toDouble())
        dialTick(canvas, rpmX, y, 103f, angle, 100f, 4.5f, accent)
        dialTick(canvas, rpmX, y, 97f, angle, 89f, 1.5f, Color.WHITE)
        paint.color = rail
        canvas.drawCircle(rpmX, y, 12f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(rpmX, y, 9f, paint)
        text(canvas, "×1000 rpm", rpmX, 248f, 22f, foreground, presetFace)
        presetFuel(canvas, speedX - 43.5f, 296f, framed = true)
    }

    private val redline = 0xffff6860.toInt()

    private fun dialTicks(canvas: Canvas, x: Float, y: Float, radius: Float, start: Float, sweep: Float, redFrom: Int = 9) {
        for (rpm in 0..10) {
            val angle = Math.toRadians((start + sweep * rpm / 10f).toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            dialTick(canvas, x, y, radius, angle, 11f, 2f, if (rpm >= redFrom) redline else foreground)
            text(canvas, rpm.toString(), x + dx * (radius - 26f), y + dy * (radius - 26f) + 8f, 22f, if (rpm >= redFrom) redline else foreground, presetFace)
        }
    }

    private fun dialTick(canvas: Canvas, x: Float, y: Float, radius: Float, angle: Double, length: Float, width: Float, color: Int = foreground) {
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        line(canvas, x + dx * (radius - length), y + dy * (radius - length), x + dx * radius, y + dy * radius, width, color)
    }

    private fun presetFuel(canvas: Canvas, x: Float, y: Float, framed: Boolean = false, step: Float = 20f, fill: Int = foreground) {
        if (framed) {
            path.reset()
            path.addRoundRect(x - 60f, y - 8f, x + 147f, y + 24f, 16f, 16f, Path.Direction.CW)
            strokePath(canvas, path, 1.5f, foreground)
        }
        fuelIcon(canvas, x - 43f, y + 6f)
        text(canvas, "E", x - 15f, y + 16f, 22f, foreground, presetFace)
        repeat(6) { index ->
            val left = x + index * step
            path.reset()
            path.moveTo(left + 4f, y)
            path.lineTo(left + step - 1f, y)
            path.lineTo(left + step - 5f, y + 15f)
            path.lineTo(left, y + 15f)
            path.close()
            if (index < 4) {
                paint.color = fill
                canvas.drawPath(path, paint)
            } else {
                strokePath(canvas, path, 1f, secondary)
            }
        }
        text(canvas, "F", x + step * 6f + 10f, y + 16f, 22f, foreground, presetFace)
    }

    private fun fuelIcon(canvas: Canvas, x: Float, y: Float) {
        path.reset()
        path.addRect(x - 7f, y - 10f, x + 5f, y + 10f, Path.Direction.CW)
        path.moveTo(x - 9f, y + 10f)
        path.lineTo(x + 7f, y + 10f)
        path.moveTo(x - 5f, y - 3f)
        path.lineTo(x + 3f, y - 3f)
        path.moveTo(x + 5f, y + 1f)
        path.lineTo(x + 8f, y + 1f)
        path.lineTo(x + 8f, y + 6f)
        path.quadTo(x + 12f, y + 10f, x + 12f, y + 5f)
        path.lineTo(x + 12f, y - 6f)
        path.lineTo(x + 9f, y - 9f)
        strokePath(canvas, path, 2f, foreground)
    }

    private fun presetReadouts(canvas: Canvas) {
        val primary = if (state.readout == TftReadout.TRIP) TftReadout.ODO else TftReadout.TRIP
        val selected = if (state.readout == TftReadout.TPMS) TftReadout.ODO else state.readout
        val tpmsColor = if (state.readout == TftReadout.TPMS && state.style != TftStyle.TWIN_DIALS) accent else foreground
        if (state.style == TftStyle.AMBER_SWEEP) {
            rect(canvas, 0f, 322f, 640f, 384f, Color.BLACK)
            rect(canvas, 18f, 322f, 622f, 324f, accent)
            line(canvas, 195f, 332f, 177f, 379f, 1f, foreground)
            line(canvas, 411f, 332f, 393f, 379f, 1f, foreground)
            text(canvas, "F 2.4 bar  30°C", 100f, 347f, 22f, tpmsColor, presetFace)
            text(canvas, "R 2.4 bar  30°C", 100f, 373f, 22f, tpmsColor, presetFace)
            text(canvas, primary.sample, 294f, 347f, 22f, foreground, presetFace)
            text(canvas, selected.sample, 294f, 373f, 22f, foreground, presetFace, maxWidth = 176f)
            presetFuel(canvas, 476f, 348f)
        } else {
            rect(canvas, 0f, 335f, 640f, 384f, Color.BLACK)
            when (state.style) {
                TftStyle.BLUE_ARC -> {
                    line(canvas, 18f, 337f, 301f, 337f, 1.5f, accent)
                    path.reset()
                    path.moveTo(18f, 378f)
                    path.lineTo(304f, 378f)
                    path.quadTo(312f, 378f, 317f, 370f)
                    path.lineTo(334f, 345f)
                    path.quadTo(340f, 337f, 350f, 337f)
                    path.lineTo(622f, 337f)
                    strokePath(canvas, path, 1.5f, accent)
                }
                TftStyle.SPLIT_BARS -> {
                    line(canvas, 18f, 337f, 622f, 337f, 2f, accent)
                    line(canvas, 336f, 337f, 316f, 378f, 2f, accent)
                }
                else -> {
                    line(canvas, 18f, 337f, 622f, 337f, 1.5f, foreground)
                    line(canvas, 320f, 346f, 320f, 374f, 1f, rail)
                }
            }
            for (x in listOf(169f, 471f)) line(canvas, x, 346f, x, 374f, 1f, rail)
            text(canvas, "F 2.4 bar 30°C", 93.5f, 367f, 22f, tpmsColor, presetFace, maxWidth = 140f)
            text(canvas, "R 2.4 bar 30°C", 244.5f, 367f, 22f, tpmsColor, presetFace, maxWidth = 140f)
            text(canvas, primary.sample, 395.5f, 367f, 22f, foreground, presetFace, maxWidth = 140f)
            text(canvas, selected.sample, 546.5f, 367f, 22f, foreground, presetFace, maxWidth = 140f)
        }
    }

    private fun apex(canvas: Canvas) {
        repeat(10) { index ->
            val x = 27f + index * 59
            val y = 108f - index * 4
            rect(canvas, x, y, x + 52, 146f, segment(index))
        }
        text(canvas, "0              RPM ×1000             10", 320f, 169f, 17f)
        speed(canvas, 320f, 280f)
        fuel(canvas, 459f, 302f)
    }

    private fun vector(canvas: Canvas) {
        rect(canvas, 321f, 72f, 323f, 322f, rail)
        speed(canvas, 170f, 237f)
        arc(canvas, 371f, 75f, 596f, 300f, 145f, 251f, 18f, rail)
        arc(canvas, 371f, 75f, 596f, 300f, 145f, 125f, 16f, accent)
        text(canvas, "5.0", 484f, 200f, 48f, foreground)
        text(canvas, "RPM ×1000", 484f, 230f, 16f)
        fuel(canvas, 420f, 305f)
    }

    private fun grid(canvas: Canvas) {
        text(canvas, "RPM ×1000", 123f, 88f, 16f)
        repeat(10) { index ->
            val y = 306f - index * 21
            rect(canvas, 66f, y, 105f + index * 9, y + 10, segment(index))
        }
        rect(canvas, 220f, 72f, 222f, 326f, accent)
        speed(canvas, 459f, 238f)
        fuel(canvas, 365f, 303f)
    }

    private fun cyberpunk(canvas: Canvas) {
        val buildingHeights = listOf(111, 71, 94, 54, 68, 45, 45, 74, 48, 95, 78, 119)
        buildingHeights.forEachIndexed { index, height ->
            val x = 25f + index * 49
            rect(canvas, x, 211f - height, x + 42, 211f, 0x90213243.toInt())
            rect(canvas, x, 211f - height, x + 42, 213f - height, ColorUtils.setAlphaComponent(accent, 120))
        }
        rect(canvas, 183f, 102f, 453f, 245f, 0xd0000000.toInt())
        rect(canvas, 183f, 102f, 453f, 105f, accent)
        rect(canvas, 183f, 249f, 453f, 252f, accent)
        speed(canvas, 318f, 213f)
        text(canvas, "FUEL", 558f, 130f, 16f)
        repeat(6) { index -> rect(canvas, 539f, 144f + index * 17, 577f, 155f + index * 17, if (index < 2) rail else accent) }
        repeat(10) { index ->
            val x = 27f + index * 59
            path.reset()
            path.moveTo(x + 12, 280f)
            path.lineTo(x + 54, 280f)
            path.lineTo(x + 42, 307f)
            path.lineTo(x, 307f)
            path.close()
            paint.color = segment(index)
            canvas.drawPath(path, paint)
        }
        text(canvas, "RPM ×1000", 320f, 331f, 16f)
    }

    private fun mirror(canvas: Canvas) {
        rect(canvas, 20f, 65f, 620f, 320f, 0xff14262f.toInt())
        path.reset()
        path.moveTo(366f, 291f)
        path.lineTo(333f, 218f)
        path.lineTo(239f, 187f)
        path.lineTo(283f, 120f)
        path.lineTo(399f, 82f)
        paint.color = accent
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 11f
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        rect(canvas, 40f, 69f, 208f, 136f, Color.BLACK)
        text(canvas, "200 m   ›", 123f, 112f, 25f, foreground)
        text(canvas, "5.0 RPM ×1000", 152f, 364f, 22f)
        text(canvas, "SCREEN PROJECTION", 483f, 364f, 22f)
    }

    private fun segment(index: Int) = when {
        index < 5 -> accent
        index >= 8 -> 0xffe86759.toInt()
        else -> rail
    }

    private fun fuel(canvas: Canvas, x: Float, y: Float) {
        text(canvas, "FUEL", x - 34, y + 13, 16f)
        repeat(6) { index -> rect(canvas, x + index * 20, y, x + index * 20 + 15, y + 15, if (index < 4) accent else rail) }
    }

    private val speedColor get() = state.digits?.takeIf {
            ColorUtils.calculateContrast(it, Color.BLACK) >= 4.5
        } ?: foreground

    private fun sportSpeed(canvas: Canvas, x: Float, y: Float, size: Float, unitX: Float, unitBelow: Boolean = false, skew: Float = 0f) {
        text(canvas, "64", x, y, size, speedColor, sportFace, skew = skew, backplate = false)
        text(canvas, "km/h", unitX, if (unitBelow) y + 26f else y - 4f, 23f, foreground, presetFace, backplate = false)
    }

    private fun squareSpeed(canvas: Canvas, x: Float, bottom: Float, height: Float, widthFactor: Float, unitX: Float) {
        val scale = height / 100f
        val width = 206f * scale * widthFactor
        val left = x - width / 2f
        val top = bottom - height
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale * widthFactor, scale)
        if (state.photo != null) strokePath(canvas, squareSpeedSample, 3f, speedOutline(speedColor))
        paint.color = speedColor
        canvas.drawPath(squareSpeedSample, paint)
        canvas.restore()
        text(canvas, "km/h", unitX, bottom - 3f, 23f, foreground, presetFace, backplate = false)
    }

    private fun speed(canvas: Canvas, x: Float, y: Float) {
        text(canvas, "64", x, y, 122f, speedColor, speedFace, backplate = false)
        text(canvas, "km/h", x, y + 29, 19f, backplate = false)
    }

    private fun rect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.color = color
        canvas.drawRect(l, t, r, b, paint)
    }

    private fun arc(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, width: Float, color: Int) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        canvas.drawArc(l, t, r, b, start, sweep, false, paint)
        paint.style = Paint.Style.FILL
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        paint.color = color
        paint.strokeWidth = width
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    private fun strokePath(canvas: Canvas, value: Path, width: Float, color: Int) {
        paint.color = color
        paint.strokeWidth = width
        paint.style = Paint.Style.STROKE
        canvas.drawPath(value, paint)
        paint.style = Paint.Style.FILL
    }

    private fun speedOutline(color: Int) =
        if (ColorUtils.calculateLuminance(color) > .35) Color.BLACK else Color.WHITE

    private fun text(
        canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int = secondary,
        face: Typeface = mono, maxWidth: Float = Float.POSITIVE_INFINITY, skew: Float = 0f,
        minSize: Float = 22f, backplate: Boolean = true,
    ) {
        paint.color = color
        paint.typeface = face
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = max(size, minSize)
        paint.textScaleX = 1f
        paint.textSkewX = skew
        val textWidth = paint.measureText(value)
        if (textWidth > maxWidth) paint.textScaleX = maxWidth / textWidth
        if (state.photo != null && backplate) {
            val halfWidth = paint.measureText(value) / 2f
            bounds.set(x - halfWidth - 6f, y + paint.ascent(), x + halfWidth + 6f, y + paint.descent())
            paint.color = 0xd9000000.toInt()
            canvas.drawRoundRect(bounds, 5f, 5f, paint)
        } else if (state.photo != null) {
            paint.color = speedOutline(color)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (size * .03f).coerceIn(1.8f, 3.2f)
            canvas.drawText(value, x, y, paint)
            paint.style = Paint.Style.FILL
        }
        paint.color = color
        canvas.drawText(value, x, y, paint)
    }
}
