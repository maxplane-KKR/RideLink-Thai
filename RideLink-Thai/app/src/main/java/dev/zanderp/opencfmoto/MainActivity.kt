package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import dev.zanderp.opencfmoto.aa.AaInput
import dev.zanderp.opencfmoto.aa.AaSelfMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // Status, Header & Privacy-Masked Saved Bike Bar
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView
    private lateinit var layoutSavedBikeBar: LinearLayout
    private lateinit var tvSavedBike: TextView
    private lateinit var tvSavedWifi: TextView
    private lateinit var tvPrivacyToggleBadge: TextView
    private lateinit var tvOnlineBadge: TextView
    private lateinit var tvHeadunitHeaderStatus: TextView
    private lateinit var statusDot: View

    // Dashboard Action Buttons
    private lateinit var btnStart: Button
    private lateinit var btnScanQr: Button

    // Dashboard Live Viewport
    private lateinit var mainScrollView: FastScrollView
    private lateinit var layoutAaViewportContainer: LinearLayout
    private lateinit var tvLiveFpsBadge: TextView
    private lateinit var frameAaPreview: FrameLayout
    private lateinit var svAaPreview: TextureView
    private lateinit var layoutAaPlaceholder: LinearLayout
    private lateinit var btnAaMaps: Button
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
    private lateinit var btnFullAaHome: Button
    private lateinit var btnFullAaBack: Button
    private var btnFullAaStart: Button? = null

    // Panes
    private lateinit var paneDashboard: LinearLayout
    private lateinit var paneScreen: LinearLayout
    private lateinit var paneTft: LinearLayout
    private lateinit var tftStudio: LiveDashboardStudio
    private lateinit var paneSettings: LinearLayout

    // Tab 3 & Tab 4 (TFT Info + Settings + Manual Calibration)
    private lateinit var tvTftStatus: TextView
    private lateinit var tvAppTagline: TextView
    private lateinit var tvTftSyncStatus: TextView
    private lateinit var tvTftSyncHint: TextView
    private lateinit var btnTftSyncStop: Button
    private lateinit var tftSyncActionBar: View
    private lateinit var btnTftToggleStudio: Button
    private lateinit var btnTftToggleDetails: Button
    private lateinit var tftStudioContainer: FrameLayout
    private lateinit var tftDetailsContainer: View

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

    private lateinit var btnTouchYMinus: Button
    private lateinit var tvTouchYOffset: TextView
    private lateinit var btnTouchYPlus: Button
    private lateinit var btnTouchYReset: Button

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
    private var headunitSetupComplete = false
    private var headunitSettingsVisited = false
    private var initialPermissionsRequested = false
    private var headunitUiResumed = false
    private var voiceAfterPermission = false
    private var initialHeadunitCheckRequested = false
    private var headunitSetupDialog: AlertDialog? = null

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkServerPort()
    }

    private val voicePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.aa_voice_permission_denied, Toast.LENGTH_SHORT).show()
        } else if (activeTabIndex == 1) {
            if (headunitUiResumed) sendAaAssistant() else voiceAfterPermission = true
        }
    }

    private val obdPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) chooseLiveObd()
        else Toast.makeText(this, R.string.ridescreen_obd_permission, Toast.LENGTH_SHORT).show()
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshSavedBikeInfo()
            initSettingsState()
            startAutoProjection(getString(R.string.scan_auto_start))
        }
    }

    private val tftFactoryBackup by lazy { TftFactoryBackup(noBackupFilesDir) }
    private val tftFactoryDownloads by lazy { TftFactoryDownloads(applicationContext, tftFactoryBackup) }
    private var preparingTftFactoryCopy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        headunitSetupComplete = savedInstanceState?.getBoolean("headunit_setup_complete") == true
        headunitSettingsVisited = savedInstanceState?.getBoolean("headunit_settings_visited") == true
        initialPermissionsRequested = savedInstanceState?.getBoolean("initial_permissions_requested") == true
        ScreenMargins.load(this)
        TouchPrefs.init(this)
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
        if (savedInstanceState == null) {
            handleDebugIntent(intent)
        } else {
            setTftStudioOpen(savedInstanceState.getBoolean("tft_studio_open"))
            setTftDetailsOpen(savedInstanceState.getBoolean("tft_details_open"))
            selectTab(savedInstanceState.getInt("active_tab"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("headunit_setup_complete", headunitSetupComplete)
        outState.putBoolean("headunit_settings_visited", headunitSettingsVisited)
        outState.putBoolean("initial_permissions_requested", initialPermissionsRequested)
        outState.putInt("active_tab", activeTabIndex)
        outState.putBoolean("tft_studio_open", tftStudioContainer.visibility == View.VISIBLE)
        outState.putBoolean("tft_details_open", tftDetailsContainer.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    private fun changeAppLanguage() {
        val next = if (resources.configuration.locales[0].language == "en") "th" else "en"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
    }

    private fun openGearWebsite() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.gear_url)))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.gear_browser_unavailable, Toast.LENGTH_SHORT).show()
        }
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
            if (tab == 3) refreshCalibrationUiReadouts()
            selectTab(tab)
        }
        val preview = intent.getIntExtra("debug_preview", -1)
        if (preview == 0 || preview == 1) {
            val want = preview == 1
            if (isPreviewVisible != want) {
                isPreviewVisible = want
                layoutAaViewportContainer.visibility = if (isPreviewVisible) View.VISIBLE else View.GONE
                syncVisiblePreviewSurface()
            }
        }
        val scrollY = intent.getIntExtra("debug_scroll", -1)
        if (scrollY >= 0) {
            mainScrollView.post { mainScrollView.scrollTo(0, scrollY) }
        }
    }

    override fun onStart() {
        super.onStart()
        SessionService.onActivityStarted()
        LiveObd.setAppVisible(this, true)
    }

    override fun onStop() {
        LiveObd.setAppVisible(this, false)
        SessionService.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        AaSelfMode.foregroundActivityProvider = {
            if (!isFinishing && !isDestroyed) this else null
        }
        headunitUiResumed = true
        if (voiceAfterPermission) {
            voiceAfterPermission = false
            sendAaAssistant()
        }
        val restartStandby = SessionService.consumeStandbyRestart()
        if (!initialHeadunitCheckRequested || restartStandby) {
            initialHeadunitCheckRequested = true
            requestHeadunitCheck()
        }
        if (headunitSetupComplete) activateProjectionControls()
        checkServerPort()
        syncVisiblePreviewSurface()
        updatePrimaryToggleUi(ConnectionState.phase)
        updateFpsAndPlaceholderUi(AaVideoBridge.currentFps)
    }

    override fun onPause() {
        headunitUiResumed = false
        AaVideoBridge.voiceMicrophone?.stop()
        if (AaSelfMode.foregroundActivityProvider?.invoke() === this) {
            AaSelfMode.foregroundActivityProvider = null
        }
        super.onPause()
    }

    override fun onDestroy() {
        headunitSetupDialog?.dismiss()
        AaVideoBridge.headunitStatusListener = null
        AaVideoBridge.fpsListener = null
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
        )
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

    private fun activateProjectionControls() {
        if (!initialPermissionsRequested) {
            initialPermissionsRequested = true
            requestNeededPermissions()
        }
    }

    private fun requestHeadunitCheck() {
        if (SessionService.isRunning) return
        val intent = Intent(this, SessionService::class.java).setAction(SessionService.ACTION_PREPARE_HEADUNIT)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun showRequiredHeadunitSetup() {
        if (headunitSetupDialog?.isShowing == true || isFinishing || isDestroyed) return
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.headunit_setup_required_title)
            .setMessage(R.string.headunit_setup_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.headunit_open_settings, null)
            .setNegativeButton(R.string.headunit_setup_continue, null)
            .create()
        headunitSetupDialog = dialog
        dialog.setOnDismissListener {
            if (headunitSetupDialog === dialog) headunitSetupDialog = null
        }
        dialog.setOnShowListener {
            val continueButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            continueButton.isEnabled = headunitSettingsVisited
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (openAndroidAutoSettings()) {
                    headunitSettingsVisited = true
                    continueButton.isEnabled = true
                } else {
                    Toast.makeText(this, R.string.headunit_settings_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            continueButton.setOnClickListener {
                if (!headunitSettingsVisited) return@setOnClickListener
                headunitSetupComplete = true
                dialog.dismiss()
                activateProjectionControls()
                checkServerPort()
            }
        }
        dialog.show()
    }

    /** Starts [SessionService], which verifies the head unit before joining the bike network. */
    private fun startAutoProjection(toastMsg: String) {
        val requestedAt = SystemClock.elapsedRealtime()
        if (!headunitSetupComplete) {
            checkServerPort()
            if (!headunitSetupComplete) return
        }
        updateTftStatusText()

        val intent = Intent(this, SessionService::class.java).apply {
            action = SessionService.ACTION_START
            putExtra(SessionService.EXTRA_CONNECT_REQUESTED_AT, requestedAt)
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
        tvHeadunitHeaderStatus = findViewById<TextView>(R.id.tvHeadunitHeaderStatus).apply {
            setOnClickListener { checkServerPort(fromUserClick = true) }
        }
        statusDot = findViewById(R.id.statusDot)

        btnStart = findViewById(R.id.btnStart)
        btnScanQr = findViewById(R.id.btnScanQr)

        mainScrollView = findViewById(R.id.mainScrollView)
        layoutAaViewportContainer = findViewById(R.id.layoutAaViewportContainer)
        tvLiveFpsBadge = findViewById(R.id.tvLiveFpsBadge)
        frameAaPreview = findViewById(R.id.frameAaPreview)
        svAaPreview = findViewById(R.id.svAaPreview)
        layoutAaPlaceholder = findViewById(R.id.layoutAaPlaceholder)
        btnAaMaps = findViewById(R.id.btnAaMaps)
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
        btnFullAaHome = findViewById(R.id.btnFullAaHome)
        btnFullAaBack = findViewById(R.id.btnFullAaBack)
        btnFullAaStart = findViewById(R.id.btnFullAaStart)

        paneDashboard = findViewById(R.id.paneDashboard)
        paneScreen = findViewById(R.id.paneScreen)
        paneTft = findViewById(R.id.paneTft)
        paneSettings = findViewById(R.id.paneSettings)
        findViewById<Button>(R.id.btnAppLanguage).setOnClickListener { changeAppLanguage() }
        findViewById<Button>(R.id.btnOpenGear).setOnClickListener { openGearWebsite() }

        // Tab 3 & Tab 4 Views
        tvTftStatus = findViewById(R.id.tvTftStatus)
        tvAppTagline = findViewById(R.id.tvAppTagline)
        tvTftSyncStatus = findViewById(R.id.tvTftSyncStatus)
        tvTftSyncHint = findViewById(R.id.tvTftSyncHint)
        btnTftSyncStop = findViewById(R.id.btnTftSyncStop)
        tftSyncActionBar = findViewById(R.id.tftSyncActionBar)
        btnTftToggleStudio = findViewById(R.id.btnTftToggleStudio)
        btnTftToggleDetails = findViewById(R.id.btnTftToggleDetails)
        tftStudioContainer = findViewById(R.id.tftStudioContainer)
        tftDetailsContainer = findViewById(R.id.tftDetailsContainer)

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

        btnTouchYMinus = findViewById(R.id.btnTouchYMinus)
        tvTouchYOffset = findViewById(R.id.tvTouchYOffset)
        btnTouchYPlus = findViewById(R.id.btnTouchYPlus)
        btnTouchYReset = findViewById(R.id.btnTouchYReset)

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
        btnStart.setOnClickListener(::onPrimaryConnectionToggle)
        btnFullAaStart?.setOnClickListener(::onPrimaryConnectionToggle)
        btnTftSyncStop.setOnClickListener { button ->
            button.isEnabled = false
            stopProjection(button)
        }
        btnTftToggleStudio.setOnClickListener {
            setTftStudioOpen(false)
        }
        findViewById<Button>(R.id.btnTftChangeDashboard).setOnClickListener { button ->
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            setTftStudioOpen(true)
            btnTftToggleStudio.doOnLayout { anchor ->
                val bounds = Rect()
                anchor.getDrawingRect(bounds)
                mainScrollView.offsetDescendantRectToMyCoords(anchor, bounds)
                mainScrollView.smoothScrollTo(0, bounds.top)
            }
        }

        btnTftToggleDetails.setOnClickListener {
            setTftDetailsOpen(tftDetailsContainer.visibility != View.VISIBLE)
        }
        findViewById<Button>(R.id.btnTftDiagnosticLog).setOnClickListener { showConnectionIssueReport() }
        findViewById<Button>(R.id.btnConnectionIssueReport).setOnClickListener { button ->
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showConnectionIssueReport()
        }

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

        btnScanQr.setOnClickListener { launchBikeScanner() }

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
        val openMaps = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            openPhoneMaps()
        }
        val sendHome = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendAaHome()
        }
        val sendBack = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendAaBack()
        }

        btnAaMaps.setOnClickListener(openMaps)
        btnAaHome.setOnClickListener(sendHome)
        btnAaBack.setOnClickListener(sendBack)

        btnFullAaMaps.setOnClickListener(openMaps)
        findViewById<Button>(R.id.btnFullAaMic).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            requestAaVoiceCommand()
        }
        btnFullAaHome.setOnClickListener(sendHome)
        btnFullAaBack.setOnClickListener(sendBack)

        // Dock Navigation
        tabDashboard.setOnClickListener { selectTab(0) }
        tabScreen.setOnClickListener { selectTab(1) }
        tabTft.setOnClickListener { selectTab(2) }
        tabSettings.setOnClickListener {
            refreshCalibrationUiReadouts()
            selectTab(3)
        }

        setupSettingsListeners()
    }

    private fun onPrimaryConnectionToggle(view: View) {
        val toggle = SessionStartPolicy.evaluateToggleControl(
            running = SessionService.isRunning,
            projecting = SessionService.isProjectionRequested,
            phase = ConnectionState.phase,
        )
        when (toggle.command) {
            SessionStartPolicy.ToggleCommand.STOP_SESSION -> stopProjection(view)
            SessionStartPolicy.ToggleCommand.START_CONNECT -> startProjection(view)
        }
    }

    private fun startProjection(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        startAutoProjection(getString(R.string.conn_starting_aa))
    }

    private fun stopProjection(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        requestProjectionStop()
        Toast.makeText(this, getString(R.string.disconnected_ready), Toast.LENGTH_SHORT).show()
    }

    private fun requestProjectionStop() {
        val intent = Intent(this, SessionService::class.java).apply {
            action = SessionService.ACTION_STOP
        }
        startService(intent)
        updateFpsAndPlaceholderUi(0)
    }

    private fun launchBikeScanner() {
        qrScannerLauncher.launch(Intent(this, QrScanActivity::class.java))
    }

    private fun setupSettingsListeners() {
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
            Toast.makeText(this, getString(R.string.resolution_saved), Toast.LENGTH_SHORT).show()
        }

        // 2. Manual DPI Icon/Navbar Zoom (100 - 220 DPI, step 5)
        btnDpiMinus.setOnClickListener { adjustManualDpi(-5) }
        btnDpiPlus.setOnClickListener { adjustManualDpi(+5) }
        btnDpiReset.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            VideoPrefs.setDpiOverride(this, null)
            refreshCalibrationUiReadouts()
            updateTftStatusText()
            Toast.makeText(this, getString(R.string.dpi_reset_done), Toast.LENGTH_SHORT).show()
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
            applySafeAreaPreset(ProjectionHarness.SafeAreaPreset.ZONTES_TEMPLATE_SAFE, getString(R.string.preset_zontes_done))
        }
        btnPresetDeepSafe.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applySafeAreaPreset(ProjectionHarness.SafeAreaPreset.ZONTES_DEEP_FRAME, getString(R.string.preset_deep_done))
        }
        btnPresetFullReset.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            applySafeAreaPreset(ProjectionHarness.SafeAreaPreset.FULL_PANEL, getString(R.string.preset_full_done))
        }

        // Touch Y Offset Tuning
        btnTouchYMinus.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            TouchPrefs.setYOffset(this, TouchPrefs.yOffset - 6)
            refreshTouchOffsetUi()
        }
        btnTouchYPlus.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            TouchPrefs.setYOffset(this, TouchPrefs.yOffset + 6)
            refreshTouchOffsetUi()
        }
        btnTouchYReset.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            TouchPrefs.setYOffset(this, TouchPrefs.DEFAULT_Y_OFFSET)
            refreshTouchOffsetUi()
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
        val rawSy = (offsetY + normY * usableH).toInt() + TouchPrefs.yOffset
        val sy = rawSy.coerceIn(0, spec.height - 1)
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
            if (scroll != null) scroll.invoke(-1) else sendAaKeyOrToast(AaInput.KEY_LEFT, getString(R.string.focus_previous_action))
        }
        btnStickConfirm.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendAaKeyOrToast(AaInput.KEY_ENTER, getString(R.string.focus_ok_action))
        }
        btnRotaryNext.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val scroll = AaVideoBridge.scrollSink
            if (scroll != null) scroll.invoke(1) else sendAaKeyOrToast(AaInput.KEY_RIGHT, getString(R.string.focus_next_action))
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
            val rawSy = (offsetY + (clampedY / ph) * usableH).toInt() + TouchPrefs.yOffset
            val sy = rawSy.coerceIn(0, spec.height - 1)

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

    private fun openPhoneMaps() {
        val maps = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(maps)
        } catch (_: ActivityNotFoundException) {
            val googleMaps = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            try {
                if (googleMaps != null) {
                    startActivity(googleMaps)
                    return
                }
            } catch (_: ActivityNotFoundException) {
                // The app may have been disabled or removed since the lookup.
            }
            Toast.makeText(this, R.string.phone_maps_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAaVoiceCommand() {
        if (!AaVideoBridge.aaSessionLive) {
            showAaVoiceDisconnected()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            sendAaAssistant()
        } else {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun sendAaAssistant() {
        if (!headunitUiResumed || activeTabIndex != 1) return
        val microphone = AaVideoBridge.voiceMicrophone
        val keySink = AaVideoBridge.keySink
        if (!AaVideoBridge.aaSessionLive || microphone == null || keySink == null) {
            showAaVoiceDisconnected()
            return
        }
        microphone.arm()
        keySink(AaInput.KEY_ASSISTANT)
        keySink(AaInput.KEY_VOICE_ASSIST)
        AaVideoBridge.noteAaAudioActive()
        val spec = BikeProfileHolder.aaVideo
        injectAaTouchTap(spec.width - 36, spec.height - 28)
    }

    private fun showAaVoiceDisconnected() {
        Toast.makeText(this, getString(R.string.aa_disconnected_action, getString(R.string.aa_voice)), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.aa_disconnected_back), Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendAaKeyOrToast(keycode: Int, label: String) {
        val sink = AaVideoBridge.keySink
        if (sink != null) {
            sink.invoke(keycode)
        } else {
            Toast.makeText(this, getString(R.string.aa_disconnected_action, label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTelemetryListeners() {
        lifecycleScope.launch {
            RideScreenLiveDashboard.activeState.collect { active ->
                if (::tftStudio.isInitialized) tftStudio.setLiveActive(active)
            }
        }
        lifecycleScope.launch {
            LiveObd.status.collect { status ->
                if (::tftStudio.isInitialized) tftStudio.setObdStatus(status)
            }
        }
        lifecycleScope.launch {
            LiveObd.inventory.collect { inventory ->
                if (::tftStudio.isInitialized) tftStudio.setObdInventory(inventory)
            }
        }
        AaVideoBridge.headunitStatusListener = {
            runOnUiThread { if (!isFinishing && !isDestroyed) checkServerPort() }
        }
        ConnectionState.listener = { phase, detail ->
            runOnUiThread {
                updateConnectionUI(phase, detail)
                checkServerPort()
            }
        }
        AaVideoBridge.fpsListener = { fps ->
            runOnUiThread {
                updateFpsAndPlaceholderUi(fps)
                checkServerPort()
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
            val idleText = if (SessionService.isRunning) getString(R.string.waiting_aa_video) else getString(R.string.waiting_video)
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

    private fun updateConnectionUI(phase: Phase, detail: ConnectionState.Detail) {
        val label = getString(phase.labelRes)
        tvStatusTitle.text = label
        val savedSsid = BikeMemory.selected(this)?.qr?.ssid
        val masked = AutoConnectPrefs.isPrivacyMasked(this)
        val cleanDetail = ProjectionHarness.sanitizeStatusText(detail.resolve(this), savedSsid, masked)
        tvStatusDesc.text = if (cleanDetail.isNotBlank()) {
            cleanDetail
        } else {
            getString(R.string.connect_instructions)
        }

        if (phase == Phase.STREAMING || phase == Phase.AA_VIDEO_LIVE) {
            statusDot.setBackgroundResource(R.drawable.btn_liquid_emerald)
            tvOnlineBadge.text = getString(R.string.badge_live)
            tvOnlineBadge.setTextColor(ContextCompat.getColor(this, R.color.green_neon))
        } else if (phase.busy) {
            statusDot.setBackgroundResource(R.drawable.btn_liquid_sapphire)
            tvOnlineBadge.text = getString(R.string.badge_connecting)
            tvOnlineBadge.setTextColor(ContextCompat.getColor(this, R.color.cyan_neon))
        } else if (phase == Phase.ERROR) {
            statusDot.setBackgroundResource(R.drawable.btn_liquid_ruby)
            tvOnlineBadge.text = getString(R.string.badge_error)
            tvOnlineBadge.setTextColor(ContextCompat.getColor(this, R.color.ruby_bright))
        } else {
            statusDot.setBackgroundResource(R.drawable.dock_capsule_bg)
            tvOnlineBadge.text = getString(R.string.badge_standby)
            tvOnlineBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
        updatePrimaryToggleUi(phase)
        updateTftSyncUi(phase, hasSavedBikeWifi = !savedSsid.isNullOrBlank(), detail = cleanDetail)
        if (tftDetailsContainer.visibility == View.VISIBLE) updateTftStatusText()
    }

    private fun updatePrimaryToggleUi(phase: Phase) {
        val toggle = SessionStartPolicy.evaluateToggleControl(
            running = SessionService.isRunning,
            projecting = SessionService.isProjectionRequested,
            phase = phase,
        )
        btnStart.setText(toggle.textRes)
        btnStart.setBackgroundResource(toggle.backgroundRes)
        btnStart.setCompoundDrawablesRelativeWithIntrinsicBounds(toggle.iconRes, 0, 0, 0)
        btnFullAaStart?.let { btn ->
            val stopMode = toggle.command == SessionStartPolicy.ToggleCommand.STOP_SESSION
            btn.setText(if (stopMode) R.string.disconnect else R.string.full_live_start)
            btn.setBackgroundResource(if (stopMode) R.drawable.btn_liquid_ruby else R.drawable.btn_liquid_emerald)
        }
    }

    private fun updateTftSyncUi(phase: Phase, hasSavedBikeWifi: Boolean, detail: String) {
        val judgment = ProjectionHarness.evaluateTftSyncUi(phase, SessionService.isProjectionRequested, hasSavedBikeWifi)
        tvTftSyncStatus.setText(judgment.statusRes)
        tvTftSyncHint.text = detail.takeIf {
            it.isNotBlank() && (judgment.statusRes == R.string.conn_error || phase == Phase.RECONNECTING && judgment.canStop)
        } ?: getString(judgment.hintRes)

        btnTftSyncStop.visibility = if (judgment.canStop) View.VISIBLE else View.GONE
        btnTftSyncStop.isEnabled = judgment.canStop
    }

    private fun refreshSavedBikeInfo() {
        val bike = BikeMemory.selected(this)
        val masked = AutoConnectPrefs.isPrivacyMasked(this)
        if (bike != null) {
            val qr = bike.qr
            tvSavedBike.text = ProjectionHarness.maskIdentifier(bike.name, masked)
            if (masked) {
                tvSavedWifi.text = getString(R.string.saved_wifi_masked, ProjectionHarness.maskIdentifier(qr?.ssid, masked = true))
                tvPrivacyToggleBadge.text = getString(R.string.reveal_details)
                tvPrivacyToggleBadge.setTextColor(ContextCompat.getColor(this, R.color.cyan_neon))
            } else {
                val ssidFull = qr?.ssid ?: getString(R.string.unspecified)
                tvSavedWifi.text = "Wi-Fi SSID: $ssidFull"
                tvPrivacyToggleBadge.text = getString(R.string.hide_details)
                tvPrivacyToggleBadge.setTextColor(ContextCompat.getColor(this, R.color.amber_accent))
            }
        } else {
            tvSavedBike.text = getString(R.string.no_saved_bike)
            tvSavedWifi.text = getString(R.string.save_bike_hint)
            tvPrivacyToggleBadge.text = getString(R.string.scan_qr)
            tvPrivacyToggleBadge.setTextColor(ContextCompat.getColor(this, R.color.cyan_neon))
        }
    }

    /**
     * Pure status read — ZERO socket I/O. Gearhead 17.9's head unit server (:5277) treats every
     * accepted TCP socket as a real head unit; a throwaway "is it listening?" probe here used to
     * crash its `:projection` process with `IllegalStateException: Already connected` every time
     * the rider came back from Android Auto settings (onResume). The receiver's coordinator owns
     * the only :5277 socket and publishes the outcome in [AaVideoBridge.headunitStatus].
     */
    private fun checkServerPort(fromUserClick: Boolean = false) {
        val running = SessionService.isRunning
        val headunitLive = AaVideoBridge.headunitSessionLive
        val detail = ConnectionState.detail
        val needsSetup = ConnectionState.phase == Phase.ERROR ||
            detail.isResource(R.string.headunit_refused) || detail.isResource(R.string.headunit_wedged)
        if (!headunitSetupComplete && headunitUiResumed) {
            when {
                headunitLive -> {
                    headunitSetupComplete = true
                    headunitSetupDialog?.dismiss()
                    activateProjectionControls()
                }
                needsSetup -> showRequiredHeadunitSetup()
            }
        }
        val judgment = ProjectionHarness.evaluateServerStatusUi(
            sessionRunning = running,
            handshakeLive = AaVideoBridge.aaSessionLive,
            decoding = AaVideoBridge.aaDecoding,
            fps = AaVideoBridge.currentFps,
            headunit = AaVideoBridge.headunitStatus,
        )
        val header = ProjectionHarness.evaluateHeadunitHeader(
            setupComplete = headunitSetupComplete,
            sessionRunning = running,
            headunitLive = headunitLive,
            androidAutoLive = AaVideoBridge.aaSessionLive,
            headunit = if (headunitSetupComplete || needsSetup) AaVideoBridge.headunitStatus
                else ProjectionHarness.HeadunitLinkStatus.UNKNOWN,
            projectionRequested = SessionService.isProjectionRequested,
        )
        val status = if (SessionService.isProjectionRequested) judgment else header
        val serverText = getString(status.textRes)
        tvServerStatus.text = serverText
        tvServerStatus.setTextColor(serverStatusColor(status.level))
        tvHeadunitHeaderStatus.setText(header.textRes)
        tvHeadunitHeaderStatus.setTextColor(serverStatusColor(header.level))
        if (!fromUserClick || !headunitSetupComplete || headunitLive) return
        val guidance = when (AaVideoBridge.headunitStatus) {
            ProjectionHarness.HeadunitLinkStatus.REFUSED -> R.string.headunit_refused
            ProjectionHarness.HeadunitLinkStatus.HANDSHAKE_FAILED -> R.string.headunit_wedged
            else -> R.string.headunit_dialog_message
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.headunit_dialog_title))
            .setMessage(guidance)
            .setPositiveButton(R.string.headunit_open_settings) { _, _ -> openAndroidAutoSettings() }
            .setNegativeButton(R.string.close, null)
        if (!running) {
            dialog.setNeutralButton(R.string.check_status) { _, _ -> requestHeadunitCheck() }
        }
        dialog.show()
    }

    private fun serverStatusColor(level: ProjectionHarness.UiLevel): Int = ContextCompat.getColor(this, when (level) {
        ProjectionHarness.UiLevel.OK -> R.color.green_neon
        ProjectionHarness.UiLevel.BUSY -> R.color.cyan_neon
        ProjectionHarness.UiLevel.WARN -> R.color.amber_accent
        ProjectionHarness.UiLevel.ERROR -> R.color.ruby_bright
        ProjectionHarness.UiLevel.NEUTRAL -> R.color.text_secondary
    })

    private fun openAndroidAutoSettings(): Boolean {
        try {
            val intent = Intent("com.google.android.projection.gearhead.SETTINGS").apply {
                setPackage("com.google.android.projection.gearhead")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return true
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
        return false
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

            refreshCalibrationUiReadouts()
            updateTftStatusText()
        } finally {
            suppressSettingsCallbacks = false
        }
    }

    private fun refreshCalibrationUiReadouts() {
        val manualDpi = VideoPrefs.dpiOverride(this)
        val defaultDpi = BikeProfileHolder.active.aaVideo.dpi
        tvDpiValue.text = if (manualDpi != null) {
            getString(R.string.dpi_manual, manualDpi)
        } else {
            getString(R.string.dpi_recommended, defaultDpi)
        }

        val scale = ScreenMargins.scalePercent
        tvScaleValue.text = if (scale == 100) getString(R.string.scale_full) else getString(R.string.scale_value, scale)
        tvMarginTop.text = getString(R.string.margin_top_value, ScreenMargins.top)
        tvMarginBottom.text = getString(R.string.margin_bottom_value, ScreenMargins.bottom)
        tvMarginLeft.text = getString(R.string.margin_left_value, ScreenMargins.left)
        tvMarginRight.text = getString(R.string.margin_right_value, ScreenMargins.right)
        refreshTouchOffsetUi()
    }

    private fun refreshTouchOffsetUi() {
        val cur = TouchPrefs.yOffset
        val label = when {
            cur < 0 -> getString(R.string.touch_up_value, cur)
            cur > 0 -> getString(R.string.touch_down_value, cur)
            else -> getString(R.string.touch_center_value)
        }
        tvTouchYOffset.text = label
    }

    private fun setTftStudioOpen(open: Boolean) {
        if (open && !::tftStudio.isInitialized) {
            tftStudio = LiveDashboardStudio(
                activity = this,
                onFactoryRestore = ::restoreTftFactory,
                onChooseBikeModel = ::chooseThaiBikeModel,
                onToggleLiveDashboard = ::toggleRideScreenDashboard,
                onChooseObd = ::chooseLiveObd,
                onTestObd = { if (!LiveObd.startTest(this)) chooseLiveObd() },
            )
            tftStudioContainer.addView(tftStudio.view)
        }
        if (open) tftStudio.setLiveActive(RideScreenLiveDashboard.active)
        if (!open) LiveObd.cancelTest(this)
        tftStudioContainer.visibility = if (open) View.VISIBLE else View.GONE
        btnTftToggleStudio.visibility = tftStudioContainer.visibility
    }

    @SuppressLint("MissingPermission")
    private fun chooseLiveObd() {
        if (!LiveObd.hasPermission(this)) {
            obdPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val devices = try {
            LiveObd.pairedDevices(this)
        } catch (_: SecurityException) {
            emptyList()
        }
        val entries = arrayOf(getString(R.string.ridescreen_obd_disable)) +
            devices.map { it.name ?: it.address }
        val selected = devices.indexOfFirst { it.address == LiveObd.selectedAddress(this) } + 1
        val dialog = AlertDialog.Builder(this).setTitle(R.string.ridescreen_obd_choose)
        if (devices.isEmpty()) dialog.setMessage(R.string.ridescreen_obd_unavailable)
        else dialog.setSingleChoiceItems(entries, selected) { picker, index ->
            LiveObd.select(this, devices.getOrNull(index - 1)?.address)
            picker.dismiss()
        }
        dialog.setNegativeButton(R.string.tft_cancel, null)
            .setNeutralButton(R.string.ridescreen_obd_pair) { _, _ ->
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        if (devices.isEmpty() && LiveObd.selectedAddress(this) != null) {
            dialog.setPositiveButton(R.string.ridescreen_obd_disable) { _, _ ->
                LiveObd.select(this, null)
            }
        }
        dialog.show()
    }

    private fun chooseThaiBikeModel() {
        val raw = BikeMemory.lastRaw(this)
        if (raw.isNullOrBlank()) {
            Toast.makeText(this, R.string.ridescreen_choose_bike_first, Toast.LENGTH_LONG).show()
            return
        }
        val models = ThaiZontesModel.entries
        val labels = arrayOf(getString(R.string.ridescreen_model_auto)) + models.map { it.label }
        val current = ThaiZontesModelPrefs.selected(this, raw)
        val selected = current?.let { models.indexOf(it) + 1 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.ridescreen_choose_bike)
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                ThaiZontesModelPrefs.set(this, raw, models.getOrNull(index - 1))
                dialog.dismiss()
                val notice = if (ProfilePrefs.get(this) == ProfileOverride.AUTO) R.string.ridescreen_model_next_connection
                    else R.string.ridescreen_model_manual_override
                Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.tft_cancel, null)
            .show()
    }

    private fun toggleRideScreenDashboard() {
        if (RideScreenLiveDashboard.active) {
            RideScreenLiveDashboard.stop(this, byUser = true)
            Toast.makeText(this, R.string.ridescreen_returned_to_aa, Toast.LENGTH_SHORT).show()
            return
        }
        val attempt = LiveDashboardLogAttempt(LiveDashboardLogAttempt.newId(), TftDiagnostics::recordLiveDashboard)
        attempt.event(LiveDashboardEvent.REQUESTED)
        val rejection = when {
            !SessionService.isProjectionRequested -> LiveDashboardEvent.REJECTED_NO_PROJECTION
            ConnectionState.phase != Phase.STREAMING -> LiveDashboardEvent.REJECTED_NOT_STREAMING
            AaVideoBridge.pipeline == null -> LiveDashboardEvent.REJECTED_NO_PIPELINE
            else -> null
        }
        if (rejection != null) {
            attempt.event(rejection)
            Toast.makeText(this, R.string.ridescreen_connect_first, Toast.LENGTH_LONG).show()
            return
        }
        val started = try {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_LIVE_DASHBOARD)
                .putExtra(SessionService.EXTRA_LIVE_ATTEMPT, attempt.id)) != null
        } catch (_: Exception) {
            false
        }
        if (!started) {
            attempt.event(LiveDashboardEvent.SERVICE_REQUEST_FAILED)
            Toast.makeText(this, R.string.ridescreen_live_start_error, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.ridescreen_live_started, Toast.LENGTH_LONG).show()
    }

    private fun restoreTftFactory() {
        if (SessionService.isProjectionRequested) requestProjectionStop()
        if (preparingTftFactoryCopy) return
        preparingTftFactoryCopy = true
        tftStudio.setFactoryCopyStatus(R.string.tft_backup_preparing)
        lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) { tftFactoryDownloads.prepareCopy() }
                TftDiagnostics.recordFactoryCopy(status)
                tftStudio.setFactoryCopyStatus(when (status) {
                    TftFactoryBackup.CopyStatus.READY -> R.string.tft_backup_exported
                    TftFactoryBackup.CopyStatus.NO_ORIGINAL -> R.string.tft_backup_missing
                    TftFactoryBackup.CopyStatus.INVALID_ORIGINAL -> R.string.tft_backup_invalid
                    TftFactoryBackup.CopyStatus.FAILED -> R.string.tft_backup_error
                })
            } finally {
                preparingTftFactoryCopy = false
            }
        }
    }

    private fun setTftDetailsOpen(open: Boolean) {
        if (open) updateTftStatusText()
        tftDetailsContainer.visibility = if (open) View.VISIBLE else View.GONE
        btnTftToggleDetails.setText(if (open) R.string.tft_close_details else R.string.tft_open_details)
        btnTftToggleDetails.setBackgroundResource(if (open) R.drawable.btn_liquid_amber else R.drawable.btn_liquid_sapphire)
    }

    private suspend fun buildDiagnosticReportText(): String {
        val history = TftDiagnostics.read()
        val persistedTrace = TftDiagnostics.readTrace()
        val liveTrace = LogBus.snapshot()
            .lineSequence()
            .filter(TftDiagnostics::isDiagnosticTraceLine)
            .joinToString("\n")
        val combinedTrace = if (persistedTrace.isNotBlank()) persistedTrace else liveTrace
        val savedBike = BikeMemory.selected(this)
        val qr = savedBike?.qr
        val maskedSsid = qr?.ssid?.let { ProjectionHarness.maskIdentifier(it, masked = true) }
        val phoneInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT})"
        return TftDiagnostics.formatQuickReport(
            diagnosticsText = history,
            traceText = combinedTrace,
            phoneInfo = phoneInfo,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            activeProfileName = BikeProfileHolder.active.name,
            savedQrModel = qr?.modelId,
            maskedSsid = maskedSsid,
            headunitStatus = AaVideoBridge.headunitStatus.name,
            connectionPhase = ConnectionState.phase.name,
        )
    }

    private fun copyTextToClipboard(report: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("RideLink TH Report", report))
        Toast.makeText(this, R.string.tft_log_copied, Toast.LENGTH_LONG).show()
    }

    private fun showConnectionIssueReport() {
        lifecycleScope.launch {
            val phase = ConnectionState.phase
            val headunit = AaVideoBridge.headunitStatus
            val evidence = try {
                ConnectionIssueEvidence.fromHistory(TftDiagnostics.read())
            } catch (_: IOException) {
                ConnectionIssueEvidence()
            }
            ConnectionIssueReportDialog(
                this@MainActivity, evidence, phase, headunit, ConnectionIssueSubmission::send, ::showTftDiagnosticLog,
            ).show()
        }
    }

    private fun showTftDiagnosticLog() {
        lifecycleScope.launch {
            val report = try {
                buildDiagnosticReportText()
            } catch (_: IOException) {
                Toast.makeText(this@MainActivity, R.string.tft_log_io_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.tft_log_title)
                .setMessage(getString(R.string.tft_log_auto_info) + "\n\n" + report)
                .setNegativeButton(R.string.close, null)
                .setNeutralButton(R.string.tft_log_share) { _, _ ->
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    startActivity(Intent.createChooser(share, getString(R.string.tft_log_share)))
                }
                .setPositiveButton(R.string.tft_log_copy) { _, _ ->
                    copyTextToClipboard(report)
                }
                .show()
            dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
        }
    }

    private fun updateTftStatusText() {
        val p = BikeProfileHolder.active
        val v = BikeProfileHolder.aaVideo
        val panel = p.panelSize
        val unavailable = getString(R.string.tft_test_unavailable)
        val panelStr = if (panel != null) "${panel.first}×${panel.second}" else unavailable
        val streamInfo = getString(
            R.string.tft_info, p.name, panelStr, "${v.width}×${v.height} @ ${v.dpi} DPI",
            ScreenMargins.scalePercent, ScreenMargins.top, ScreenMargins.bottom,
            ScreenMargins.left, ScreenMargins.right
        )
        val versions = ConnectionState.projectionVersions
        val testStatus = if (ConnectionState.phase == Phase.STREAMING) {
            getString(R.string.tft_test_streaming, versions?.client ?: unavailable, versions?.sdk ?: unavailable)
        } else {
            getString(R.string.tft_test_waiting)
        }
        tvTftStatus.text = listOf(streamInfo, testStatus, getString(R.string.tft_test_factory_scope)).joinToString("\n\n")
    }

    private fun selectTab(index: Int) {
        if (index != 1) {
            voiceAfterPermission = false
            AaVideoBridge.voiceMicrophone?.stop()
        }
        activeTabIndex = index
        val panes = listOf(paneDashboard, paneScreen, paneTft, paneSettings)
        panes.forEachIndexed { i, pane ->
            pane.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        val showTft = index == 2
        tvAppTagline.setText(if (showTft) R.string.tft_tab_tagline else R.string.app_tagline)
        tftSyncActionBar.visibility = if (showTft) View.VISIBLE else View.GONE
        mainScrollView.updateLayoutParams<RelativeLayout.LayoutParams> {
            addRule(RelativeLayout.ABOVE, if (showTft) R.id.tftSyncActionBar else R.id.bottomDockContainer)
        }
        if (showTft) {
            updateTftStatusText()
            updateConnectionUI(ConnectionState.phase, ConnectionState.detail)
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
