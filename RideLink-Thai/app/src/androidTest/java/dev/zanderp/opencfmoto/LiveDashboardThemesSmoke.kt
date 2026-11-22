package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.ImageReader
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/** ตรวจภาพจำลองบนมือถือ ไม่เชื่อมรถหรือใช้ตำแหน่งจริง */
object LiveDashboardThemesSmoke {
    private const val WIDTH = 800
    private const val HEIGHT = 368
    private data class Frame(val signature: Long, val isBlue: Boolean)

    @JvmStatic fun main(args: Array<String>) {
        var stage = "setup"
        val output = args.singleOrNull()?.let(::File)
        try {
            check(output?.isDirectory == true)
            // app_process ไม่ผ่าน Zygote จึงต้องโหลดฟอนต์ระบบในโปรเซสทดสอบเอง
            stage = "initialize_fonts"
            Class.forName("android.graphics.Typeface").getMethod("loadPreinstalledSystemFontMap").invoke(null)
            stage = "obd_typography"
            checkObdTypography()
            val sample = LiveDashboardData(speedKmh = 64.0, speedFromGps = true)
            val allObd = sample.copy(rpm = 2800.0, coolant = 82.0, volt = 14.5, load = 27.0,
                intake = 33.0, manifoldPressureKpa = 71.0, relativeThrottle = 0.0, obdLive = true)
            val images = mutableListOf<Bitmap>()
            stage = "gallery_does_not_replace_live_background"
            val racing = LiveDashboardSettings(design = LiveDashboardDesign.RACING)
            val concept = LiveDashboardSettings(design = LiveDashboardDesign.C01_NEO_SPORT)
            val racingFrame = checkNotNull(SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample, racing, "No vehicle data")
                .copy(Bitmap.Config.ARGB_8888, false))
            val racingBackground = privateBitmap(SelectedDashboardRenderer, "staticLayer")
            val conceptFrame = checkNotNull(SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample, concept, "No vehicle data")
                .copy(Bitmap.Config.ARGB_8888, false))
            val conceptBackground = privateBitmap(ConceptDashboardRenderer, "background")
            for (design in LiveDashboardDesign.entries) {
                SelectedDashboardRenderer.render(320, 147, LiveDashboardData(),
                    racing.copy(design = design), "No vehicle data", standalone = true).recycle()
            }
            check(privateBitmap(SelectedDashboardRenderer, "staticLayer") === racingBackground && !racingBackground.isRecycled)
            check(privateBitmap(ConceptDashboardRenderer, "background") === conceptBackground && !conceptBackground.isRecycled)
            check(racingFrame.sameAs(SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample, racing, "No vehicle data")))
            check(conceptFrame.sameAs(SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample, concept, "No vehicle data")))
            racingFrame.recycle()
            conceptFrame.recycle()
            stage = "render_variants"
            for (design in LiveDashboardDesign.entries) for (light in listOf(false, true)) {
                val config = LiveDashboardSettings(design = design, light = light)
                val preview = SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample, config, "No vehicle data", standalone = true)
                val live = SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample, config, "No vehicle data")
                check(preview.sameAs(live))
                check(images.none { it.sameAs(preview) })
                FileOutputStream(File(output, "${design.id}-${if (light) "day" else "night"}.png")).use {
                    check(preview.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                images.add(preview)
            }
            val noSpeed = SelectedDashboardRenderer.render(WIDTH, HEIGHT, LiveDashboardData(),
                LiveDashboardSettings(), "No vehicle data", standalone = true)
            check(!noSpeed.sameAs(images.first()))
            for (design in LiveDashboardDesign.entries) {
                val config = LiveDashboardSettings(design = design)
                val regular = SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample, config, "No vehicle data", standalone = true)
                val threeDigits = SelectedDashboardRenderer.render(WIDTH, HEIGHT,
                    LiveDashboardData(speedKmh = 128.0, speedFromGps = true), config, "No vehicle data", standalone = true)
                check(!regular.sameAs(threeDigits))
                FileOutputStream(File(output, "${design.id}-three-digit.png")).use {
                    check(threeDigits.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val allFields = SelectedDashboardRenderer.render(WIDTH, HEIGHT, allObd,
                    config.copy(visibleFields = LiveDashboardSpec.fields.toSet()), "No vehicle data", standalone = true)
                FileOutputStream(File(output, "${design.id}-all-obd.png")).use {
                    check(allFields.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val noSpeedField = SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample,
                    config.copy(visibleFields = config.visibleFields - "speed"), "No vehicle data", standalone = true)
                val scaled = SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample,
                    config.copy(speedMax = 120), "No vehicle data", standalone = true)
                check(!regular.sameAs(allFields) && !regular.sameAs(noSpeedField) && !regular.sameAs(scaled))
                if (design.isConcept) {
                    val stale = sample.copy(rpm = 7000.0, coolant = 95.0, volt = 14.5, obdLive = false)
                    val ignored = SelectedDashboardRenderer.render(WIDTH, HEIGHT, stale, config,
                        "No vehicle data", standalone = true)
                    check(regular.sameAs(ignored)) { "ข้อมูลรถที่ยังไม่พร้อมต้องไม่แสดงใน ${design.id}" }
                    val actual = SelectedDashboardRenderer.render(WIDTH, HEIGHT, stale.copy(obdLive = true), config,
                        "No vehicle data", standalone = true)
                    check(!regular.sameAs(actual)) { "ค่ารถที่มีแหล่งจริงต้องแสดงใน ${design.id}" }
                    for (y in 320 until HEIGHT) for (x in 0 until WIDTH) {
                        check(regular.getPixel(x, y) == noSpeedField.getPixel(x, y)) {
                            "ความเร็วทับขอบล่างใน ${design.id} ที่ $x,$y"
                        }
                    }
                    ignored.recycle()
                    actual.recycle()
                }
            }
            for ((w, h) in listOf(1024 to 425, 1024 to 464, 1280 to 720)) {
                for (design in LiveDashboardDesign.entries) {
                    val image = SelectedDashboardRenderer.render(w, h, sample,
                        LiveDashboardSettings(design = design), "No vehicle data", standalone = true)
                    check(image.width == w && image.height == h)
                }
            }
            val racingSettings = LiveDashboardSettings(design = LiveDashboardDesign.RACING)
            // ตัวเลขความเร็วต้องไม่ลงไปทับแถบเรือนไมล์เดิมบริเวณขอบล่าง
            stage = "racing_speed_bottom_inset"
            for ((w, h) in listOf(WIDTH to HEIGHT, 1024 to 425)) {
                val scale = minOf(w / 800f, h / 368f)
                val left = ((w - 800f * scale) / 2f).toInt().coerceAtLeast(0)
                val right = (left + 385f * scale).toInt().coerceAtMost(w)
                val bottomRegion = ((h - 368f * scale) / 2f + 320f * scale).toInt()
                for (value in listOf(0.0, 64.0, 128.0)) {
                    val data = LiveDashboardData(speedKmh = value, speedFromGps = true)
                    val withSpeed = SelectedDashboardRenderer.render(w, h, data, racingSettings,
                        "No vehicle data", standalone = true)
                    val withoutSpeed = SelectedDashboardRenderer.render(w, h, data,
                        racingSettings.copy(visibleFields = racingSettings.visibleFields - "speed"),
                        "No vehicle data", standalone = true)
                    for (y in bottomRegion until h) for (x in left until right) {
                        check(withSpeed.getPixel(x, y) == withoutSpeed.getPixel(x, y)) {
                            "R1 speed touches bottom region at $x,$y, size=$w x $h, speed=$value"
                        }
                    }
                    withSpeed.recycle()
                    withoutSpeed.recycle()
                }
            }
            val rpm10 = SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample, racingSettings, "No vehicle data", standalone = true)
            val rpm14 = SelectedDashboardRenderer.render(WIDTH, HEIGHT, sample,
                racingSettings.copy(rpmMax = 14_000), "No vehicle data", standalone = true)
            check(!rpm10.sameAs(rpm14))

            stage = "compositor_switch"
            val config = AtomicReference(LiveDashboardSettings())
            val liveEvents = java.util.Collections.synchronizedList(mutableListOf<LiveLayerEvent>())
            val milestones = java.util.Collections.synchronizedList(mutableListOf<String>())
            val attempt = LiveDashboardLogAttempt(123) { milestones.add(it) }
            attempt.attached(config.get(), gpsPermission = true)
            val compositor = AaCompositor { }
            val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
            try {
                compositor.start(WIDTH, HEIGHT)
                compositor.setOutput(reader.surface, WIDTH, HEIGHT, WIDTH, HEIGHT, ScreenFit.FILL)
                val input = checkNotNull(compositor.inputSurface)
                drawBlue(input)
                val blue = awaitFrame(reader) { it.isBlue }
                compositor.setLiveLayer({ w, h ->
                    SelectedDashboardRenderer.render(w, h, sample, config.get(), "No vehicle data").also {
                        attempt.bitmap(w, h, gpsFresh = true)
                    }
                }, { liveEvents.add(it); attempt.layerEvent(it) })
                val sport = awaitFrame(reader) { !it.isBlue && it.signature != blue.signature }
                config.set(config.get().copy(design = LiveDashboardDesign.RACING))
                val racing = awaitFrame(reader) { !it.isBlue && it.signature != sport.signature }
                config.set(config.get().copy(design = LiveDashboardDesign.TOURING, light = true))
                val touring = awaitFrame(reader) { !it.isBlue && it.signature != racing.signature }
                check(touring.signature != sport.signature)
                var previous = touring
                for (design in LiveDashboardDesign.entries.filter { it.isConcept }) {
                    config.set(config.get().copy(design = design, light = false))
                    previous = awaitFrame(reader) { !it.isBlue && it.signature != previous.signature }
                }
                check(liveEvents.count { it == LiveLayerEvent.COMPOSITED } == 1)
                check(liveEvents.none { it != LiveLayerEvent.COMPOSITED })
                attempt.videoSent()
                attempt.stop(byUser = true)
                check(milestones.count { it.contains("FIRST_BITMAP") } == 1)
                check(milestones.count { it.contains("FIRST_ENCODER_SUBMISSION") } == 1)
                check(milestones.count { it.contains("VIDEO_SENT_WHILE_ACTIVE") } == 1)
                compositor.setLiveLayer(null)
                drawBlue(input)
                awaitFrame(reader) { it.isBlue }
            } finally {
                compositor.release()
                reader.close()
            }
            println("dashboard_theme_smoke=pass")
        } catch (error: Throwable) {
            println("dashboard_theme_smoke=failed:$stage:${error.javaClass.simpleName}")
            error.printStackTrace()
            exitProcess(1)
        }
    }

    private fun checkObdTypography() {
        val samples = listOf(
            LiveDashboardData(rpm = 2800.0, coolant = 82.0, volt = 14.5, load = 27.0,
                intake = 33.0, manifoldPressureKpa = 71.0, relativeThrottle = 0.0, obdLive = true),
            LiveDashboardData(rpm = 16383.75, coolant = -40.0, volt = 65.535, load = 100.0,
                intake = 215.0, manifoldPressureKpa = 255.0, relativeThrottle = 100.0, obdLive = true),
            LiveDashboardData(),
        )
        val fields = LiveDashboardSpec.fields.filter { it != "speed" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = DashboardCardTypography.typeface }
        fun fitted(value: String, size: Float, width: Float): Float {
            paint.textSize = size
            val measured = paint.measureText(value)
            return if (measured > width) size * width / measured else size
        }
        fun ink(value: String, placement: DashboardTextPlacement): RectF {
            paint.textSize = placement.size
            val bounds = Rect()
            paint.getTextBounds(value, 0, value.length, bounds)
            return RectF(placement.x + bounds.left, placement.baseline + bounds.top,
                placement.x + bounds.right, placement.baseline + bounds.bottom)
        }
        for (mask in 1 until (1 shl fields.size)) {
            val visible = fields.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val sport = SelectedDashboardLayout.slots(LiveDashboardDesign.SPORT, visible.size).first()
            val sportSize = minOf(if (sport.width < 150f) 15f else 26f, sport.height * .42f)
            val available = sport.width - 22f - 12f
            val previous = mutableMapOf<Pair<LiveDashboardDesign, String>, DashboardCardTextPlan>()
            for (sample in samples) for (design in LiveDashboardDesign.entries.filter { it != LiveDashboardDesign.SPORT }) {
                val settings = LiveDashboardSettings(design = design, visibleFields = visible)
                val cards = SelectedDashboardLayout.cards(sample, settings)
                val slots = SelectedDashboardLayout.slots(design, cards.size)
                for ((card, slot) in cards.zip(slots)) {
                    val plan = DashboardCardTypography.plan(slot, card, design, visible.size)
                    val previousPlan = previous.put(design to card.id, plan)
                    if (previousPlan != null) {
                        check(plan.stacked == previousPlan.stacked && plan.label.x == previousPlan.label.x &&
                            plan.label.baseline == previousPlan.label.baseline &&
                            plan.value.baseline == previousPlan.value.baseline &&
                            (!plan.stacked || plan.value.x == previousPlan.value.x)) {
                            "ตำแหน่งข้อความเปลี่ยนเมื่อค่า OBD เปลี่ยน ${design.id} ${card.id} mask=$mask"
                        }
                    }
                    check(plan.label.size + .001f >= fitted(card.label, sportSize, available * .55f))
                    check(plan.value.size + .001f >= fitted(card.value, sportSize, available * .40f))
                    val label = ink(card.label, plan.label)
                    val value = ink(card.value, plan.value)
                    for (box in listOf(label, value)) {
                        check(box.left >= slot.x - .01f && box.right <= slot.x + slot.width + .01f &&
                            box.top >= slot.y - .01f && box.bottom <= slot.y + slot.height + .01f) {
                            "ข้อความออกนอกการ์ด ${design.id} ${card.id} mask=$mask"
                        }
                    }
                    check(!RectF.intersects(label, value)) { "ข้อความทับกัน ${design.id} ${card.id} mask=$mask" }
                }
            }
        }
    }

    private fun privateBitmap(owner: Any, name: String): Bitmap =
        owner.javaClass.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(owner) as Bitmap
        }

    private fun drawBlue(surface: Surface) {
        val canvas = surface.lockCanvas(null)
        try { canvas.drawColor(Color.BLUE) } finally { surface.unlockCanvasAndPost(canvas) }
    }

    private fun awaitFrame(reader: ImageReader, accept: (Frame) -> Boolean): Frame {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = reader.acquireLatestImage()
            if (image != null) image.use {
                val plane = it.planes[0]
                var hash = 1125899906842597L
                var bluePixels = 0
                var count = 0
                for (y in 12 until HEIGHT step 32) for (x in 12 until WIDTH step 32) {
                    val pos = y * plane.rowStride + x * plane.pixelStride
                    val r = plane.buffer.get(pos).toInt() and 0xff
                    val g = plane.buffer.get(pos + 1).toInt() and 0xff
                    val b = plane.buffer.get(pos + 2).toInt() and 0xff
                    hash = hash * 31 + (r shl 16) + (g shl 8) + b
                    if (b > 180 && r < 80 && g < 80) bluePixels++
                    count++
                }
                val frame = Frame(hash, bluePixels > count * 9 / 10)
                if (accept(frame)) return frame
            }
            SystemClock.sleep(50)
        }
        error("frame timeout")
    }
}
