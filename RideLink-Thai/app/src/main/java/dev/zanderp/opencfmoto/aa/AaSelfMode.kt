// OpenCfMoto glue (technique ported from headunit-revived AGPLv3 AapService.startSelfMode).
// Triggers Google Android Auto's loopback "self-mode": asks gearhead to project to 127.0.0.1:PORT
// with NO VPN. Best launched from a foreground Activity to satisfy Android's background-activity-
// launch restrictions (Android 12+/15).
//
// Path (escalating — wait for :5288 accept / session between steps):
//  1) WirelessStartupActivity (1.0.x — works on older gearhead)
//  2) WirelessStartupReceiver (1.0.x / AA 16.4+ when activity is not exported)
//  3) WifiBluetoothReceiver + START_WIRELESS_PROJECTION (AA 17.4+ / HUR) — ONLY with a real BT MAC
// Never use a dummy MAC: that causes Pixel "Wi‑Fi OK / AA never starts" loops after 2.0.6.
// Callers must NOT re-fire this while a session is already live — that kills AAP.
package dev.zanderp.opencfmoto.aa

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import java.util.concurrent.atomic.AtomicInteger

object AaSelfMode {
    private const val GEARHEAD_PKG = "com.google.android.projection.gearhead"
    /** Wait after each self-mode poke before escalating if AA never attaches. */
    private const val ESCALATE_WAIT_MS = 950L

    private val mainHandler = Handler(Looper.getMainLooper())
    /** Bumps when a new trigger starts so stale escalate callbacks exit. */
    private val triggerGeneration = AtomicInteger(0)

    /** Optional foreground Activity hook so SessionService can satisfy BAL rules when visible. */
    @Volatile var foregroundActivityProvider: (() -> android.app.Activity?)? = null

    fun trigger(context: Context, port: Int = AaReceiver.PORT, log: (String) -> Unit) {
        // Additive safety: never poke gearhead again if AAP is already up.
        if (sessionLive()) {
            log("[AA] self-mode skip — session already live/decoding")
            return
        }

        val app = context.applicationContext
        val launchCtx: Context = foregroundActivityProvider?.invoke() ?: context
        val gen = triggerGeneration.incrementAndGet()
        logGearheadVersion(app, log)

        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkToUse: Parcelable? = (cm.activeNetwork as? Parcelable) ?: createFakeNetwork(0)
        val fakeWifiInfo = createFakeWifiInfo()

        // 1) Activity (older gearhead). Do NOT return on success — AA 16.4+/17.4+ often
        // startActivity without throwing and then ignore the intent (HUR never ran before).
        var activityStarted = false
        try {
            val intent = Intent().apply {
                setClassName(
                    GEARHEAD_PKG,
                    "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity",
                )
                if (launchCtx !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
                putExtra("PARAM_SERVICE_PORT", port)
                networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                fakeWifiInfo?.let { putExtra("wifi_info", it) }
            }
            log("[AA] launching Android Auto WirelessStartupActivity → 127.0.0.1:$port")
            launchCtx.startActivity(intent)
            activityStarted = true
        } catch (e: Exception) {
            log("[AA] Activity trigger failed (${e.message}); trying broadcast fallback")
        }

        if (!activityStarted) {
            // Activity denied — escalate Receiver immediately (Pure Wi-Fi / Loopback).
            // Skip slow BT HUR handshake entirely for instant Wi-Fi/Loopback startup.
            fireWirelessStartupReceiver(app, port, networkToUse, fakeWifiInfo, log)
            return
        }

        // Activity launched without throw — wait for accept, then Receiver.
        // Skip slow BT HUR handshake entirely for instant Wi-Fi/Loopback startup.
        scheduleIfStillNeeded(gen, ESCALATE_WAIT_MS) {
            if (sessionLive()) {
                log("[AA] self-mode escalate stop — session live after Activity")
                return@scheduleIfStillNeeded
            }
            log("[AA] no session after Activity — escalating to WirelessStartupReceiver")
            fireWirelessStartupReceiver(app, port, networkToUse, fakeWifiInfo, log)
        }
    }

    private fun sessionLive(): Boolean =
        dev.zanderp.opencfmoto.AaVideoBridge.aaSessionLive ||
            dev.zanderp.opencfmoto.AaVideoBridge.aaHandshaking ||
            dev.zanderp.opencfmoto.AaVideoBridge.aaDecoding

    /** Drop every pending escalation step (called on Stop so nothing pokes Gearhead afterwards). */
    fun cancel() {
        triggerGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun scheduleIfStillNeeded(gen: Int, delayMs: Long, block: () -> Unit) {
        mainHandler.postDelayed({
            if (gen != triggerGeneration.get()) return@postDelayed
            if (sessionLive()) return@postDelayed
            block()
        }, delayMs)
    }

    private fun fireWirelessStartupReceiver(
        app: Context,
        port: Int,
        networkToUse: Parcelable?,
        fakeWifiInfo: Parcelable?,
        log: (String) -> Unit,
    ) {
        try {
            val receiverIntent = Intent().apply {
                setClassName(
                    GEARHEAD_PKG,
                    "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver",
                )
                action = "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
                putExtra("ip_address", "127.0.0.1")
                putExtra("projection_port", port)
                networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                fakeWifiInfo?.let { putExtra("wifi_info", it) }
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            app.sendBroadcast(receiverIntent)
            log("[AA] broadcast fallback sent")
        } catch (e: Exception) {
            log("[AA] broadcast fallback failed: ${e.message}")
        }
    }

    private fun fireHurProjection(app: Context, log: (String) -> Unit) {
        // AA 17.4+ / HUR — only with a real BT MAC (never dummy 00:11:22:33:44:55).
        val mac = pickProjectionMac(app, log) ?: run {
            log("[AA] skip START_WIRELESS_PROJECTION — no real BT MAC (grant Nearby / turn BT on)")
            return
        }
        try {
            val btReceiverIntent = Intent("com.google.android.projection.gearhead.START_WIRELESS_PROJECTION").apply {
                setClassName(
                    GEARHEAD_PKG,
                    "com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver",
                )
                putExtra("DEVICE_ADDRESS", mac)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            app.sendBroadcast(btReceiverIntent)
            log("[AA] broadcast fallback 2 (START_WIRELESS_PROJECTION mac=••:${mac.takeLast(5)}) sent")
        } catch (e: Exception) {
            log("[AA] broadcast fallback 2 failed: ${e.message} — is Android Auto installed & set up?")
        }
    }

    fun logGearheadVersion(context: Context, log: (String) -> Unit) {
        try {
            val pi = context.packageManager.getPackageInfo(GEARHEAD_PKG, 0)
            @Suppress("DEPRECATION")
            val ver = pi.versionName ?: "?"
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            log("[AA] gearhead versionName=$ver versionCode=$code")
        } catch (e: Exception) {
            log("[AA] gearhead not installed or unreadable: ${e.message}")
        }
    }

    /**
     * True when Connect/AA can attempt HUR (Nearby granted on API 31+, BT adapter on).
     * Older APIs only need BT enabled.
     */
    fun canAttemptHur(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = mgr?.adapter
            adapter != null && adapter.isEnabled
        } catch (_: Exception) {
            false
        }
    }

    /** Real bonded/connected MAC, or null if we must not send HUR (no CONNECT / BT off / none). */
    private fun pickProjectionMac(context: Context, log: (String) -> Unit): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log("[AA] no BLUETOOTH_CONNECT — HUR skipped (1.0-style Activity/Receiver only)")
            return null
        }
        return try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = mgr?.adapter
            if (adapter == null || !adapter.isEnabled) {
                log("[AA] Bluetooth off/unavailable — HUR skipped")
                return null
            }

            val a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP)
            val hfp = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
            val audioConnected =
                a2dp == BluetoothProfile.STATE_CONNECTED || hfp == BluetoothProfile.STATE_CONNECTED

            val bonded = try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }

            val savedSsids = savedBikeSsids(context)
            val candidates = bonded.map {
                dev.zanderp.opencfmoto.ProjectionHarness.BtDeviceCandidate(
                    address = it.address,
                    name = try { it.name } catch (_: SecurityException) { null },
                    connected = it.isConnectedCompat(),
                )
            }
            // Bike cluster only (connected first, then bonded). Never a smartwatch / earbuds /
            // random first bond — Gearhead would try to project to that device instead.
            val selected = dev.zanderp.opencfmoto.ProjectionHarness.selectBikeBluetoothMac(candidates, savedSsids)

            log(
                "[AA] SelfMode BT: bonded=${bonded.size} audioConnected=$audioConnected " +
                    "bikeSelected=${selected != null}",
            )
            selected
        } catch (e: Exception) {
            log("[AA] BT MAC lookup failed: ${e.message}")
            null
        }
    }

    private fun savedBikeSsids(context: Context): List<String> = try {
        val selected = dev.zanderp.opencfmoto.BikeMemory.selected(context)?.qr?.ssid
        val all = dev.zanderp.opencfmoto.BikeMemory.devices(context).mapNotNull { it.qr?.ssid }
        (listOfNotNull(selected) + all).distinct()
    } catch (_: Exception) {
        emptyList()
    }

    /** True when at least one bonded Bluetooth device matching a bike cluster is actively connected. */
    fun hasConnectedBluetooth(context: Context): Boolean {
        if (!canAttemptHur(context)) return false
        return try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = mgr?.adapter ?: return false
            val savedSsid = dev.zanderp.opencfmoto.BikeMemory.selected(context)?.qr?.ssid
            val allSsids = dev.zanderp.opencfmoto.BikeMemory.devices(context).mapNotNull { it.qr?.ssid }
            val bonded = adapter.bondedDevices?.toList().orEmpty()
            // Strict bike check: device must be actively connected AND match known bike names/SSID.
            // Never treat random accessories (smartwatches, fitness trackers, earbuds) as the motorcycle.
            bonded.any { it.isConnectedCompat() && isBikeDevice(it, savedSsid, allSsids) }
        } catch (_: Exception) {
            false
        }
    }

    fun isBikeDevice(
        device: BluetoothDevice,
        savedSsid: String? = null,
        allSavedSsids: List<String> = emptyList(),
    ): Boolean {
        val n = try { device.name } catch (_: SecurityException) { null }
        val ssids = if (savedSsid != null) listOf(savedSsid) + allSavedSsids else allSavedSsids
        return dev.zanderp.opencfmoto.ProjectionHarness.isBikeDeviceName(n, ssids)
    }

    private fun BluetoothDevice.isConnectedCompat(): Boolean = try {
        val m = javaClass.getMethod("isConnected")
        (m.invoke(this) as? Boolean) == true
    } catch (_: Exception) {
        false
    }

    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(netId)
            parcel.setDataPosition(0)
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as Parcelable
        } catch (_: Exception) {
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val wifiInfo = wifiInfoClass.getDeclaredConstructor().apply { isAccessible = true }
                .newInstance() as Parcelable
            try {
                wifiInfoClass.getDeclaredField("mSSID").apply { isAccessible = true }
                    .set(wifiInfo, "\"Headunit-Fake-Wifi\"")
            } catch (_: Exception) {
            }
            wifiInfo
        } catch (_: Exception) {
            null
        }
    }
}
