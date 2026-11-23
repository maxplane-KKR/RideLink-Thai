package dev.zanderp.opencfmoto

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** ตรวจภาพ GL บนมือถือด้วยเฟรมสีจำลอง ไม่เชื่อมรถหรือใช้ข้อมูลส่วนตัว */
class LiveLayerSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val status = LiveLayerSmokeMain.runCheck()
        val result = Bundle().apply { putString("result", status) }
        finish(if (status == "pass") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}

object LiveLayerSmokeMain {
    @JvmStatic fun main(args: Array<String>) {
        val status = runCheck()
        println("live_layer_smoke=$status")
        if (status != "pass") exitProcess(1)
    }

    fun runCheck(): String {
        val compositor = checkCompositor()
        return if (compositor == "pass") checkBikeEncoder() else compositor
    }

    private fun checkCompositor(): String {
        val compositor = AaCompositor { }
        val reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 3)
        val preview = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 3)
        val requestedColor = AtomicInteger(Color.RED)
        var renderedColor = Color.RED
        val liveBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        var stage = "setup"
        try {
            compositor.start(64, 64)
            compositor.setOutput(reader.surface, 64, 64, 64, 64, ScreenFit.FILL)
            val input = checkNotNull(compositor.inputSurface)
            stage = "initial_aa_frame"
            drawInput(input)
            check(awaitColor(reader, Color.BLUE))
            stage = "live_layer_frame"
            compositor.setLiveLayer { _, _ ->
                val nextColor = requestedColor.get()
                if (nextColor != renderedColor) {
                    liveBitmap.eraseColor(nextColor)
                    renderedColor = nextColor
                }
                liveBitmap
            }
            check(awaitColor(reader, Color.RED))
            stage = "static_frame_not_resubmitted"
            SystemClock.sleep(500)
            reader.acquireLatestImage()?.use { error("Static frame submitted before keep-alive") }
            stage = "static_frame_keep_alive"
            check(awaitColor(reader, Color.RED, timeoutMs = 3_000L))
            stage = "changed_pixels_in_same_bitmap"
            requestedColor.set(Color.GREEN)
            check(awaitColor(reader, Color.GREEN, timeoutMs = 1_000L))
            stage = "restore_aa_frame"
            compositor.setLiveLayer(null)
            drawInput(input)
            check(awaitColor(reader, Color.BLUE))
            stage = "detach_bike_output_preserves_preview"
            compositor.setPreview(preview.surface, 64, 64)
            check(compositor.clearOutput())
            reader.acquireLatestImage()?.close()
            drawInput(input, Color.GREEN)
            check(awaitColor(preview, Color.GREEN))
            reader.acquireLatestImage()?.use { error("Detached bike surface still receives frames") }
            stage = "reattach_bike_output"
            compositor.setOutput(reader.surface, 64, 64, 64, 64, ScreenFit.FILL)
            drawInput(input, Color.RED)
            check(awaitColor(reader, Color.RED))
            return "pass"
        } catch (error: Throwable) {
            return "failed:$stage:${error.javaClass.simpleName}"
        } finally {
            compositor.release()
            reader.close()
            preview.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun checkBikeEncoder(): String {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val preferences = Proxy.newProxyInstance(
            LiveLayerSmokeMain::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> args[1]
                "contains" -> args[0] == "bikes_json"
                else -> error("Unexpected preference access: ${method.name}")
            }
        } as SharedPreferences
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext() = this
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        val pipeline = VideoPipeline(context, 800, 368, { Log.i("RideLinkPowerSmoke", it) },
            externalSource = true, compositor = true)
        var stage = "configure_bike_encoder"
        try {
            pipeline.start()
            val input = checkNotNull(pipeline.decoderInputSurface())
            pipeline.configureBikeCanvas(800, 368)
            val firstOutput = checkNotNull(pipeline.encoderInputSurface())
            stage = "first_encoded_bike_frame"
            check(awaitEncodedFrame(pipeline, input, Color.BLUE))

            stage = "release_bike_encoder"
            check(pipeline.releaseBikeEncoder())
            check(!firstOutput.isValid && pipeline.encoderInputSurface() == null)
            check(pipeline.decoderInputSurface() === input && input.isValid)
            check(pipeline.codecConfigBytes() == null && pipeline.pollFrame(300) == null)

            stage = "recreate_bike_encoder_at_same_size"
            pipeline.configureBikeCanvas(800, 368)
            val nextOutput = checkNotNull(pipeline.encoderInputSurface())
            check(nextOutput !== firstOutput && nextOutput.isValid)
            pipeline.onBikeDataStart()
            stage = "recreate_bike_encoder_static_keyframe"
            check(pipeline.pollFrame(600) != null && pipeline.codecConfigBytes()?.isNotEmpty() == true)
            check(awaitEncodedFrame(pipeline, input, Color.GREEN))
            return "pass"
        } catch (error: Throwable) {
            return "failed:$stage:${error.javaClass.simpleName}"
        } finally {
            pipeline.stop()
        }
    }

    private fun awaitEncodedFrame(pipeline: VideoPipeline, input: Surface, color: Int): Boolean {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            drawInput(input, color)
            if (pipeline.pollFrame(200) != null && pipeline.codecConfigBytes()?.isNotEmpty() == true) return true
        }
        return false
    }

    private fun drawInput(surface: Surface, color: Int = Color.BLUE) {
        val canvas = surface.lockCanvas(null)
        try { canvas.drawColor(color) } finally { surface.unlockCanvasAndPost(canvas) }
    }

    private fun awaitColor(reader: ImageReader, expected: Int, timeoutMs: Long = 8_000L): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val image = reader.acquireLatestImage()
            if (image != null) {
                image.use {
                    val plane = it.planes[0]
                    val offset = 32 * plane.rowStride + 32 * plane.pixelStride
                    val r = plane.buffer.get(offset).toInt() and 0xff
                    val g = plane.buffer.get(offset + 1).toInt() and 0xff
                    val b = plane.buffer.get(offset + 2).toInt() and 0xff
                    if (kotlin.math.abs(r - Color.red(expected)) < 70 &&
                        kotlin.math.abs(g - Color.green(expected)) < 70 &&
                        kotlin.math.abs(b - Color.blue(expected)) < 70) return true
                }
            }
            SystemClock.sleep(50)
        }
        return false
    }
}
