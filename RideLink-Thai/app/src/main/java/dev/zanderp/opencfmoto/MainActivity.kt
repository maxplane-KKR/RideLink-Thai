package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.SurfaceTexture
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.zanderp.opencfmoto.aa.AaInput
import dev.zanderp.opencfmoto.aa.AaSelfMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    // Status, Header & Privacy-Masked Saved Bike Bar
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView
    private lateinit var layoutSavedBikeBar: LinearLayout
    private lateinit var tvSavedBike: TextView
    private lateinit var tvSavedWifi: TextView
    private lateinit var tvPrivacyToggleBadge: TextView
    private lateinit var tvOnlineBadge: TextView
    private lateinit var statusDot: View
    private lateinit var btnQuickStop: ImageButton

    // Dashboard Action Buttons
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnScanQr: Button
    private lateinit var btnManualWifi: Button
    private lateinit var btnTogglePreview: Button
    private lateinit var btnOpenStickCockpit: Button

    // Dashboard Live Viewport
    private lateinit var mainScrollView: FastScrollView
    private lateinit var layoutAaViewportContainer: LinearLayout
    private lateinit var tvLiveFpsBadge: TextView
    private lateinit var frameAaPreview: FrameLayout
    private lateinit var svAaPreview: TextureView
    private lateinit var layoutAaPlaceholder: LinearLayout
    private lateinit var btnAaMaps: Button
    private lateinit var btnAaMic: Button
    private lateinit var btnAaHome: Button
    private lateinit var btnAaBack: Button

    // Server / Native BT Status Card
    private lateinit var tvServerStatus: TextView
    private lateinit var btnCheckServer: TextView
    private lateinit var btnOpenAaSettings: Button

    // Tab 2: Full AA View + Dedicated Stick / Rotary / Trackpad Cockpit
    private lateinit var tvFullLiveFpsBadge: TextView
    private lateinit var frameFullAaPreview: FrameLayout
    private lateinit var svAaScreenFull: TextureView
    private var dashPreviewSurface: Surface? = null
    private var fullPreviewSurface: Surface? = null
    private lateinit var layoutFullAaPlaceholder: LinearLayout
    private lateinit var btnModeJoystick: Button
    private lateinit var btnModeTrackpad: Button
    private lateinit var layoutJoystickPanel: LinearLayout
    private lateinit var virtualStickAa: VirtualStickView
    private lateinit var btnRotaryPrev: Button
    private lateinit var btnStickConfirm: Button
    private lateinit var btnRotaryNext: Button
    private lateinit var frameTrackpadSurface: FrameLayout
    private lateinit var viewTrackpadCursor: View
    private lateinit var btnFullAaMaps: Button
    private lateinit var btnFullAaMic: Button
    private lateinit var btnFullAaHome: Button
    private lateinit var btnFullAaBack: Button
    private var btnFullAaStart: Button? = null

    // Panes
    private lateinit var paneDashboard: LinearLayout
    private lateinit var paneScreen: LinearLayout
    private lateinit var paneTft: LinearLayout
    private lateinit var paneSettings: LinearLayout

    // Tab 3 & Tab 4 (TFT Info + Settings + Manual Calibration)
    private lateinit var tvTftTitle: TextView
    private lateinit var tvTftStatus: TextView
    private lateinit var tvAutoConnectState: TextView
    private lateinit var btnToggleAutoConnect: Button

    private lateinit var rgManualResolution: RadioGroup
    private lateinit var rbResAuto: RadioButton
    private lateinit var rbRes800x480: RadioButton
    private lateinit var rbRes1280x720: RadioButton

    private lateinit var btnDpiMinus: Button
    private lateinit var tvDpiValue: TextView
    private lateinit var btnDpiPlus: Button
    private lateinit var btnDpiReset: Button

    private lateinit var btnScaleMinus: Button
    private lateinit var tvScaleValue: TextView
    private lateinit var btnScalePlus: Button

    private lateinit var tvMarginTop: TextView
    private lateinit var btnTopMinus: Button
    private lateinit var btnTopPlus: Button
    private lateinit var tvMarginBottom: TextView
    private lateinit var btnBottomMinus: Button
    private lateinit var btnBottomPlus: Button
    private lateinit var tvMarginLeft: TextView
    private lateinit var btnLeftMinus: Button
    private lateinit var btnLeftPlus: Button
    private lateinit var tvMarginRight: TextView
    private lateinit var btnRightMinus: Button
    private lateinit var btnRightPlus: Button

    private lateinit var btnPresetZontesSafe: Button
    private lateinit var btnPresetDeepSafe: Button
    private lateinit var btnPresetFullReset: Button

    private lateinit var rgProfile: RadioGroup
    private lateinit var rbProfileAuto: RadioButton
    private lateinit var rbProfileZontes: RadioButton
    private lateinit var rbProfileZontesHd: RadioButton
    private lateinit var rbProfileCfmotoLand: RadioButton
    private lateinit var rbProfileCfmotoPort: RadioButton

    private lateinit var rgFitMode: RadioGroup
    private lateinit var rbFitFill: RadioButton
    private lateinit var rbFitLetterbox: RadioButton
    private lateinit var rbFitStretch: RadioButton

    // Bottom Dock Tabs
    private lateinit var tabDashboard: LinearLayout
    private lateinit var tabScreen: LinearLayout
    private lateinit var tabTft: LinearLayout
    private lateinit var tabSettings: LinearLayout

    private lateinit var ivTabDashIcon: ImageView
    private lateinit var tvTabDashText: TextView
    private lateinit var ivTabScreenIcon: ImageView
    private lateinit var tvTabScreenText: TextView
    private lateinit var ivTabTftIcon: ImageView
    private lateinit var tvTabTftText: TextView
    private lateinit var ivTabSettingsIcon: ImageView
    private lateinit var tvTabSettingsText: TextView

    private var activeTabIndex = 0
    private var isPreviewVisible = false
    private var suppressSettingsCallbacks = false
    private var latestAutoWatcherReason = "สแตนด์บายรอ BT และหน้าเมนู QR บนจอรถ"

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        BikeAutoWatcher.init(this)
        checkServerPort()
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshSavedBikeInfo()
            initSettingsState()
            startAutoProjection("สแกนเมนู QR สำเร็จ — เริ่มเชื่อมต่อ Android Auto อัตโนมัติ")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenMargins.load(this)
        setContentView(R.layout.activity_main)
        enableHighRefreshRate()

        initViews()
        setupListeners()
        setupPreviewSurfaces()
        setupDedicatedControllers()
        setupTelemetryListeners()
        refreshSavedBikeInfo()
        initSettingsState()
        checkServerPort()
        requestNeededPermissions()
        BikeAutoWatcher.init(this)
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Unlocks 120Hz / maximum supported display refresh rate on Xiaomi HyperOS & high-refresh OLED
     * phones, which often throttle sideloaded apps to 60Hz unless explicitly requested.
     */
    @Suppress("DEPRECATION")
    private fun enableHighRefreshRate() {
        try {
            val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                windowManager.defaultDisplay
            } ?: return
            val active = disp.mode ?: return
            val bestMode = disp.supportedModes
                .filter { it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight }
                .maxByOrNull { it.refreshRate }
                ?: disp.supportedModes.maxByOrNull { it.refreshRate }
                ?: return
            val lp = window.attributes
            if (lp.preferredDisplayModeId != bestMode.modeId) {
                lp.preferredDisplayModeId = bestMode.modeId
                lp.preferredRefreshRate = bestMode.refreshRate
                window.attributes = lp
            }
        } catch (_: Exception) {
        }
    }

    private fun handleDebugIntent(intent: Intent?) {
        intent ?: return
        val tab = intent.getIntExtra("debug_tab", -1)
        if (tab in 0..3) {
            if (tab == 2) updateTftStatusText()
            if (tab == 3) refreshCalibrationUiReadouts()
            selectTab(tab)
        }
        val preview = intent.getIntExtra("debug_preview", -1)
        if (preview == 0 || preview == 1) {
            val want = preview == 1
            if (isPreviewVisible != want) {
                isPreviewVisible = want
                layoutAaViewportContainer.visibility = if (isPreviewVisible) View.VISIBLE else View.GONE
                btnTogglePreview.text = if (isPreviewVisible) "ซ่อนจอสด" else "แสดงจอสด"
                syncVisiblePreviewSurface()
            }
        }
        val scrollY = intent.getIntExtra("debug_scroll", -1)
        if (scrollY >= 0) {
            mainScrollView.post { mainScrollView.scrollTo(0, scrollY) }
        }
    }

    override fun onResume() {
        super.onResume()
        AaSelfMode.foregroundActivityProvider = {
            if (!isFinishing && !isDestroyed) this else null
        }
        BikeAutoWatcher.onAppForegroundChanged(this, true)
        checkServerPort()
        syncVisiblePreviewSurface()
        updateFpsAndPlaceholderUi(AaVideoBridge.currentFps)
    }

    override fun onPause() {
        if (AaSelfMode.foregroundActivityProvider?.invoke() === this) {
            AaSelfMode.foregroundActivityProvider = null
        }
        BikeAutoWatcher.onAppForegroundChanged(this, false)
        super.onPause()
    }

    override fun onDestroy() {
        AaVideoBridge.fpsListener = null
        BikeAutoWatcher.statusListener = null
        dashPreviewSurface?.let {
            AaVideoBridge.unbindPreview(it)
            it.release()
        }
        dashPreviewSurface = null
        fullPreviewSurface?.let {
            AaVideoBridge.unbindPreview(it)
            it.release()
        }
        fullPreviewSurface = null
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            runtimePermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Starts or restarts [SessionService]. Note: [SessionService] binds `:5288` synchronously
     * FIRST and then triggers [AaSelfMode] once using [AaSelfMode.foregroundActivityProvider],
     * eliminating the premature double-trigger race from previous versions.
     */
    private fun startAutoProjection(toastMsg: String) {
        BikeAutoWatcher.onManualStartOrSessionActive()
        updateTftStatusText()

        val intent = Intent(this, SessionService::class.java).apply {
            action = SessionService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun initViews() {
        findViewById<TextView>(R.id.tvAppVersion)?.text = "v${BuildConfig.VERSION_NAME}"
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDesc = findViewById(R.id.tvStatusDesc)
        layoutSavedBikeBar = findViewById(R.id.layoutSavedBikeBar)
        tvSavedBike = findViewById(R.id.tvSavedBike)
        tvSavedWifi = findViewById(R.id.tvSavedWifi)
        tvPrivacyToggleBadge = findViewById(R.id.tvPrivacyToggleBadge)
        tvOnlineBadge = findViewById(R.id.tvOnlineBadge)
        statusDot = findViewById(R.id.statusDot)
        btnQuickStop = findViewById(R.id.btnQuickStop)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnScanQr = findViewById(R.id.btnScanQr)
        btnManualWifi = findViewById(R.id.btnManualWifi)
        btnTogglePreview = findViewById(R.id.btnTogglePreview)
        btnOpenStickCockpit = findViewById(R.id.btnOpenStickCockpit)

        mainScrollView = findViewById(R.id.mainScrollView)
        layoutAaViewportContainer = findViewById(R.id.layoutAaViewportContainer)
        tvLiveFpsBadge = findViewById(R.id.tvLiveFpsBadge)
        frameAaPreview = findViewById(R.id.frameAaPreview)
        svAaPreview = findViewById(R.id.svAaPreview)
        layoutAaPlaceholder = findViewById(R.id.layoutAaPlaceholder)
        btnAaMaps = findViewById(R.id.btnAaMaps)
        btnAaMic = findViewById(R.id.btnAaMic)
        btnAaHome = findViewById(R.id.btnAaHome)
        btnAaBack = findViewById(R.id.btnAaBack)

        tvServerStatus = findViewById(R.id.tvServerStatus)
        btnCheckServer = findViewById(R.id.btnCheckServer)
        btnOpenAaSettings = findViewById(R.id.btnOpenAaSettings)

        // Tab 2 Views
        tvFullLiveFpsBadge = findViewById(R.id.tvFullLiveFpsBadge)
        frameFullAaPreview = findViewById(R.id.frameFullAaPreview)
        svAaScreenFull = findViewById(R.id.svAaScreenFull)
        layoutFullAaPlaceholder = findViewById(R.id.layoutFullAaPlaceholder)
        btnModeJoystick = findViewById(R.id.btnModeJoystick)
        btnModeTrackpad = findViewById(R.id.btnModeTrackpad)
        layoutJoystickPanel = findViewById(R.id.layoutJoystickPanel)
        virtualStickAa = findViewById(R.id.virtualStickAa)
        btnRotaryPrev = findViewById(R.id.btnRotaryPrev)
        btnStickConfirm = findViewById(R.id.btnStickConfirm)
        btnRotaryNext = findViewById(R.id.btnRotaryNext)
        frameTrackpadSurface = findViewById(R.id.frameTrackpadSurface)
        viewTrackpadCursor = findViewById(R.id.viewTrackpadCursor)
        btnFullAaMaps = findViewById(R.id.btnFullAaMaps)
        btnFullAaMic = findViewById(R.id.btnFullAaMic)
        btnFullAaHome = findViewById(R.id.btnFullAaHome)
        btnFullAaBack = findViewById(R.id.btnFullAaBack)
        btnFullAaStart = findViewById(R.id.btnFullAaStart)

        paneDashboard = findViewById(R.id.paneDashboard)
        paneScreen = findViewById(R.id.paneScreen)
        paneTft = findViewById(R.id.paneTft)
        paneSettings = findViewById(R.id.paneSettings)

        // Tab 3 & Tab 4 Views
        tvTftTitle = findViewById(R.id.tvTftTitle)
        tvTftStatus = findViewById(R.id.tvTftStatus)
        tvAutoConnectState = findViewById(R.id.tvAutoConnectState)
        btnToggleAutoConnect = findViewById(R.id.btnToggleAutoConnect)

        rgManualResolution = findViewById(R.id.rgManualResolution)
        rbResAuto = findViewById(R.id.rbResAuto)
        rbRes800x480 = findViewById(R.id.rbRes800x480)
        rbRes1280x720 = findViewById(R.id.rbRes1280x720)

        btnDpiMinus = findViewById(R.id.btnDpiMinus)
        tvDpiValue = findViewById(R.id.tvDpiValue)
        btnDpiPlus = findViewById(R.id.btnDpiPlus)
        btnDpiReset = findViewById(R.id.btnDpiReset)

        btnScaleMinus = findViewById(R.id.btnScaleMinus)
        tvScaleValue = findViewById(R.id.tvScaleValue)
        btnScalePlus = findViewById(R.id.btnScalePlus)

        tvMarginTop = findViewById(R.id.tvMarginTop)
        btnTopMinus = findViewById(R.id.btnTopMinus)
        btnTopPlus = findViewById(R.id.btnTopPlus)
        tvMarginBottom = findViewById(R.id.tvMarginBottom)
        btnBottomMinus = findViewById(R.id.btnBottomMinus)
        btnBottomPlus = findViewById(R.id.btnBottomPlus)
        tvMarginLeft = findViewById(R.id.tvMarginLeft)
        btnLeftMinus = findViewById(R.id.btnLeftMinus)
        btnLeftPlus = findViewById(R.id.btnLeftPlus)
        tvMarginRight = findViewById(R.id.tvMarginRight)
        btnRightMinus = findViewById(R.id.btnRightMinus)
        btnRightPlus = findViewById(R.id.btnRightPlus)

        btnPresetZontesSafe = findViewById(R.id.btnPresetZontesSafe)
        btnPresetDeepSafe = findViewById(R.id.btnPresetDeepSafe)
        btnPresetFullReset = findViewById(R.id.btnPresetFullReset)

        rgProfile = findViewById(R.id.rgProfile)
        rbProfileAuto = findViewById(R.id.rbProfileAuto)
        rbProfileZontes = findViewById(R.id.rbProfileZontes)
        rbProfileZontesHd = findViewById(R.id.rbProfileZontesHd)
        rbProfileCfmotoLand = findViewById(R.id.rbProfileCfmotoLand)
        rbProfileCfmotoPort = findViewById(R.id.rbProfileCfmotoPort)

        rgFitMode = findViewById(R.id.rgFitMode)
        rbFitFill = findViewById(R.id.rbFitFill)
        rbFitLetterbox = findViewById(R.id.rbFitLetterbox)
        rbFitStretch = findViewById(R.id.rbFitStretch)

        tabDashboard = findViewById(R.id.tabDashboard)
        tabScreen = findViewById(R.id.tabScreen)
        tabTft = findViewById(R.id.tabTft)
        tabSettings = findViewById(R.id.tabSettings)

        ivTabDashIcon = findViewById(R.id.ivTabDashIcon)
        tvTabDashText = findViewById(R.id.tvTabDashText)
        ivTabScreenIcon = findViewById(R.id.ivTabScreenIcon)
        tvTabScreenText = findViewById(R.id.tvTabScreenText)
        ivTabTftIcon = findViewById(R.id.ivTabTftIcon)
        tvTabTftText = findViewById(R.id.tvTabTftText)
        ivTabSettingsIcon = findViewById(R.id.ivTabSettingsIcon)
        tvTabSettingsText = findViewById(R.id.tvTabSettingsText)
    }

    private fun setupListeners() {
        btnStart.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            startAutoProjection("กำลังเริ่มการเชื่อมต่อ Android Auto...")
        }
        btnFullAaStart?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            startAutoProjection("กำลังเริ่มการเชื่อมต่อ Android Auto...")
        }

        val stopAction = View.OnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            BikeAutoWatcher.onManualStop(this)
            val intent = Intent(this, SessionService::class.java).apply {
                action = SessionService.ACTION_STOP
            }
            startService(intent)
            updateFpsAndPlaceholderUi(0)
            Toast.makeText(this, "หยุดการเชื่อมต่อแล้ว (พร้อมต่อใหม่ได้ทันที)", Toast.LENGTH_SHORT).show()
        }
        btnStop.setOnClickListener(stopAction)
        btnQuickStop.setOnClickListener(stopAction)

        // Tap-to-Reveal / Hide Privacy Mask on Saved Bike Card (or open Wi-Fi settings when waiting/reconnecting)
        layoutSavedBikeBar.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (ConnectionState.phase == Phase.WAITING_FOR_BIKE || ConnectionState.phase == Phase.RECONNECTING) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI))
                    } else {
                        startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    }
                } catch (_: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                }
            } else {
                val nextMasked = !AutoConnectPrefs.isPrivacyMasked(this)
                AutoConnectPrefs.setPrivacyMasked(this, nextMasked)
                refreshSavedBikeInfo()
                updateConnectionUI(ConnectionState.phase, ConnectionState.detail)
            }
        }

        btnScanQr.setOnClickListener {
            val intent = Intent(this, QrScanActivity::class.java)
            qrScannerLauncher.launch(intent)
        }

        btnManualWifi.setOnClickListener {
            showManualWifiDialog()
        }

        btnTogglePreview.setOnClickListener {
            isPreviewVisible = !isPreviewVisible
            layoutAaViewportContainer.visibility = if (isPreviewVisible) View.VISIBLE else View.GONE
            btnTogglePreview.text = if (isPreviewVisible) "ซ่อนจอสด" else "แสดงจอสด"
            if (isPreviewVisible) {
                syncVisiblePreviewSurface()
            } else if (activeTabIndex == 0) {
                AaVideoBridge.unbindPreview(dashPreviewSurface)
                mainScrollView.scrollTo(0, 0)
            }
        }

        btnOpenStickCockpit.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            selectTab(1)
        }

        btnCheckServer.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            checkServerPort(fromUserClick = true)
        }
        btnOpenAaSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            openAndroidAutoSettings()
        }
        tvServerStatus.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            openAndroidAutoSettings()
        }

        // Quick AA Buttons (wired identically on both Tab 0 & Tab 1)
        val sendMaps = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendAaNavigation()
        }
        val sendMic = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendAaAssistant()
        }
        val sendHome = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendAaHome()
        }
        val sendBack = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendAaBack()
        }

        btnAaMaps.setOnClickListener(sendMaps)
        btnAaMic.setOnClickListener(sendMic)
        btnAaHome.setOnClickListener(sendHome)
        btnAaBack.setOnClickListener(sendBack)

        btnFullAaMaps.setOnClickListener(sendMaps)
        btnFullAaMic.setOnClickListener(sendMic)
        btnFullAaHome.setOnClickListener(sendHome)
        btnFullAaBack.setOnClickListener(sendBack)

        // Dock Navigation
        tabDashboard.setOnClickListener { selectTab(0) }
        tabScreen.setOnClickListener { selectTab(1) }
        tabTft.setOnClickListener {
            updateTftStatusText()
            selectTab(2)
        }
        tabSettings.setOnClickListener {
            refreshCalibrationUiReadouts()
            selectTab(3)
        }

        setupSettingsListeners()
    }

    private fun setupSettingsListeners() {
        // 0. Auto-Connect Toggle
        btnToggleAutoConnect.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val next = !AutoConnectPrefs.isAutoConnectEnabled(this)
            AutoConnectPrefs.setAutoConnectEnabled(this, next)
            BikeAutoWatcher.refreshConfig(this)
            refreshAutoConnectButtonUi()
            refreshSavedBikeInfo()
        }

        // 1. Manual AA Resolution Override
        rgManualResolution.setOnCheckedChangeListener { _, checkedId ->
            if (suppressSettingsCallbacks) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rbRes800x480 -> ResolutionMode.LANDSCAPE_SD
                R.id.rbRes1280x720 -> ResolutionMode.LANDSCAPE_HD
                else -> ResolutionMode.AUTO
            }
            VideoPrefs.setResolution(this, mode)
            BikeProfileHolder.aaVideoOverride = VideoPrefs.resolutionOverride(this, BikeProfileHolder.active)
            refreshCalibrationUiReadouts()
            updateTftStatusText()
            Toast.makeText(this, "บันทึกความละเอียดแล้ว (จะมีผลเมื่อเริ่มเชื่อมต่อรอบถัดไป)", Toast.LENGTH_SHORT).show()
        }

        // 2. Manual DPI Icon/Navbar Zoom (100 - 220 DPI, step 5)
        btnDpiMinus.setOnClickListener { adjustManualDpi(-5) }
        btnDpiPlus.setOnClickListener { adjustManualDpi(+5) }
        btnDpiReset.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            VideoPrefs.setDpiOverride(this, null)
            refreshCalibrationUiReadouts()
            updateTftStatusText()
            Toast.makeText(this, "รีเซ็ตขนาดไอคอนเป็นค่าแนะนำตามรุ่นรถแล้ว", Toast.LENGTH_SHORT).show()
        }

        // 3. Live Overall Viewport Scale (70% - 100%, step 2%)
        btnScaleMinus.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val nextScale = (ScreenMargins.scalePercent - 2).coerceIn(ScreenMargins.MIN_SCALE, ScreenMargins.MAX_SCALE)
            ScreenMargins.set(this, scalePct = nextScale)
            refreshCalibrationUiReadouts()
            updateTftStatusText()
        }
        btnScalePlus.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val nextScale = (ScreenMargins.scalePercent + 2).coerceIn(ScreenMargins.MIN_SCALE, ScreenMargins.MAX_SCALE)
            ScreenMargins.set(this, scalePct = nextScale)
            refreshCalibrationUiReadouts()
            updateTftStatusText()
        }

        // 4. Live 4-Edge Zontes Template Safe-Area Margins (step 4px)
        val step = 4
        btnTopMinus.setOnClickListener { adjustEdgeMargin(dTop = -step) }
        btnTopPlus.setOnClickListener { adjustEdgeMargin(dTop = +step) }
        btnBottomMinus.setOnClickListener { adjustEdgeMargin(dBottom = -step) }
        btnBottomPlus.setOnClickListener { adjustEdgeMargin(dBottom = +step) }
        btnLeftMinus.setOnClickListener { adjustEdgeMargin(dLeft = -step) }
        btnLeftPlus.setOnClickListener { adjustEdgeMargin(dLeft = +step) }
        btnRightMinus.setOnClickListener { adjustEdgeMargin(dRight = -step) }
        btnRightPlus.setOnClickListener { adjustEdgeMargin(dRight = +step) }

        btnPresetZontesSafe.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applySafeAreaPreset(ProjectionHarness.SafeAreaPreset.ZONTES_TEMPLATE_SAFE, "ใช้ค่าหลบกรอบ Template หน้าจอ Zontes แล้ว")
        }
        btnPresetDeepSafe.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applySafeAreaPreset(ProjectionHarness.SafeAreaPreset.ZONTES_DEEP_FRAME, "ใช้ค่าหลบกรอบลึกพิเศษแล้ว")
        }
        btnPresetFullReset.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applySafeAreaPreset(ProjectionHarness.SafeAreaPreset.FULL_PANEL, "รีเซ็ตขนาดเต็มจอ 100% แล้ว")
        }

        // 5. Bike Profile Override
        rgProfile.setOnCheckedChangeListener { _, checkedId ->
            if (suppressSettingsCallbacks) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.rbProfileZontes -> {
                    ProfilePrefs.set(this, ProfileOverride.ZONTES_5INCH)
                    BikeProfileHolder.profileOverride = ProfileOverride.ZONTES_5INCH
                    BikeProfileHolder.active = ZontesProfile
                }
                R.id.rbProfileZontesHd -> {
                    ProfilePrefs.set(this, ProfileOverride.ZONTES_HD)
                    BikeProfileHolder.profileOverride = ProfileOverride.ZONTES_HD
                    BikeProfileHolder.active = ZontesHdProfile
                }
                R.id.rbProfileCfmotoLand -> {
                    ProfilePrefs.set(this, ProfileOverride.CFDL26_LAND)
                    BikeProfileHolder.profileOverride = ProfileOverride.CFDL26_LAND
                    BikeProfileHolder.active = Cfdl26LandscapeProfile
                }
                R.id.rbProfileCfmotoPort -> {
                    ProfilePrefs.set(this, ProfileOverride.CFDL26_PORT)
                    BikeProfileHolder.profileOverride = ProfileOverride.CFDL26_PORT
                    BikeProfileHolder.active = Cfdl26PortraitProfile
                }
                else -> {
                    ProfilePrefs.set(this, ProfileOverride.AUTO)
                    BikeProfileHolder.profileOverride = ProfileOverride.AUTO
                    val savedQr = BikeMemory.selected(this)?.qr
                    BikeProfileHolder.active = BikeProfiles.selectByQr(savedQr, this)
                }
            }
            BikeProfileHolder.aaVideoOverride = VideoPrefs.resolutionOverride(this, BikeProfileHolder.active)
            refreshCalibrationUiReadouts()
            updateTftStatusText()
        }

        // 6. Live Fit Mode Update
        rgFitMode.setOnCheckedChangeListener { _, checkedId ->
            if (suppressSettingsCallbacks) return@setOnCheckedChangeListener
            val fit = when (checkedId) {
                R.id.rbFitLetterbox -> ScreenFit.FIT
                R.id.rbFitStretch -> ScreenFit.STRETCH
                else -> ScreenFit.FILL
            }
            VideoPrefs.setFit(this, fit)
            AaVideoBridge.pipeline?.refreshFitMode(fit)
        }
    }

    private fun adjustManualDpi(delta: Int) {
        val currentEffective = VideoPrefs.dpiOverride(this) ?: BikeProfileHolder.active.aaVideo.dpi
        val next = ProjectionHarness.clampManualDpi(currentEffective + delta)
        VideoPrefs.setDpiOverride(this, next)
        btnDpiPlus.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        refreshCalibrationUiReadouts()
        updateTftStatusText()
    }

    private fun adjustEdgeMargin(dTop: Int = 0, dBottom: Int = 0, dLeft: Int = 0, dRight: Int = 0) {
        ScreenMargins.set(
            context = this,
            t = (ScreenMargins.top + dTop).coerceIn(0, ScreenMargins.MAX),
            b = (ScreenMargins.bottom + dBottom).coerceIn(0, ScreenMargins.MAX),
            l = (ScreenMargins.left + dLeft).coerceIn(0, ScreenMargins.MAX),
            r = (ScreenMargins.right + dRight).coerceIn(0, ScreenMargins.MAX),
        )
        btnTopPlus.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        refreshCalibrationUiReadouts()
        updateTftStatusText()
    }

    private fun applySafeAreaPreset(preset: ProjectionHarness.SafeAreaPreset, toast: String) {
        if (preset == ProjectionHarness.SafeAreaPreset.FULL_PANEL) {
            ScreenMargins.reset(this)
        } else {
            val cfg = ProjectionHarness.presetConfig(preset)
            ScreenMargins.set(
                context = this,
                t = cfg.topPx,
                b = cfg.bottomPx,
                l = cfg.leftPx,
                r = cfg.rightPx,
                scalePct = cfg.scalePercent,
            )
        }
        refreshCalibrationUiReadouts()
        updateTftStatusText()
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    /**
     * Binds both Tab 1 ([svAaPreview]) and Tab 2 ([svAaScreenFull]) as GPU-composited [TextureView]s
     * through [AaVideoBridge] so:
     *  - Scrolling happens inside Android's RenderThread at 120Hz with zero SurfaceView punch-hole stalls.
     *  - Liveview works even when [SessionService] starts AFTER `onSurfaceTextureAvailable`.
     *  - Switching between Tab 1 (`แดชบอร์ด`) and Tab 2 (`จอ AA`) seamlessly hands over the live GL
     *    preview surface without ever showing a black screen.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewSurfaces() {
        svAaPreview.isOpaque = true
        svAaScreenFull.isOpaque = true
        svAaPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                dashPreviewSurface?.release()
                val surface = Surface(st)
                dashPreviewSurface = surface
                if (activeTabIndex == 0 && isPreviewVisible) {
                    AaVideoBridge.bindPreview(surface, width, height)
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                val surface = dashPreviewSurface ?: return
                if (activeTabIndex == 0 && isPreviewVisible) {
                    AaVideoBridge.bindPreview(surface, width, height)
                }
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                dashPreviewSurface?.let {
                    AaVideoBridge.unbindPreview(it)
                    it.release()
                }
                dashPreviewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }

        svAaPreview.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != oldRight - oldLeft || h != oldBottom - oldTop) && activeTabIndex == 0 && isPreviewVisible) {
                val st = svAaPreview.surfaceTexture ?: return@addOnLayoutChangeListener
                var surface = dashPreviewSurface
                if (surface == null || !surface.isValid) {
                    surface?.release()
                    surface = Surface(st)
                    dashPreviewSurface = surface
                }
                AaVideoBridge.bindPreview(surface, w, h)
            }
        }

        svAaScreenFull.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                fullPreviewSurface?.release()
                val surface = Surface(st)
                fullPreviewSurface = surface
                if (activeTabIndex == 1) {
                    AaVideoBridge.bindPreview(surface, width, height)
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                val surface = fullPreviewSurface ?: return
                if (activeTabIndex == 1) {
                    AaVideoBridge.bindPreview(surface, width, height)
                }
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                fullPreviewSurface?.let {
                    AaVideoBridge.unbindPreview(it)
                    it.release()
                }
                fullPreviewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }

        svAaScreenFull.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != oldRight - oldLeft || h != oldBottom - oldTop) && activeTabIndex == 1) {
                val st = svAaScreenFull.surfaceTexture ?: return@addOnLayoutChangeListener
                var surface = fullPreviewSurface
                if (surface == null || !surface.isValid) {
                    surface?.release()
                    surface = Surface(st)
                    fullPreviewSurface = surface
                }
                AaVideoBridge.bindPreview(surface, w, h)
            }
        }

        val touchForwarder = View.OnTouchListener { v, event ->
            forwardPreviewTouch(v, event)
        }
        svAaPreview.setOnTouchListener(touchForwarder)
        svAaScreenFull.setOnTouchListener(touchForwarder)
    }

    private fun syncVisiblePreviewSurface() {
        when (activeTabIndex) {
            0 -> {
                if (isPreviewVisible && svAaPreview.isAvailable) {
                    val st = svAaPreview.surfaceTexture ?: return
                    var surface = dashPreviewSurface
                    if (surface == null || !surface.isValid) {
                        surface?.release()
                        surface = Surface(st)
                        dashPreviewSurface = surface
                    }
                    val w = svAaPreview.width.takeIf { it > 0 } ?: 640
                    val h = svAaPreview.height.takeIf { it > 0 } ?: 360
                    AaVideoBridge.bindPreview(surface, w, h)
                }
            }
            1 -> {
                if (svAaScreenFull.isAvailable) {
                    val st = svAaScreenFull.surfaceTexture ?: return
                    var surface = fullPreviewSurface
                    if (surface == null || !surface.isValid) {
                        surface?.release()
                        surface = Surface(st)
                        fullPreviewSurface = surface
                    }
                    val w = svAaScreenFull.width.takeIf { it > 0 } ?: 640
                    val h = svAaScreenFull.height.takeIf { it > 0 } ?: 360
                    AaVideoBridge.bindPreview(surface, w, h)
                }
            }
        }
    }

    private fun forwardPreviewTouch(view: View, event: MotionEvent): Boolean {
        val isStreamingVideo = AaVideoBridge.aaDecoding || AaVideoBridge.currentFps > 0 || AaVideoBridge.aaSessionLive
        if (!isStreamingVideo) return false
        val sink = AaVideoBridge.previewTouchSink ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.parent?.requestDisallowInterceptTouchEvent(false)
        }

        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            for (i in 0 until event.pointerCount) {
                val pid = event.getPointerId(i)
                val mapped = mapViewPointToAaSource(view, event.getX(i), event.getY(i)) ?: continue
                sink.invoke(AaInput.ACTION_MOVE, pid, mapped.first, mapped.second)
            }
            return true
        }

        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> AaInput.ACTION_DOWN
            else -> AaInput.ACTION_UP
        }
        val idx = event.actionIndex
        val pid = event.getPointerId(idx)
        val mapped = mapViewPointToAaSource(view, event.getX(idx), event.getY(idx)) ?: return true
        sink.invoke(action, pid, mapped.first, mapped.second)
        return true
    }

    private fun mapViewPointToAaSource(view: View, vx: Float, vy: Float): Pair<Int, Int>? {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw <= 0f || vh <= 0f) return null

        // 1. Direct GPU compositor mapping (matches letterbox and crop on phone screen exactly)
        val fromPipeline = AaVideoBridge.pipeline?.mapPreviewTouchToSource(vx.toInt(), vy.toInt())
        if (fromPipeline != null) {
            return fromPipeline
        }

        // 2. Geometry fallback
        val spec = BikeProfileHolder.aaVideo
        val margins = BikeProfileHolder.aaContentMargins
        val usableW = (spec.width - margins.marginW).coerceIn(16, spec.width)
        val usableH = (spec.height - margins.marginH).coerceIn(16, spec.height)

        val contentAspect = usableW.toFloat() / usableH.toFloat()
        val viewAspect = vw / vh

        val drawW: Float
        val drawH: Float
        if (contentAspect < viewAspect) {
            // Content is narrower/taller than view -> black bars on left/right
            drawH = vh
            drawW = vh * contentAspect
        } else {
            // Content is wider/shorter than view -> black bars on top/bottom
            drawW = vw
            drawH = vw / contentAspect
        }
        val drawX = (vw - drawW) * 0.5f
        val drawY = (vh - drawH) * 0.5f

        // Allow 36px edge tolerance so corner icons (Home/Mic) and bottom dock are effortless to tap
        val tolerance = 36f
        if (vx < drawX - tolerance || vx > drawX + drawW + tolerance ||
            vy < drawY - tolerance || vy > drawY + drawH + tolerance) {
            return null
        }

        val rx = (vx - drawX).coerceIn(0f, drawW)
        val ry = (vy - drawY).coerceIn(0f, drawH)

        val normX = if (drawW > 0f) rx / drawW else 0.5f
        val normY = if (drawH > 0f) ry / drawH else 0.5f

        val offsetX = margins.marginW * 0.5f
        val offsetY = margins.marginH * 0.5f
        val sx = (offsetX + normX * usableW).toInt().coerceIn(0, spec.width - 1)
        val sy = (offsetY + normY * usableH).toInt().coerceIn(0, spec.height - 1)
        return sx to sy
    }

    /**
     * Dedicated Virtual Stick, Rotary Knob & Glide Trackpad Controllers specifically engineered
     * for controlling Android Auto when riding with a non-touch Zontes TFT screen.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDedicatedControllers() {
        btnModeJoystick.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            layoutJoystickPanel.visibility = View.VISIBLE
            frameTrackpadSurface.visibility = View.GONE
            btnModeJoystick.setBackgroundResource(R.drawable.btn_liquid_sapphire)
            btnModeJoystick.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            btnModeTrackpad.setBackgroundResource(R.drawable.btn_liquid_pill)
            btnModeTrackpad.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        btnModeTrackpad.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            layoutJoystickPanel.visibility = View.GONE
            frameTrackpadSurface.visibility = View.VISIBLE
            btnModeTrackpad.setBackgroundResource(R.drawable.btn_liquid_sapphire)
            btnModeTrackpad.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            btnModeJoystick.setBackgroundResource(R.drawable.btn_liquid_pill)
            btnModeJoystick.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        // Virtual Analog Stick + 5-way D-Pad
        virtualStickAa.onDirection = { keycode ->
            AaVideoBridge.keySink?.invoke(keycode)
        }
        virtualStickAa.onCenterTap = {
            AaVideoBridge.keySink?.invoke(AaInput.KEY_ENTER)
        }

        // Dedicated Rotary Focus & Confirm Buttons
        btnRotaryPrev.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val scroll = AaVideoBridge.scrollSink
            if (scroll != null) scroll.invoke(-1) else sendAaKeyOrToast(AaInput.KEY_LEFT, "โฟกัสก่อนหน้า")
        }
        btnStickConfirm.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendAaKeyOrToast(AaInput.KEY_ENTER, "ตกลง (OK)")
        }
        btnRotaryNext.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val scroll = AaVideoBridge.scrollSink
            if (scroll != null) scroll.invoke(1) else sendAaKeyOrToast(AaInput.KEY_RIGHT, "โฟกัสถัดไป")
        }

        // Precision Touchpad Surface (maps full pad area into AA coded canvas + visual cursor dot)
        frameTrackpadSurface.setOnTouchListener { pad, event ->
            val sink = AaVideoBridge.previewTouchSink
            val pw = pad.width.coerceAtLeast(1)
            val ph = pad.height.coerceAtLeast(1)
            val spec = BikeProfileHolder.aaVideo
            val margins = BikeProfileHolder.aaContentMargins
            val usableW = (spec.width - margins.marginW).coerceIn(16, spec.width)
            val usableH = (spec.height - margins.marginH).coerceIn(16, spec.height)
            val clampedX = event.x.coerceIn(0f, pw.toFloat())
            val clampedY = event.y.coerceIn(0f, ph.toFloat())
            val offsetX = margins.marginW * 0.5f
            val offsetY = margins.marginH * 0.5f
            val sx = (offsetX + (clampedX / pw) * usableW).toInt().coerceIn(0, spec.width - 1)
            val sy = (offsetY + (clampedY / ph) * usableH).toInt().coerceIn(0, spec.height - 1)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pad.parent?.requestDisallowInterceptTouchEvent(true)
                    pad.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    viewTrackpadCursor.visibility = View.VISIBLE
                    viewTrackpadCursor.translationX = (clampedX - viewTrackpadCursor.width * 0.5f).coerceAtLeast(0f)
                    viewTrackpadCursor.translationY = (clampedY - viewTrackpadCursor.height * 0.5f).coerceAtLeast(0f)
                    sink?.invoke(AaInput.ACTION_DOWN, 0, sx, sy)
                }
                MotionEvent.ACTION_MOVE -> {
                    viewTrackpadCursor.translationX = (clampedX - viewTrackpadCursor.width * 0.5f).coerceAtLeast(0f)
                    viewTrackpadCursor.translationY = (clampedY - viewTrackpadCursor.height * 0.5f).coerceAtLeast(0f)
                    sink?.invoke(AaInput.ACTION_MOVE, 0, sx, sy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    viewTrackpadCursor.visibility = View.GONE
                    sink?.invoke(AaInput.ACTION_UP, 0, sx, sy)
                    pad.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
    }

    private fun injectAaTouchTap(x: Int, y: Int) {
        val sink = AaVideoBridge.previewTouchSink ?: return
        sink.invoke(AaInput.ACTION_DOWN, 0, x, y)
        viewTrackpadCursor.postDelayed({
            sink.invoke(AaInput.ACTION_UP, 0, x, y)
        }, 60L)
    }

    private fun sendAaNavigation() {
        val spec = BikeProfileHolder.aaVideo
        val keySink = AaVideoBridge.keySink
        keySink?.invoke(AaInput.KEY_GUIDE)
        keySink?.invoke(AaInput.KEY_EXPLORER)

        // Touch injection on Coolwalk Maps icon (~100px from left, ~28px from bottom)
        val mapX = (spec.width * 0.13f).toInt().coerceIn(80, 140)
        val mapY = spec.height - 28
        injectAaTouchTap(mapX, mapY)
        LogBus.log("[AA-UI] sendAaNavigation: injected touch at ($mapX, $mapY)")
    }

    private fun sendAaAssistant() {
        val spec = BikeProfileHolder.aaVideo
        val keySink = AaVideoBridge.keySink
        keySink?.invoke(AaInput.KEY_ASSISTANT)
        keySink?.invoke(AaInput.KEY_VOICE_ASSIST)
        AaVideoBridge.noteAaAudioActive()

        // Touch injection on Coolwalk Assistant / Mic icon (bottom right: ~36px from right edge)
        val micX = spec.width - 36
        val micY = spec.height - 28
        injectAaTouchTap(micX, micY)
        LogBus.log("[AA-UI] sendAaAssistant: sent KEY_ASSISTANT and injected touch at ($micX, $micY)")
    }

    private fun sendAaHome() {
        val spec = BikeProfileHolder.aaVideo
        val keySink = AaVideoBridge.keySink
        keySink?.invoke(AaInput.KEY_HOME)

        // Touch injection on Coolwalk App Drawer / Home icon
        // Dock is at bottom for landscape: home icon is ~36px from left, ~28px from bottom
        val homeX = (spec.width * 0.05f).toInt().coerceIn(24, 60)
        val homeY = spec.height - 28
        injectAaTouchTap(homeX, homeY)
        LogBus.log("[AA-UI] sendAaHome: sent KEY_HOME and injected touch at ($homeX, $homeY)")
    }

    private fun sendAaBack() {
        val sink = AaVideoBridge.keySink
        if (sink != null) {
            sink.invoke(AaInput.KEY_BACK)
        } else {
            Toast.makeText(this, "ยังไม่ได้เชื่อมต่อ AA (ย้อนกลับ)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendAaKeyOrToast(keycode: Int, label: String) {
        val sink = AaVideoBridge.keySink
        if (sink != null) {
            sink.invoke(keycode)
        } else {
            Toast.makeText(this, "ยังไม่ได้เชื่อมต่อ AA ($label)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTelemetryListeners() {
        ConnectionState.listener = { phase, detail ->
            runOnUiThread {
                updateConnectionUI(phase, detail)
            }
        }
        AaVideoBridge.fpsListener = { fps ->
            runOnUiThread {
                updateFpsAndPlaceholderUi(fps)
            }
        }
        BikeAutoWatcher.statusListener = { _, reasonTh ->
            runOnUiThread {
                latestAutoWatcherReason = reasonTh
                tvAutoConnectState.text = "สถานะ: $reasonTh"
                refreshSavedBikeInfo()
            }
        }
        updateConnectionUI(ConnectionState.phase, ConnectionState.detail)
        updateFpsAndPlaceholderUi(AaVideoBridge.currentFps)
    }

    private fun updateFpsAndPlaceholderUi(fps: Int) {
        val isStreamingVideo = fps > 0 || AaVideoBridge.aaDecoding
        if (isStreamingVideo) {
            val shownFps = if (fps > 0) fps else 30
            val badgeText = "● LIVE ${shownFps}fps"
            val green = ContextCompat.getColor(this, R.color.green_neon)
            tvLiveFpsBadge.text = badgeText
            tvLiveFpsBadge.setTextColor(green)
            tvFullLiveFpsBadge.text = badgeText
            tvFullLiveFpsBadge.setTextColor(green)
            layoutAaPlaceholder.visibility = View.GONE
            layoutFullAaPlaceholder.visibility = View.GONE
        } else {
            val idleText = if (SessionService.isRunning) "● กำลังรอภาพ AA..." else "● รอสัญญาณภาพ"
            val col = ContextCompat.getColor(
                this,
                if (SessionService.isRunning) R.color.amber_accent else R.color.text_secondary
            )
            tvLiveFpsBadge.text = idleText
            tvLiveFpsBadge.setTextColor(col)
            tvFullLiveFpsBadge.text = idleText
            tvFullLiveFpsBadge.setTextColor(col)
            layoutAaPlaceholder.visibility = View.VISIBLE
            layoutFullAaPlaceholder.visibility = View.VISIBLE
        }
    }

    private fun updateConnectionUI(phase: Phase, detail: String) {
        val label = getString(phase.labelRes)
        tvStatusTitle.text = label
        val savedSsid = BikeMemory.selected(this)?.qr?.ssid
        val masked = AutoConnectPrefs.isPrivacyMasked(this)
        val cleanDetail = ProjectionHarness.sanitizeStatusText(detail, savedSsid, masked)
        tvStatusDesc.text = if (cleanDetail.isNotBlank()) {
            cleanDetail
        } else {
            "กดเมนู QR บนจอรถเพื่อเชื่อมอัตโนมัติ หรือกดเริ่มเชื่อมต่อด้านล่าง"
        }

        if (phase == Phase.STREAMING || phase == Phase.AA_VIDEO_LIVE) {
            statusDot.setBackgroundResource(R.drawable.btn_liquid_emerald)
            tvOnlineBadge.text = "● สตรีมสด"
            tvOnlineBadge.setTextColor(ContextCompat.getColor(this, R.color.green_neon))
        } else if (phase.busy) {
            statusDot.setBackgroundResource(R.drawable.btn_liquid_sapphire)
            tvOnlineBadge.text = "● กำลังเชื่อมต่อ"
            tvOnlineBadge.setTextColor(ContextCompat.getColor(this, R.color.cyan_neon))
        } else if (phase == Phase.ERROR) {
            statusDot.setBackgroundResource(R.drawable.btn_liquid_ruby)
            tvOnlineBadge.text = "● พบปัญหา"
            tvOnlineBadge.setTextColor(ContextCompat.getColor(this, R.color.ruby_bright))
        } else {
            statusDot.setBackgroundResource(R.drawable.dock_capsule_bg)
            tvOnlineBadge.text = "● สแตนด์บาย"
            tvOnlineBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun refreshSavedBikeInfo() {
        val bike = BikeMemory.selected(this)
        val masked = AutoConnectPrefs.isPrivacyMasked(this)
        if (bike != null) {
            val qr = bike.qr
            tvSavedBike.text = ProjectionHarness.maskIdentifier(bike.name, masked)
            if (masked) {
                tvSavedWifi.text = latestAutoWatcherReason
                tvPrivacyToggleBadge.text = "กดเพื่อแสดง"
                tvPrivacyToggleBadge.setTextColor(ContextCompat.getColor(this, R.color.cyan_neon))
            } else {
                val ssidFull = qr?.ssid ?: "ไม่ระบุ"
                tvSavedWifi.text = "Wi-Fi SSID: $ssidFull"
                tvPrivacyToggleBadge.text = "ซ่อนข้อมูล"
                tvPrivacyToggleBadge.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
            }
        } else {
            tvSavedBike.text = "ยังไม่มีรถที่จำไว้"
            tvSavedWifi.text = "กดปุ่ม 'สแกน QR รถ' หรือ 'ใส่ Wi-Fi เอง' เพื่อบันทึกครั้งแรก"
            tvPrivacyToggleBadge.text = "สแกน QR"
            tvPrivacyToggleBadge.setTextColor(ContextCompat.getColor(this, R.color.cyan_neon))
        }
    }

    private fun checkServerPort(fromUserClick: Boolean = false) {
        val isLive = AaVideoBridge.aaSessionLive
        val isRunning = SessionService.isRunning
        if (isLive) {
            tvServerStatus.text = "● สตรีม Android Auto กำลังทำงานสด"
            tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.green_neon))
            if (fromUserClick) {
                Toast.makeText(this, "สตรีม Android Auto กำลังทำงานสด", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (isRunning) {
            tvServerStatus.text = "● กำลังเชื่อมต่อ Android Auto / รถ"
            tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.cyan_neon))
            if (fromUserClick) {
                Toast.makeText(this, "ระบบกำลังดำเนินการเชื่อมต่อ...", Toast.LENGTH_SHORT).show()
            }
            return
        }

        tvServerStatus.text = "● กำลังตรวจสถานะเซิร์ฟเวอร์..."
        tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))

        lifecycleScope.launch {
            val isListening = withContext(Dispatchers.IO) {
                isPortListening(5277, 400)
            }
            if (isFinishing || isDestroyed) return@launch

            if (isListening) {
                tvServerStatus.text = "● เซิร์ฟเวอร์เฮดยูนิต: เปิดอยู่ (:5277 พร้อมเชื่อมต่อ)"
                tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green_neon))
                if (fromUserClick) {
                    Toast.makeText(this@MainActivity, "เซิร์ฟเวอร์เฮดยูนิตเปิดอยู่ (:5277) พร้อมทำงาน", Toast.LENGTH_SHORT).show()
                }
            } else {
                tvServerStatus.text = "● เซิร์ฟเวอร์เฮดยูนิต: ปิดอยู่ (แตะเปิดตั้งค่า AA)"
                tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ruby_bright))
                if (fromUserClick) {
                    Toast.makeText(
                        this@MainActivity,
                        "เซิร์ฟเวอร์เฮดยูนิตปิดอยู่! กรุณากดปุ่ม 3 จุดใน Android Auto แล้วเลือก 'Start headunit server'",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun isPortListening(port: Int, timeoutMs: Int = 400): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val bound = try { cm?.boundNetworkForProcess } catch (_: Exception) { null }
        if (bound != null) {
            try { cm?.bindProcessToNetwork(null) } catch (_: Exception) {}
        }
        for (host in arrayOf("127.0.0.1", "localhost", "::1")) {
            try {
                Socket().use { s ->
                    s.reuseAddress = true
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    return true
                }
            } catch (_: Exception) {
                // Try next
            }
        }
        return false
    }

    private fun openAndroidAutoSettings() {
        try {
            val intent = Intent("com.google.android.projection.gearhead.SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:com.google.android.projection.gearhead")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        }
    }

    private fun showManualWifiDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val currentQr = BikeMemory.selected(this)?.qr
        val ssidInput = EditText(this).apply {
            hint = "ชื่อ Wi-Fi (SSID) เช่น A004DC0D30888555"
            setText(currentQr?.ssid ?: "")
            setSingleLine()
        }
        val pwdInput = EditText(this).apply {
            hint = "รหัสผ่าน Wi-Fi (ค่ามาตรฐาน 12345678)"
            setText(currentQr?.pwd ?: "12345678")
            setSingleLine()
        }
        container.addView(ssidInput)
        container.addView(pwdInput)

        AlertDialog.Builder(this)
            .setTitle("ตั้งค่า Wi-Fi หน้าจอรถ")
            .setMessage("ระบุ SSID และรหัสผ่านจากหน้าเมนู QR ของจอรถ:")
            .setView(container)
            .setPositiveButton("บันทึกและเชื่อมต่อ") { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                val pwd = pwdInput.text.toString().trim().ifBlank { "12345678" }
                if (ssid.isNotBlank()) {
                    val raw = "WIFI:S:$ssid;T:WPA;P:$pwd;;"
                    val qr = QrData.parse(raw)
                    if (qr != null) {
                        BikeMemory.save(this, raw, qr)
                        refreshSavedBikeInfo()
                        initSettingsState()
                        startAutoProjection("บันทึกข้อมูลรถแล้ว — เริ่มเชื่อมต่ออัตโนมัติ")
                    }
                }
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun initSettingsState() {
        suppressSettingsCallbacks = true
        try {
            val currentProfile = ProfilePrefs.get(this)
            val savedQr = BikeMemory.selected(this)?.qr
            when (currentProfile) {
                ProfileOverride.ZONTES_5INCH -> {
                    rbProfileZontes.isChecked = true
                    BikeProfileHolder.profileOverride = ProfileOverride.ZONTES_5INCH
                    BikeProfileHolder.active = ZontesProfile
                }
                ProfileOverride.ZONTES_HD -> {
                    rbProfileZontesHd.isChecked = true
                    BikeProfileHolder.profileOverride = ProfileOverride.ZONTES_HD
                    BikeProfileHolder.active = ZontesHdProfile
                }
                ProfileOverride.CFDL26_LAND -> {
                    rbProfileCfmotoLand.isChecked = true
                    BikeProfileHolder.profileOverride = ProfileOverride.CFDL26_LAND
                    BikeProfileHolder.active = Cfdl26LandscapeProfile
                }
                ProfileOverride.CFDL26_PORT -> {
                    rbProfileCfmotoPort.isChecked = true
                    BikeProfileHolder.profileOverride = ProfileOverride.CFDL26_PORT
                    BikeProfileHolder.active = Cfdl26PortraitProfile
                }
                else -> {
                    rbProfileAuto.isChecked = true
                    BikeProfileHolder.profileOverride = ProfileOverride.AUTO
                    BikeProfileHolder.active = BikeProfiles.selectByQr(savedQr, this)
                }
            }

            BikeProfileHolder.aaDpiOverride = VideoPrefs.dpiOverride(this)
            BikeProfileHolder.aaVideoOverride = VideoPrefs.resolutionOverride(this, BikeProfileHolder.active)

            when (VideoPrefs.resolution(this)) {
                ResolutionMode.LANDSCAPE_SD -> rbRes800x480.isChecked = true
                ResolutionMode.LANDSCAPE_HD -> rbRes1280x720.isChecked = true
                else -> rbResAuto.isChecked = true
            }

            when (VideoPrefs.fit(this)) {
                ScreenFit.FIT -> rbFitLetterbox.isChecked = true
                ScreenFit.STRETCH -> rbFitStretch.isChecked = true
                ScreenFit.FILL -> rbFitFill.isChecked = true
            }

            refreshAutoConnectButtonUi()
            refreshCalibrationUiReadouts()
            updateTftStatusText()
        } finally {
            suppressSettingsCallbacks = false
        }
    }

    private fun refreshAutoConnectButtonUi() {
        val enabled = AutoConnectPrefs.isAutoConnectEnabled(this)
        if (enabled) {
            btnToggleAutoConnect.text = "เปิดเชื่อมต่ออัตโนมัติอยู่ (แตะเพื่อปิด)"
            btnToggleAutoConnect.setBackgroundResource(R.drawable.btn_liquid_emerald)
            btnToggleAutoConnect.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            btnToggleAutoConnect.text = "ปิดเชื่อมต่ออัตโนมัติไว้ (แตะเพื่อเปิด)"
            btnToggleAutoConnect.setBackgroundResource(R.drawable.btn_liquid_pill)
            btnToggleAutoConnect.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun refreshCalibrationUiReadouts() {
        val manualDpi = VideoPrefs.dpiOverride(this)
        val defaultDpi = BikeProfileHolder.active.aaVideo.dpi
        tvDpiValue.text = if (manualDpi != null) {
            "แมนนวล: $manualDpi DPI"
        } else {
            "ค่าแนะนำ ($defaultDpi DPI)"
        }

        val scale = ScreenMargins.scalePercent
        tvScaleValue.text = if (scale == 100) "100% (เต็มสัดส่วน)" else "ย่อภาพ: $scale%"
        tvMarginTop.text = "ขอบบน: ${ScreenMargins.top} px"
        tvMarginBottom.text = "ขอบล่าง: ${ScreenMargins.bottom} px"
        tvMarginLeft.text = "ขอบซ้าย: ${ScreenMargins.left} px"
        tvMarginRight.text = "ขอบขวา: ${ScreenMargins.right} px"
    }

    private fun updateTftStatusText() {
        val p = BikeProfileHolder.active
        val v = BikeProfileHolder.aaVideo
        val panel = p.panelSize
        val panelStr = if (panel != null) "${panel.first}×${panel.second}" else "${v.width}×${v.height}"
        val autoStr = if (AutoConnectPrefs.isAutoConnectEnabled(this)) "เปิด (รอหน้าเมนู QR)" else "ปิด"
        tvTftStatus.text =
            "โปรไฟล์ที่ใช้งาน: ${p.name}\n" +
            "ความละเอียดหน้าปัดรถ: $panelStr\n" +
            "สัญญาณ Android Auto: ${v.width}×${v.height} @ ${v.dpi} DPI\n" +
            "สเกลและการหลบขอบ: ${ScreenMargins.scalePercent}% " +
            "(บน ${ScreenMargins.top} / ล่าง ${ScreenMargins.bottom} / ซ้าย ${ScreenMargins.left} / ขวา ${ScreenMargins.right} px)\n" +
            "โหมด BT + QR Auto-Connect: $autoStr"
    }

    private fun selectTab(index: Int) {
        activeTabIndex = index
        val panes = listOf(paneDashboard, paneScreen, paneTft, paneSettings)
        panes.forEachIndexed { i, pane ->
            pane.visibility = if (i == index) View.VISIBLE else View.GONE
        }

        val tabs = listOf(tabDashboard, tabScreen, tabTft, tabSettings)
        val icons = listOf(ivTabDashIcon, ivTabScreenIcon, ivTabTftIcon, ivTabSettingsIcon)
        val texts = listOf(tvTabDashText, tvTabScreenText, tvTabTftText, tvTabSettingsText)

        val activeColor = ContextCompat.getColor(this, R.color.cyan_neon)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        tabs.forEachIndexed { i, tab ->
            if (i == index) {
                tab.setBackgroundResource(R.drawable.dock_pill_active)
                icons[i].setColorFilter(activeColor)
                texts[i].setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            } else {
                tab.background = null
                icons[i].setColorFilter(inactiveColor)
                texts[i].setTextColor(inactiveColor)
            }
        }

        // Reset scroll to top cleanly when switching tabs & hand off live OpenGL preview surface
        mainScrollView.scrollTo(0, 0)
        mainScrollView.post { syncVisiblePreviewSurface() }
    }
}
