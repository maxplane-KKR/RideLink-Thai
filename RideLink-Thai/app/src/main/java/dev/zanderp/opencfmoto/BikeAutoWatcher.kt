package dev.zanderp.opencfmoto

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.zanderp.opencfmoto.aa.AaSelfMode

/**
 * Application-wide Bluetooth + QR-Menu Wi-Fi Auto-Connect Watcher controlled by [ProjectionHarness].
 *
 * Solves all 4 real-world failures of BT Auto-Connect on Zontes 150X / Carbit clusters:
 *  1. Survives `MainActivity.onPause()` / screen-off / pocket riding via `applicationContext` receiver.
 *  2. Listens to Bluetooth `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` so ignition cycles
 *     reset manual-stop state and enter ready standby without prematurely starting AA on normal rides.
 *  3. Combines `SCAN_RESULTS_AVAILABLE_ACTION` + periodic scan checks WITH a hardware-level passive
 *     `WifiNetworkSpecifier` listener (once the bike SSID has been approved once), bypassing
 *     Xiaomi HyperOS / Android 13+ background Wi-Fi scan throttling.
 *  4. Keeps the passive `NetworkCallback` alive for 1500ms during handoff to [SessionService] so the
 *     `WifiNetworkSpecifier` refcount never drops to 0 mid-handoff.
 */
object BikeAutoWatcher {
    private const val PREFS_APPROVED = "ridelink_approved_ssids"
    private const val PASSIVE_REQUEST_WINDOW_MS = 35_000
    private const val POLL_INTERVAL_BT_ACTIVE_MS = 3_000L
    private const val POLL_INTERVAL_IDLE_MS = 6_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var receiverRegistered = false

    @Volatile var manuallyStopped: Boolean = false
        private set
    @Volatile var btConnected: Boolean = false
        private set
    @Volatile private var appInForeground: Boolean = false
    @Volatile private var lastTriggerElapsedMs: Long = -60_000L
    @Volatile private var lastScanEvalElapsedMs: Long = 0L

    private var passiveCm: ConnectivityManager? = null
    private var passiveCallback: ConnectivityManager.NetworkCallback? = null
    private var passiveArmedSsid: String? = null

    /** Optional UI listener so MainActivity can show live BT + Auto-Connect standby status. */
    @Volatile var statusListener: ((btConnected: Boolean, statusTh: String) -> Unit)? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            val ctx = appCtx ?: return
            // If the app is in the background and no foreground session is running,
            // stop the active polling loop immediately to prevent waking CPU and draining battery.
            if (!appInForeground && !SessionService.isRunning) {
                disarmPassiveWifiRequest(immediate = true)
                return
            }
            evaluateAndAct(ctx, qrWifiFromPassiveCallback = false)
            val delay = if (btConnected || appInForeground) POLL_INTERVAL_BT_ACTIVE_MS else POLL_INTERVAL_IDLE_MS
            if (AutoConnectPrefs.isAutoConnectEnabled(ctx) && !SessionService.isRunning && !manuallyStopped) {
                mainHandler.postDelayed(this, delay)
            }
        }
    }

    private val btWifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val act = intent?.action ?: return
            when (act) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val savedSsid = BikeMemory.selected(context)?.qr?.ssid
                    val allSsids = BikeMemory.devices(context).mapNotNull { it.qr?.ssid }
                    val matchesBike = dev != null && AaSelfMode.isBikeDevice(dev, savedSsid, allSsids)
                    if (matchesBike) {
                        btConnected = true
                        if (manuallyStopped) {
                            // Fresh bike ignition / BT reconnect clears previous ride's manual stop.
                            manuallyStopped = false
                        }
                        LogBus.log("[AutoBT] บลูทูธรถเชื่อมต่อแล้ว (${dev.name}) — เฝ้ารอสัญญาณหน้าเมนู QR")
                        if (appInForeground) {
                            kickWatchLoop(context)
                        } else {
                            notifyUi(context)
                        }
                    } else {
                        // Non-bike device connected (smartwatch, earbud, car, etc.) - ignore!
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val savedSsid = BikeMemory.selected(context)?.qr?.ssid
                    val allSsids = BikeMemory.devices(context).mapNotNull { it.qr?.ssid }
                    if (dev == null || AaSelfMode.isBikeDevice(dev, savedSsid, allSsids)) {
                        val stillConnected = AaSelfMode.hasConnectedBluetooth(context)
                        btConnected = stillConnected
                        if (!stillConnected) {
                            // Bike switched off -> clear manual stop so the next ride auto-connects cleanly.
                            manuallyStopped = false
                            LogBus.log("[AutoBT] บลูทูธรถตัดการเชื่อมต่อ — รีเซ็ตสถานะพร้อมสำหรับรอบถัดไป")
                        }
                        if (appInForeground) {
                            evaluateAndAct(context, qrWifiFromPassiveCallback = false)
                        } else {
                            disarmPassiveWifiRequest(immediate = true)
                            notifyUi(context)
                        }
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    btConnected = AaSelfMode.hasConnectedBluetooth(context)
                    if (appInForeground) {
                        kickWatchLoop(context)
                    } else {
                        notifyUi(context)
                    }
                }

                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                    if (appInForeground) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastScanEvalElapsedMs >= 5_000L) {
                            lastScanEvalElapsedMs = now
                            if (!SessionService.isRunning && !manuallyStopped) {
                                evaluateAndAct(context, qrWifiFromPassiveCallback = false)
                            }
                        }
                    }
                }
            }
        }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        appCtx = app
        btConnected = AaSelfMode.hasConnectedBluetooth(app)
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(btWifiReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    app.registerReceiver(btWifiReceiver, filter)
                }
                receiverRegistered = true
            } catch (e: Exception) {
                LogBus.log("[AutoBT] registerReceiver warning: ${e.message}")
            }
        }
        if (appInForeground) {
            kickWatchLoop(app)
        }
    }

    fun onAppForegroundChanged(context: Context, inForeground: Boolean) {
        appInForeground = inForeground
        if (!inForeground) {
            // When leaving foreground, immediately stop polling and disarm passive specifiers
            // to conform to Android OS background restrictions and save battery.
            disarmPassiveWifiRequest(immediate = true)
            mainHandler.removeCallbacks(pollRunnable)
        } else {
            init(context)
        }
    }

    fun onManualStop(context: Context) {
        manuallyStopped = true
        disarmPassiveWifiRequest(immediate = true)
        mainHandler.removeCallbacks(pollRunnable)
        notifyUi(context)
    }

    fun onManualStartOrSessionActive() {
        manuallyStopped = false
        // Delay disarming passive Wi-Fi callback by 1.5s if it was armed so SessionService's
        // BikeWifi.join overlaps and keeps the WifiNetworkSpecifier association seamless.
        disarmPassiveWifiRequest(immediate = false)
        mainHandler.removeCallbacks(pollRunnable)
    }

    fun onSessionStoppedNaturally(context: Context) {
        if (!manuallyStopped) {
            kickWatchLoop(context)
        } else {
            notifyUi(context)
        }
    }

    fun refreshConfig(context: Context) {
        val app = context.applicationContext
        if (!AutoConnectPrefs.isAutoConnectEnabled(app)) {
            disarmPassiveWifiRequest(immediate = true)
            mainHandler.removeCallbacks(pollRunnable)
        } else {
            manuallyStopped = false
            kickWatchLoop(app)
        }
        notifyUi(app)
    }

    fun markSsidApproved(context: Context, ssid: String) {
        if (ssid.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS_APPROVED, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ssid.trim().uppercase(), true)
            .apply()
    }

    fun isSsidApproved(context: Context, ssid: String): Boolean {
        if (ssid.isBlank()) return false
        val clean = ssid.trim()
        val selected = BikeMemory.selected(context)?.qr?.ssid
        if (!selected.isNullOrBlank() && selected.equals(clean, ignoreCase = true)) {
            return true
        }
        val allDevices = BikeMemory.devices(context)
        if (allDevices.any { it.qr?.ssid?.equals(clean, ignoreCase = true) == true }) {
            return true
        }
        return context.applicationContext
            .getSharedPreferences(PREFS_APPROVED, Context.MODE_PRIVATE)
            .getBoolean(clean.uppercase(), false)
    }

    private fun kickWatchLoop(context: Context) {
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.post(pollRunnable)
    }

    private fun evaluateAndAct(context: Context, qrWifiFromPassiveCallback: Boolean) {
        val app = context.applicationContext
        btConnected = AaSelfMode.hasConnectedBluetooth(app)
        val qr = BikeMemory.selected(app)?.qr
        val ssid = qr?.ssid
        val inScan = if (!ssid.isNullOrBlank() && !SessionService.isRunning && appInForeground) {
            BikeWifi.isSsidInRange(app, ssid) == true
        } else {
            false
        }
        val qrDetected = qrWifiFromPassiveCallback || inScan
        val sinceLast = SystemClock.elapsedRealtime() - lastTriggerElapsedMs

        val judgment = ProjectionHarness.evaluateAutoTrigger(
            ProjectionHarness.AutoTriggerInput(
                autoConnectEnabled = AutoConnectPrefs.isAutoConnectEnabled(app),
                savedSsid = ssid,
                sessionRunning = SessionService.isRunning,
                manuallyStopped = manuallyStopped,
                btBikeConnected = btConnected,
                qrWifiDetected = qrDetected,
                msSinceLastTrigger = sinceLast,
            )
        )

        statusListener?.let { cb ->
            mainHandler.post { cb(btConnected, judgment.reasonTh) }
        }

        when (judgment.decision) {
            ProjectionHarness.TriggerDecision.FIRE_PROJECTION -> {
                lastTriggerElapsedMs = SystemClock.elapsedRealtime()
                LogBus.log("[AutoQR] ${judgment.reasonTh} -> เริ่มฉายภาพ Android Auto อัตโนมัติ")
                // Keep passive Wi-Fi callback alive for 1500ms while SessionService joins BikeWifi
                disarmPassiveWifiRequest(immediate = false)
                mainHandler.removeCallbacks(pollRunnable)
                launchSessionFromWatcher(app)
            }

            ProjectionHarness.TriggerDecision.ARM_WIFI_WATCHER_ONLY -> {
                if (qr != null && isSsidApproved(app, qr.ssid) && (btConnected || appInForeground)) {
                    ensurePassiveWifiArmed(app, qr.ssid, qr.pwd)
                } else {
                    disarmPassiveWifiRequest(immediate = true)
                }
            }

            else -> {
                if (!judgment.shouldKeepPassiveWifiRequest) {
                    disarmPassiveWifiRequest(immediate = true)
                }
            }
        }
    }

    private fun ensurePassiveWifiArmed(app: Context, ssid: String, psk: String) {
        // Android OS strictly forbids WifiNetworkSpecifier requests if not called from
        // a foreground activity or an active foreground service.
        if (!appInForeground && !SessionService.isRunning) {
            return
        }
        if (passiveCallback != null && passiveArmedSsid.equals(ssid, ignoreCase = true)) {
            return
        }
        disarmPassiveWifiRequest(immediate = true)

        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(psk)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogBus.log("[AutoQR] ฮาร์ดแวร์ Wi-Fi ตรวจพบหน้าเมนู QR ของรถ ($ssid)")
                mainHandler.post {
                    evaluateAndAct(app, qrWifiFromPassiveCallback = true)
                }
            }

            override fun onUnavailable() {
                // In Android OS, ConnectivityService automatically unregisters this callback on unavailable.
                // Do NOT call cm.unregisterNetworkCallback(this) here as it logs "NetworkCallback was already unregistered".
                mainHandler.post {
                    if (passiveCallback === this) {
                        passiveCallback = null
                        passiveArmedSsid = null
                    }
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    if (passiveCallback === this) {
                        passiveCallback = null
                        passiveArmedSsid = null
                    }
                }
            }
        }

        try {
            passiveCm = cm
            passiveCallback = cb
            passiveArmedSsid = ssid
            cm.requestNetwork(req, cb, PASSIVE_REQUEST_WINDOW_MS)
        } catch (e: Exception) {
            passiveCallback = null
            passiveArmedSsid = null
            LogBus.log("[AutoQR] passive requestNetwork skipped: ${e.message}")
        }
    }

    private fun disarmPassiveWifiRequest(immediate: Boolean) {
        val cm = passiveCm
        val cb = passiveCallback ?: return
        passiveCallback = null
        passiveArmedSsid = null
        if (immediate || cm == null) {
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        } else {
            mainHandler.postDelayed({
                try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
            }, 1_500L)
        }
    }

    private fun launchSessionFromWatcher(app: Context) {
        try {
            val intent = Intent(app, SessionService::class.java).apply {
                action = SessionService.ACTION_START
            }
            ContextCompat.startForegroundService(app, intent)
        } catch (e: Exception) {
            LogBus.log("[AutoQR] launchSessionFromWatcher error: ${e.message}")
        }
    }

    private fun notifyUi(context: Context) {
        evaluateAndAct(context.applicationContext, qrWifiFromPassiveCallback = false)
    }
}
