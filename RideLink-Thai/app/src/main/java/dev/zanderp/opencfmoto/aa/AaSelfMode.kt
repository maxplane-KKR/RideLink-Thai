// OpenCfMoto glue (technique ported from headunit-revived AGPLv3 AapService.startSelfMode).
// Triggers Google Android Auto's loopback "self-mode": asks gearhead to project to 127.0.0.1:PORT
// with NO VPN. Best launched from a foreground Activity to satisfy Android's background-activity-
// launch restrictions (Android 12+/15).
//
// Path (escalating — wait for :5288 accept / session between steps):
//  1) WirelessStartupActivity (1.0.x — works on older gearhead)
//  2) WirelessStartupReceiver (1.0.x / AA 16.4+ when activity is not exported)
// Callers must NOT re-fire this while a session is already live — that kills AAP.
package dev.zanderp.opencfmoto.aa

import android.content.Context
import android.content.Intent
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
