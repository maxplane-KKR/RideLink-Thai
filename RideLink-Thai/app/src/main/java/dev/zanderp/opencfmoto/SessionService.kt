package dev.zanderp.opencfmoto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dev.zanderp.opencfmoto.aa.AaReceiver
import dev.zanderp.opencfmoto.aa.AaSelfMode
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

class SessionService : Service() {

    companion object {
        const val ACTION_START = "app.ridelink.thai.ACTION_START"
        const val ACTION_STOP = "app.ridelink.thai.ACTION_STOP"
        private const val CHANNEL_ID = "ridelink_service_channel"
        private const val NOTIFICATION_ID = 2026

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pipeline: VideoPipeline? = null
    private var receiver: AaReceiver? = null
    private var prober: EasyConnProber? = null
    private var bikeNetwork: Network? = null
    private var proberActiveNetwork: Network? = null
    private var sessionStartedAtElapsed: Long = 0L
    private var sessionGeneration: Int = 0
    private var pxcRetryScheduled = false

    private fun schedulePxcRetry(gen: Int, delayMs: Long) {
        if (gen != sessionGeneration || !isRunning || pxcRetryScheduled) return
        pxcRetryScheduled = true
        mainHandler.postDelayed({
            pxcRetryScheduled = false
            if (gen == sessionGeneration && isRunning) {
                maybeHandoffToPxc(gen)
            }
        }, delayMs)
    }

    private fun stopPxcWatchdog() {
        pxcRetryScheduled = false
    }

    /**
     * Checks if the device is already associated with the bike's Wi-Fi hotspot or has a valid bike route/gateway.
     * Prevents stalls where Android Auto is running and Wi-Fi is connected, but WifiNetworkSpecifier didn't fire.
     */
    private fun findActiveBikeNetwork(targetSsid: String?): Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val targetClean = targetSsid?.trim('"', ' ')
        val allNets = cm.allNetworks

        // 1. Direct SSID match via NetworkCapabilities (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !targetClean.isNullOrBlank()) {
            for (net in allNets) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val wifiInfo = caps.transportInfo as? android.net.wifi.WifiInfo
                val currentSsid = wifiInfo?.ssid?.trim('"', ' ')
                if (currentSsid != null && !currentSsid.contains("unknown", ignoreCase = true) &&
                    currentSsid.equals(targetClean, ignoreCase = true)) {
                    return net
                }
            }
        }

        // 2. WifiManager connected SSID match fallback
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wmSsid = wm?.connectionInfo?.ssid?.trim('"', ' ')
        if (!targetClean.isNullOrBlank() && wmSsid != null && wmSsid.equals(targetClean, ignoreCase = true)) {
            val active = cm.activeNetwork
            if (active != null && cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return active
            }
            return allNets.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }

        // 3. Fallback: match typical bike hotspot gateways (192.168.43.1, 192.168.50.1, 192.168.18.1, etc.)
        for (net in allNets) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(net) ?: continue
            for (r in lp.routes) {
                val gw = r.gateway
                if (gw is Inet4Address && !gw.isAnyLocalAddress && !gw.isLoopbackAddress) {
                    val host = gw.hostAddress ?: ""
                    if (host.startsWith("192.168.43.") || host.startsWith("192.168.50.") ||
                        host.startsWith("192.168.18.") || host.startsWith("192.168.123.") ||
                        host.startsWith("192.168.49.")
                    ) {
                        return net
                    }
                }
            }
        }

        return null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopSession(manual = true)
            return START_NOT_STICKY
        }

        startSession()
        return START_STICKY
    }

    private fun startSession() {
        // Always ensure Foreground notification is posted within Android's 5-second FGS deadline
        val notification = buildNotification("กำลังเริ่มการฉายภาพ Android Auto...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Fix Bug #1 ("ต่อได้ครั้งเดียว พอครั้งต่อไปไม่ทำงาน"):
        // Never silently return if a previous session was still flagged running or half-alive.
        // Tear down sockets, unbind stale process network, reset session flags, and start clean.
        if (isRunning || receiver != null || prober != null || pipeline != null) {
            LogBus.log("[Session] รีเซ็ตเซสชันเดิมเพื่อเริ่มการเชื่อมต่อรอบใหม่อย่างสะอาด")
            teardownCore()
        }

        isRunning = true
        val gen = ++sessionGeneration
        sessionStartedAtElapsed = SystemClock.elapsedRealtime()
        BikeAutoWatcher.onManualStartOrSessionActive()
        BikeWifi.unbindProcess(context = this)
        AaVideoBridge.resetSessionState()
        acquireLocks()

        try {
            // Load user calibration settings (4-edge Zontes template margins, scale %, manual DPI)
            ScreenMargins.load(this)
            ProfilePrefs.applyToHolder(this)
            BikeProfileHolder.aaDpiOverride = VideoPrefs.dpiOverride(this)

            val savedBike = BikeMemory.selected(this)
            val qr = savedBike?.qr
            val bikeName = savedBike?.name ?: "จอรถ"

            val profile = BikeProfiles.selectByQr(qr, this)
            BikeProfileHolder.active = profile
            BikeProfileHolder.aaVideoOverride = VideoPrefs.resolutionOverride(this, profile)
                ?: DashMemory.specFor(this, qr?.ssid, profile)
                ?: profile.aaVideo
            BikeProfileHolder.aaContentMargins = VideoPrefs.aaMarginsFor(this, BikeProfileHolder.aaVideo, qr?.ssid)

            val panel = profile.panelSize ?: (BikeProfileHolder.aaVideo.width to BikeProfileHolder.aaVideo.height)
            LogBus.log(
                "[Session] Gen#$gen Profile=${profile.name} Panel=${panel.first}x${panel.second} " +
                    "AA=${BikeProfileHolder.aaVideo.width}x${BikeProfileHolder.aaVideo.height}@${BikeProfileHolder.aaVideo.dpi}dpi"
            )

            // 1. Start H.264 Compositor Video Pipeline & immediately reattach any visible phone SurfaceView
            pipeline = VideoPipeline(
                context = this,
                width = panel.first,
                height = panel.second,
                log = { LogBus.log("[Pipeline] $it") },
                externalSource = true,
                compositor = true
            ).apply {
                start()
            }
            AaVideoBridge.pipeline = pipeline
            AaVideoBridge.reattachActivePreview()

            // 2. Register steady-video callback so PXC binds process network AFTER AA loopback connects
            AaVideoBridge.onSteadyVideo = {
                mainHandler.post {
                    if (gen == sessionGeneration && isRunning) {
                        LogBus.log("[Session] AA ภาพสดนิ่งแล้ว — ส่งต่อสตรีมไปยังหน้าจอรถ (PXC)")
                        maybeHandoffToPxc(gen)
                    }
                }
            }

            // Safety fallback timer: hand off to PXC after LOOPBACK_GRACE_MS even if AA takes longer
            mainHandler.postDelayed({
                if (gen == sessionGeneration && isRunning) {
                    maybeHandoffToPxc(gen)
                }
            }, ProjectionHarness.LOOPBACK_GRACE_MS + 150L)

            // 3. Start Android Auto Receiver (:5288 bound synchronously BEFORE AaSelfMode pokes gearhead!)
            val decoderSurface = pipeline?.decoderInputSurface() ?: pipeline?.encoderInputSurface()
            if (decoderSurface != null) {
                receiver = AaReceiver(
                    context = this,
                    encoderSurface = decoderSurface,
                    log = { LogBus.log("[AA] $it") }
                ).apply {
                    onSessionEnded = { userExit ->
                        if (userExit) {
                            LogBus.log("[Session] ผู้ใช้กดออกจาก Android Auto — หยุดเซสชัน")
                            mainHandler.post { stopSession(manual = false) }
                        } else {
                            // Temporary AA drop: keep receiver listening for reconnect
                            LogBus.log("[Session] AA ตัดการเชื่อมต่อชั่วคราว — เปิดรับการเชื่อมต่อใหม่บน :5288")
                        }
                    }
                    onNeedSelfModeFallback = {
                        mainHandler.post {
                            if (isRunning && receiver != null) {
                                LogBus.log("[AA] ส่ง Intent/Broadcast เริ่ม Wireless Self-Mode สู่ :5288")
                                AaSelfMode.trigger(this@SessionService) { LogBus.log(it) }
                            }
                        }
                    }
                    start()
                }
            }

            // 4. Join Bike Wi-Fi (deferring PXC process bind until ProjectionHarness approves handoff)
            val maskedLabel = ProjectionHarness.maskIdentifier(
                bikeName,
                AutoConnectPrefs.isPrivacyMasked(this)
            )
            ConnectionState.set(Phase.STARTING_AA, "$bikeName (${panel.first}×${panel.second})")

            // Check immediately if device is already connected to bike Wi-Fi (manual connect / system auto-join)
            val preExisting = findActiveBikeNetwork(qr?.ssid)
            if (preExisting != null) {
                LogBus.log("[Session] ตรวจพบ Wi-Fi รถเชื่อมต่ออยู่แล้ว: $preExisting")
                bikeNetwork = preExisting
                maybeHandoffToPxc(gen)
            }

            if (qr != null) {
                BikeWifi.join(
                    context = this,
                    ssid = qr.ssid,
                    psk = qr.pwd,
                    onAvailable = { net ->
                        mainHandler.post {
                            if (gen != sessionGeneration || !isRunning) return@post
                            val isNewNet = bikeNetwork != net
                            bikeNetwork = net
                            if (isNewNet && proberActiveNetwork != null && proberActiveNetwork != net) {
                                // Re-acquired a fresh Wi-Fi Network mid-ride -> force PXC re-probe
                                proberActiveNetwork = null
                            }
                            maybeHandoffToPxc(gen)
                        }
                    },
                    onLost = {
                        mainHandler.post {
                            if (gen != sessionGeneration || !isRunning) return@post
                            bikeNetwork = null
                            proberActiveNetwork = null
                            ConnectionState.set(Phase.RECONNECTING, "สัญญาณ Wi-Fi รถหลุด กำลังต่อใหม่อัตโนมัติ...")
                        }
                    },
                    log = { LogBus.log("[WiFi] $it") }
                )
            } else {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                bikeNetwork = cm.activeNetwork
                maybeHandoffToPxc(gen)
            }

            updateNotification("กำลังฉายภาพ Android Auto สู่ $maskedLabel (${panel.first}×${panel.second})")
        } catch (e: Exception) {
            LogBus.log("[Session] startSession error: $e")
            ConnectionState.set(Phase.ERROR, e.message)
        }
    }

    /**
     * Consults [ProjectionHarness.evaluatePxcHandoff] before calling [startProber] so `127.0.0.1`
     * loopback AA startup is never severed by an instant pre-approved Wi-Fi join on 2nd+ connects.
     */
    private fun maybeHandoffToPxc(gen: Int) {
        if (gen != sessionGeneration || !isRunning) return

        // If bikeNetwork is null, check active Wi-Fi in background (watchdog polling)
        if (bikeNetwork == null) {
            val savedBike = BikeMemory.selected(this)
            val detected = findActiveBikeNetwork(savedBike?.qr?.ssid)
            if (detected != null) {
                LogBus.log("[Session] Watchdog ตรวจพบ Wi-Fi รถเชื่อมต่อแล้ว: $detected")
                bikeNetwork = detected
            }
        }

        val net = bikeNetwork
        val elapsed = SystemClock.elapsedRealtime() - sessionStartedAtElapsed
        val decision = ProjectionHarness.evaluatePxcHandoff(
            hasBikeNetwork = net != null,
            proberActiveOnSameNetwork = net != null && net == proberActiveNetwork && prober != null,
            aaSteadyOrDecoding = AaVideoBridge.aaDecoding || AaVideoBridge.currentFps > 0,
            elapsedSinceSessionStartMs = elapsed,
        )

        when (decision) {
            ProjectionHarness.HandoffDecision.START_PXC_NOW -> {
                stopPxcWatchdog()
                proberActiveNetwork = net
                startProber(net)
            }
            ProjectionHarness.HandoffDecision.DEFER_FOR_AA_LOOPBACK -> {
                val remain = (ProjectionHarness.LOOPBACK_GRACE_MS - elapsed).coerceAtLeast(250L)
                LogBus.log("[Harness] รอ AA เชื่อมต่อ Loopback :5288 ก่อนผูก Wi-Fi รถ (เหลือเวลา ${remain}ms)")
                schedulePxcRetry(gen, remain + 50L)
            }
            ProjectionHarness.HandoffDecision.WAIT_FOR_WIFI -> {
                // Poll every 1.5s until bike Wi-Fi appears instead of dropping into a dead black hole
                schedulePxcRetry(gen, 1500L)
            }
            ProjectionHarness.HandoffDecision.ALREADY_ACTIVE -> {
                stopPxcWatchdog()
            }
        }
    }

    private fun startProber(network: Network?) {
        try {
            prober?.stop()
            prober = EasyConnProber(this) { LogBus.log("[PXC] $it") }.apply {
                start(network)
            }
        } catch (e: Exception) {
            LogBus.log("[Session] startProber error: $e")
        }
    }

    private fun teardownCore() {
        stopPxcWatchdog()
        mainHandler.removeCallbacksAndMessages(null)
        bikeNetwork = null
        proberActiveNetwork = null

        try {
            prober?.stop()
            prober = null
        } catch (_: Exception) {}

        try {
            receiver?.stop()
            receiver = null
        } catch (_: Exception) {}

        try {
            pipeline?.stop()
            pipeline = null
            AaVideoBridge.pipeline = null
        } catch (_: Exception) {}

        try {
            BikeWifi.leave(this) { LogBus.log("[WiFi] $it") }
        } catch (_: Exception) {}

        BikeWifi.unbindProcess(context = this)
        AaVideoBridge.resetSessionState()
        releaseLocks()
    }

    private fun stopSession(manual: Boolean) {
        isRunning = false
        sessionGeneration++
        teardownCore()
        ConnectionState.set(Phase.STOPPED)
        if (!manual) {
            BikeAutoWatcher.onSessionStoppedNaturally(this)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideLink::WakeLock").apply {
                    acquire(12 * 60 * 60 * 1000L)
                }
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock?.isHeld != true) {
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RideLink::WifiLock").apply {
                    acquire()
                }
            }
        } catch (e: Exception) {
            LogBus.log("[Session] acquireLocks warning: $e")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "RideLink บริการฉายภาพ"
            val descriptionText = "การเชื่อมต่อ Android Auto สู่จอหน้าปัดรถยนต์และมอเตอร์ไซค์"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, SessionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RideLink TH")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_play_vector)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop_vector, "หยุดเชื่อมต่อ", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        if (isRunning) {
            stopSession(manual = false)
        }
        super.onDestroy()
    }
}
