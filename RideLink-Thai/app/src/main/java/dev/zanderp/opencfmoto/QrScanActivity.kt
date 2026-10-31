package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QrScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvScanStatus: TextView
    private lateinit var btnCancelScan: Button
    private lateinit var btnBackScan: ImageButton
    private lateinit var btnToggleTorch: ImageButton
    private lateinit var btnZoom1x: Button
    private lateinit var btnZoom2x: Button
    private lateinit var btnZoom3x: Button
    private lateinit var viewLaser: View

    private var camera: Camera? = null
    private var isTorchOn = false
    private var isScanned = false
    private var lastAutoZoomTime = 0L

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var barcodeScanner: BarcodeScanner

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "จำเป็นต้องใช้สิทธิ์กล้องเพื่อสแกน QR รถ", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)

        initViews()
        initScanner()
        setupListeners()
        startLaserAnimation()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        btnCancelScan = findViewById(R.id.btnCancelScan)
        btnBackScan = findViewById(R.id.btnBackScan)
        btnToggleTorch = findViewById(R.id.btnToggleTorch)
        btnZoom1x = findViewById(R.id.btnZoom1x)
        btnZoom2x = findViewById(R.id.btnZoom2x)
        btnZoom3x = findViewById(R.id.btnZoom3x)
        viewLaser = findViewById(R.id.viewLaser)
    }

    private fun initScanner() {
        // Google ML Kit Barcode Scanner with Auto-Zoom Enabled
        val zoomSuggestionOptions = ZoomSuggestionOptions.Builder { zoomRatio ->
            val control = camera?.cameraControl ?: return@Builder false
            control.setZoomRatio(zoomRatio)
            runOnUiThread {
                updateZoomButtonUi(zoomRatio)
                tvScanStatus.text = "🔍 ซูมอัตโนมัติ (%.1fx)".format(zoomRatio)
            }
            true
        }.setMaxSupportedZoomRatio(4.0f).build()

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAllPotentialBarcodes()
            .setZoomSuggestionOptions(zoomSuggestionOptions)
            .build()

        barcodeScanner = BarcodeScanning.getClient(options)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        val cancelAction = View.OnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        btnCancelScan.setOnClickListener(cancelAction)
        btnBackScan.setOnClickListener(cancelAction)

        btnToggleTorch.setOnClickListener {
            val control = camera?.cameraControl ?: return@setOnClickListener
            isTorchOn = !isTorchOn
            control.enableTorch(isTorchOn)
            btnToggleTorch.setColorFilter(
                ContextCompat.getColor(this, if (isTorchOn) R.color.green_neon else R.color.amber_accent)
            )
        }

        btnZoom1x.setOnClickListener { setManualZoom(1.0f) }
        btnZoom2x.setOnClickListener { setManualZoom(2.0f) }
        btnZoom3x.setOnClickListener { setManualZoom(3.0f) }

        // Tap-to-focus on the screen
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
                tvScanStatus.text = "🎯 โฟกัสตามจุดที่แตะ"
            }
            true
        }
    }

    private fun setManualZoom(ratio: Float) {
        val control = camera?.cameraControl ?: return
        val maxRatio = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 4.0f
        val clamped = ratio.coerceIn(1.0f, maxRatio)
        control.setZoomRatio(clamped)
        updateZoomButtonUi(clamped)
    }

    private fun updateZoomButtonUi(ratio: Float) {
        val activeBg = ContextCompat.getDrawable(this, R.drawable.dock_pill_active)
        val inactiveBg = null
        val activeText = ContextCompat.getColor(this, R.color.text_primary)
        val inactiveText = ContextCompat.getColor(this, R.color.text_secondary)

        when {
            ratio < 1.5f -> {
                btnZoom1x.background = activeBg
                btnZoom1x.setTextColor(activeText)
                btnZoom2x.background = inactiveBg
                btnZoom2x.setTextColor(inactiveText)
                btnZoom3x.background = inactiveBg
                btnZoom3x.setTextColor(inactiveText)
            }
            ratio < 2.5f -> {
                btnZoom2x.background = activeBg
                btnZoom2x.setTextColor(activeText)
                btnZoom1x.background = inactiveBg
                btnZoom1x.setTextColor(inactiveText)
                btnZoom3x.background = inactiveBg
                btnZoom3x.setTextColor(inactiveText)
            }
            else -> {
                btnZoom3x.background = activeBg
                btnZoom3x.setTextColor(activeText)
                btnZoom1x.background = inactiveBg
                btnZoom1x.setTextColor(inactiveText)
                btnZoom2x.background = inactiveBg
                btnZoom2x.setTextColor(inactiveText)
            }
        }
    }

    private fun startLaserAnimation() {
        val anim = TranslateAnimation(
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, 0f,
            Animation.RELATIVE_TO_PARENT, -0.4f,
            Animation.RELATIVE_TO_PARENT, 0.4f
        ).apply {
            duration = 1800
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        viewLaser.startAnimation(anim)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                LogBus.log("[QR] bindToLifecycle failed: $e")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !isScanned) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val box = barcode.boundingBox
                        if (box != null) {
                            handleDynamicAutoZoomAndFocus(box, imageProxy.width, imageProxy.height)
                        }

                        val raw = barcode.rawValue ?: barcode.displayValue ?: continue
                        val parsed = QrData.parse(raw)
                        if (parsed != null && !isScanned) {
                            isScanned = true
                            triggerHapticSuccess()
                            BikeMemory.save(this, raw, parsed)
                            runOnUiThread {
                                tvScanStatus.text = "✅ สแกนสำเร็จ: ${parsed.ssid}"
                                tvScanStatus.setTextColor(ContextCompat.getColor(this, R.color.green_neon))
                                Toast.makeText(this, "สแกนสำเร็จ: ${parsed.ssid}", Toast.LENGTH_SHORT).show()
                                val data = Intent().apply {
                                    putExtra("raw_qr", raw)
                                    putExtra("ssid", parsed.ssid)
                                }
                                setResult(Activity.RESULT_OK, data)
                                finish()
                            }
                            break
                        } else if (!isScanned) {
                            val hint = QrData.parseFailureHint(raw)
                            runOnUiThread {
                                tvScanStatus.text = hint ?: "⚠️ ตรวจพบ QR แต่ไม่ใช่ Wi-Fi สำหรับต่อจอรถ"
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    // Ignore transient frames
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun triggerHapticSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(120)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Intelligent auto-zoom and auto-focus lock when a QR is small or distant on TFT screen
     */
    private fun handleDynamicAutoZoomAndFocus(box: Rect, frameWidth: Int, frameHeight: Int) {
        val now = System.currentTimeMillis()
        if (now - lastAutoZoomTime < 1000) return // Throttling zoom adjustments

        val qrSize = maxOf(box.width(), box.height())
        val frameMin = minOf(frameWidth, frameHeight)
        val coverage = qrSize.toFloat() / frameMin.toFloat()

        // If QR coverage is under 35% of the frame (small on motorbike dash), zoom in and focus!
        if (coverage in 0.05f..0.35f) {
            lastAutoZoomTime = now
            val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
            val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 4.0f
            val desiredCoverage = 0.50f
            val targetZoom = (currentZoom * (desiredCoverage / coverage)).coerceIn(1.0f, minOf(maxZoom, 3.5f))

            if (targetZoom > currentZoom + 0.25f) {
                runOnUiThread {
                    camera?.cameraControl?.setZoomRatio(targetZoom)
                    updateZoomButtonUi(targetZoom)
                    tvScanStatus.text = "🎯 ล็อกเป้า & ซูมอัตโนมัติ (%.1fx)".format(targetZoom)
                }

                // Point auto-focus and auto-exposure metering directly at the center of the reticle
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(2, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }
}
