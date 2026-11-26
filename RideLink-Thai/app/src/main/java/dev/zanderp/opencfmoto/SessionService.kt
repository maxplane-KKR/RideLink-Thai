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
        const val ACTION_PREPARE_HEADUNIT = "app.ridelink.thai.ACTION_PREPARE_HEADUNIT"
        const val ACTION_STOP = "app.ridelink.thai.ACTION_STOP"
        const val ACTION_STOP_PROJECTION = "app.ridelink.thai.ACTION_STOP_PROJECTION"
        const val ACTION_LIVE_DASHBOARD = "app.ridelink.thai.ACTION_LIVE_DASHBOARD"
        const val EXTRA_LIVE_ATTEMPT = "app.ridelink.thai.EXTRA_LIVE_ATTEMPT"
        const val EXTRA_CONNECT_REQUESTED_AT = "app.ridelink.thai.EXTRA_CONNECT_REQUESTED_AT"
        const val EXTRA_FORCE_BIKE_RECONNECT = "app.ridelink.thai.EXTRA_FORCE_BIKE_RECONNECT"
        private const val CHANNEL_ID = "ridelink_service_channel"
        private const val NOTIFICATION_ID = 2026
        private const val HIDDEN_STANDBY_TIMEOUT_MS = 15_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile var isProjectionRequested: Boolean = false
            private set

        // Activity และ Service ใช้ main thread เดียวกัน ไม่เก็บ Activity ไว้ใน Service
        private var visibleActivities = 0
        private var activeService: SessionService? = null
        private var standbyStoppedInBackground = false

        internal fun onActivityStarted() {
            visibleActivities++
            activeService?.updateStandbyTimeout()
        }

        internal fun onActivityStopped() {
            visibleActivities = (visibleActivities - 1).coerceAtLeast(0)
            activeService?.updateStandbyTimeout()
        }

        internal fun consumeStandbyRestart(): Boolean {
            val restart = standbyStoppedInBackground
            standbyStoppedInBackground = false
            return restart
        }
    }

    private data class SessionConfiguration(
        val qr: QrData?,
        val profile: BikeProfile,
        val video: AaVideoSpec,
        val dpi: Int?,
        val margins: AaMargins,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val standbyTimeout = Runnable {
        if (SessionStartPolicy.stopHiddenStandby(isRunning, isProjectionRequested, visibleActivities > 0)) {
            standbyStoppedInBackground = true
            LogBus.log("[Session] แอปอยู่เบื้องหลังครบ 15 วินาที — หยุด Head Unit สแตนด์บาย")
            stopSession()
        }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pipeline: VideoPipeline? = null
    private var receiver: AaReceiver? = null
    private var prober: EasyConnProber? = null
    private var bikeNetwork: Network? = null
    private var proberActiveNetwork: Network? = null
    private var bikeConnectionStartedAtElapsed: Long? = null
    private var sessionGeneration: Int = 0
    private var proberGeneration = 0
    private var encoderCleanupGeneration = 0
    private val bikeCallbacks = BikeProjectionCallbacks(
        post = { mainHandler.post(it) },
        postDelayed = { task, delayMs -> mainHandler.postDelayed(task, delayMs) },
        remove = { mainHandler.removeCallbacks(it) },
    )
    private var currentConfiguration: SessionConfiguration? = null

    /**
     * Checks if the device is already associated with the bike's Wi-Fi hotspot or has a valid bike route/gateway.
     * Prevents stalls where Android Auto is running and Wi-Fi is connected, but WifiNetworkSpecifier didn't fire.
     */
    private fun findActiveBikeNetwork(targetSsid: String?): Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val targetClean = targetSsid?.trim('"', ' ')
        val allNets = cm.allNetworks

        // 0. Network ที่ BikeWifi ถืออยู่แล้วและพร้อมใช้งานจริง ให้ใช้ต่อทันที
        if (BikeWifi.isCurrentNetworkUsable(this)) {
            BikeWifi.currentNetwork?.let { return it }
        }

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
                    if (host.startsWith("192.168.3.") || host.startsWith("192.168.43.") || host.startsWith("192.168.50.") ||
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
        activeService = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            standbyStoppedInBackground = false
            stopSession()
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP_PROJECTION) {
            // กดหยุดครั้งเดียวหยุดทั้งเซสชัน (ไม่ค้างสแตนด์บาย)
            standbyStoppedInBackground = false
            stopSession()
            return START_NOT_STICKY
        }

        if (action == ACTION_LIVE_DASHBOARD) {
            val attemptId = intent?.getLongExtra(EXTRA_LIVE_ATTEMPT, 0L)?.takeIf { it > 0 }
                ?: LiveDashboardLogAttempt.newId()
            val attempt = LiveDashboardLogAttempt(attemptId, TftDiagnostics::recordLiveDashboard)
            attempt.event(LiveDashboardEvent.SERVICE_RECEIVED)
            if (!isRunning || !isProjectionRequested) {
                attempt.event(LiveDashboardEvent.SERVICE_NOT_READY)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            if (!RideScreenLiveDashboard.active) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    runCatching {
                        startForeground(
                            NOTIFICATION_ID,
                            buildNotification(localizedString(R.string.ridescreen_live_notification)),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                        )
                    }.onFailure { LogBus.log("[Session] GPS foreground unavailable: ${it.javaClass.simpleName}") }
                }
                RideScreenLiveDashboard.start(this, attemptId)
            } else {
                attempt.event(LiveDashboardEvent.ALREADY_ACTIVE)
            }
            return START_STICKY
        }

        if (action == ACTION_PREPARE_HEADUNIT) {
            standbyStoppedInBackground = false
            if (isRunning) {
                receiver?.requestHeadunitCheck()
                val detail = ConnectionState.detail
                if (!AaVideoBridge.aaSessionLive &&
                    (detail.isResource(R.string.headunit_refused) || detail.isResource(R.string.headunit_wedged))) {
                    ConnectionState.set(ConnectionState.phase, R.string.headunit_header_checking)
                }
            } else {
                startSession(projectionRequested = false)
            }
            updateStandbyTimeout()
            return START_NOT_STICKY
        }
        standbyStoppedInBackground = false
        encoderCleanupGeneration++
        val now = SystemClock.elapsedRealtime()
        val requestedAt = intent?.getLongExtra(EXTRA_CONNECT_REQUESTED_AT, now)
            ?.takeIf { it in 0..now && now - it <= 60_000L } ?: now
        val forceBikeReconnect = intent?.getBooleanExtra(EXTRA_FORCE_BIKE_RECONNECT, false) == true
        startSession(
            projectionRequested = true,
            requestedAtMs = requestedAt,
            forceBikeReconnect = forceBikeReconnect,
        )
        updateStandbyTimeout()
        return START_STICKY
    }

    private fun updateStandbyTimeout() {
        mainHandler.removeCallbacks(standbyTimeout)
        if (SessionStartPolicy.stopHiddenStandby(isRunning, isProjectionRequested, visibleActivities > 0)) {
            mainHandler.postDelayed(standbyTimeout, HIDDEN_STANDBY_TIMEOUT_MS)
        }
    }

    private fun sessionConfiguration(qr: QrData?): SessionConfiguration {
        ProfilePrefs.applyToHolder(this)
        val profile = BikeProfiles.selectByQr(qr, this)
        val video = VideoPrefs.resolutionOverride(this, profile)
            ?: DashMemory.specFor(this, qr?.ssid, profile)
            ?: profile.aaVideo
        val dpi = VideoPrefs.dpiOverride(this)
        val effectiveVideo = if (dpi == null || dpi == video.dpi) video else AaVideoSpec(video.resolution, dpi)
        val margins = VideoPrefs.aaMarginsFor(this, effectiveVideo, qr?.ssid, profile.panelSize)
        return SessionConfiguration(qr, profile, video, dpi, margins)
    }

    private fun ensureForeground(projectionRequested: Boolean) {
        // Always ensure Foreground notification is posted within Android's 5-second FGS deadline
        val notification = buildNotification(localizedString(
            if (projectionRequested) R.string.projection_starting else R.string.headunit_header_checking,
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSession(
        projectionRequested: Boolean,
        requestedAtMs: Long = SystemClock.elapsedRealtime(),
        forceBikeReconnect: Boolean = false,
    ) {
        if (!isRunning) ensureForeground(projectionRequested)
        try {
            ScreenMargins.load(this)
            val savedBike = BikeMemory.selected(this)
            val qr = savedBike?.qr
            val bikeName = savedBike?.name
            val config = sessionConfiguration(qr)
            val standbyReceiver = receiver
            val decision = SessionStartPolicy.decide(
                requested = projectionRequested,
                running = isRunning,
                projecting = isProjectionRequested,
                receiverReady = standbyReceiver != null,
                sameConfiguration = currentConfiguration == config,
                error = ConnectionState.phase == Phase.ERROR,
                wifiPickerError = ConnectionState.detail.isResource(R.string.conn_detail_wifi_picker),
                headunitLive = AaVideoBridge.headunitSessionLive,
                forceBikeReconnect = forceBikeReconnect,
            )
            if (decision == SessionStartPolicy.Action.REUSE_PROJECTION) {
                TftDiagnostics.markConnectionStartup(ConnectionStartupStage.REPEAT_REQUEST, requestedAtMs)
                if (BikeWifi.retryPendingJoinNow()) {
                    LogBus.log("[Session] มีคำขอเชื่อมซ้ำ — เริ่มลอง Wi-Fi ที่รออยู่ทันที")
                } else {
                    LogBus.log("[Session] มีคำขอเชื่อมซ้ำ — รอการเชื่อมต่อเดิมโดยไม่เริ่มใหม่")
                }
                return
            }
            if (isRunning) ensureForeground(projectionRequested)
            if (decision == SessionStartPolicy.Action.RESTART_BIKE_LINK) {
                // Head Unit :5277 ยังดีอยู่ — เริ่มใหม่เฉพาะลิงก์รถ (Wi-Fi + PXC) ไม่ทำลาย Android Auto
                LogBus.log("[Session] เริ่มลิงก์รถใหม่เฉพาะ Wi-Fi/PXC — คง Head Unit :5277 ไว้")
                resetBikeLinkOnly()
                TftDiagnostics.beginConnectionStartup(requestedAtMs)
                isProjectionRequested = true
                checkNotNull(standbyReceiver).allowSelfModeRecovery = true
                acquireLocks()
                publishProjectionStarting(bikeName, config.profile.panelSize ?: (config.video.width to config.video.height))
                TftDiagnostics.markConnectionStartup(ConnectionStartupStage.HEADUNIT_READY)
                startBikeConnection(sessionGeneration, qr)
                return
            }
            if (decision == SessionStartPolicy.Action.PROMOTE_STANDBY) {
                TftDiagnostics.beginConnectionStartup(requestedAtMs)
                isProjectionRequested = true
                checkNotNull(standbyReceiver).allowSelfModeRecovery = true
                if (!AaVideoBridge.headunitSessionLive) standbyReceiver.requestHeadunitCheck()
                acquireLocks()
                publishProjectionStarting(bikeName, config.profile.panelSize ?: (config.video.width to config.video.height))
                if (AaVideoBridge.headunitSessionLive) {
                    TftDiagnostics.markConnectionStartup(ConnectionStartupStage.HEADUNIT_READY)
                }
                startBikeConnection(sessionGeneration, qr)
                return
            }

            // Restart with the negotiated profile if the rider selected a different bike or changed settings.
            if (isRunning || receiver != null || prober != null || pipeline != null) {
                LogBus.log("[Session] รีเซ็ตเซสชันเดิมเพื่อเริ่มการเชื่อมต่อรอบใหม่อย่างสะอาด")
                teardownCore()
            }
            if (projectionRequested) TftDiagnostics.beginConnectionStartup(requestedAtMs)
            isProjectionRequested = projectionRequested
            isRunning = true
            val gen = ++sessionGeneration
            currentConfiguration = config
            BikeWifi.unbindProcess(context = this)
            AaVideoBridge.resetSessionState()
            AaVideoBridge.headunitStatus = ProjectionHarness.HeadunitLinkStatus.UNKNOWN
            if (projectionRequested) acquireLocks()

            val profile = config.profile
            BikeProfileHolder.active = profile
            BikeProfileHolder.aaVideoOverride = config.video
            BikeProfileHolder.aaDpiOverride = config.dpi
            BikeProfileHolder.aaContentMargins = config.margins

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
            RideScreenLiveDashboard.reattach(this)

            // 2. Register steady-video callback so PXC binds process network AFTER AA loopback connects
            AaVideoBridge.onSteadyVideo = {
                mainHandler.post {
                    if (gen == sessionGeneration && isRunning && isProjectionRequested) {
                        LogBus.log("[Session] AA ภาพสดนิ่งแล้ว — ส่งต่อสตรีมไปยังหน้าจอรถ (PXC)")
                        bikeCallbacks.current?.let(::maybeHandoffToPxc)
                    }
                }
            }

            // 3. Verify the head unit before requesting the bike network.
            val decoderSurface = pipeline?.decoderInputSurface() ?: pipeline?.encoderInputSurface()
            if (decoderSurface != null) {
                receiver = AaReceiver(
                    context = this,
                    encoderSurface = decoderSurface,
                    log = { LogBus.log("[AA] $it") }
                ).apply {
                    allowSelfModeRecovery = projectionRequested
                    onSessionEnded = { userExit ->
                        if (userExit) {
                            LogBus.log("[Session] ผู้ใช้กดออกจาก Android Auto — หยุดเซสชัน")
                            mainHandler.post {
                                if (gen == sessionGeneration && isRunning) stopSession()
                            }
                        } else {
                            // Temporary AA drop: the receiver's coordinator redials :5277 after the
                            // settle window and keeps :5288 open — no manual Stop → Connect needed.
                            LogBus.log("[Session] AA ตัดการเชื่อมต่อชั่วคราว — กำลังต่อ :5277 / :5288 ใหม่อัตโนมัติ")
                            mainHandler.post {
                                if (gen == sessionGeneration && isRunning && !AaVideoBridge.aaSessionLive) {
                                    val phase = if (isProjectionRequested) Phase.STARTING_AA else Phase.IDLE
                                    ConnectionState.set(phase, R.string.aa_auto_retry)
                                }
                            }
                        }
                    }
                    onHeadunitGuidance = { reasonRes ->
                        mainHandler.post {
                            if (gen == sessionGeneration && isRunning && !AaVideoBridge.aaSessionLive &&
                                ProjectionHarness.headunitGuidance(AaVideoBridge.headunitStatus) == reasonRes) {
                                val phase = if (isProjectionRequested) Phase.STARTING_AA else Phase.IDLE
                                ConnectionState.set(phase, reasonRes)
                            }
                        }
                    }
                    onHeadunitConnected = {
                        mainHandler.post {
                            if (gen != sessionGeneration || !isRunning || !AaVideoBridge.headunitSessionLive) return@post
                            if (isProjectionRequested) {
                                TftDiagnostics.markConnectionStartup(ConnectionStartupStage.HEADUNIT_READY)
                                startBikeConnection(gen, qr)
                                bikeCallbacks.current?.let(::maybeHandoffToPxc)
                            } else {
                                ConnectionState.set(Phase.IDLE, R.string.headunit_header_standby)
                                updateNotification(localizedString(R.string.headunit_header_standby))
                            }
                        }
                    }
                    onNeedSelfModeFallback = {
                        mainHandler.post {
                            if (gen == sessionGeneration && isRunning && isProjectionRequested) {
                                LogBus.log("[AA] ส่ง Intent/Broadcast เริ่ม Wireless Self-Mode สู่ :5288")
                                AaSelfMode.trigger(this@SessionService) { LogBus.log(it) }
                            }
                        }
                    }
                    start()
                }
            } else {
                error("Android Auto decoder surface is unavailable")
            }

            // Head Unit :5277 และ Wi-Fi รถเริ่มขนานกัน; PXC จะเริ่มเมื่อทั้งสองฝั่งพร้อม
            if (!projectionRequested) {
                ConnectionState.set(Phase.IDLE, R.string.headunit_header_checking)
            } else {
                publishProjectionStarting(bikeName, panel)
                startBikeConnection(gen, qr)
            }
        } catch (e: Exception) {
            LogBus.log("[Session] startSession error: $e")
            stopSession()
            ConnectionState.set(Phase.ERROR, e.message)
        }
    }

    private fun publishProjectionStarting(bikeName: String?, panel: Pair<Int, Int>) {
        val maskedLabel = bikeName?.let {
            ProjectionHarness.maskIdentifier(it, masked = AutoConnectPrefs.isPrivacyMasked(this))
        } ?: localizedString(R.string.bike_screen_name)
        if (bikeName == null) {
            ConnectionState.set(Phase.STARTING_AA, R.string.bike_screen_dimensions, panel.first, panel.second)
        } else {
            ConnectionState.set(Phase.STARTING_AA, "$maskedLabel (${panel.first}×${panel.second})")
        }
        updateNotification(localizedString(R.string.projection_active, maskedLabel, panel.first, panel.second))
    }

    private fun startBikeConnection(gen: Int, qr: QrData?) {
        // มี QR แล้วให้เข้า Wi-Fi รถขนานไปกับการต่อ :5277 ได้เลย (BikeWifi ไม่ bind โปรเซส) ส่วน PXC
        // ยังรอ Head Unit/AA พร้อมใน maybeHandoffToPxc
        if (gen != sessionGeneration || !isRunning || !isProjectionRequested ||
            bikeConnectionStartedAtElapsed != null || (qr == null && !AaVideoBridge.headunitSessionLive)
        ) return
        val attempt = bikeCallbacks.begin()
        bikeConnectionStartedAtElapsed = SystemClock.elapsedRealtime()
        TftDiagnostics.recordBikeConnectionStart(qr?.modelId)
        try {
            if (qr != null) {
                TftDiagnostics.markConnectionStartup(ConnectionStartupStage.WIFI_REQUESTED)
                BikeWifi.reuseOrJoin(
                    context = this,
                    ssid = qr.ssid,
                    psk = qr.pwd,
                    onAvailable = { net ->
                        bikeCallbacks.dispatch(attempt) {
                            TftDiagnostics.markConnectionStartup(ConnectionStartupStage.WIFI_AVAILABLE)
                            val isNewNet = bikeNetwork != net
                            bikeNetwork = net
                            if (isNewNet && proberActiveNetwork != null && proberActiveNetwork != net) {
                                // Re-acquired a fresh Wi-Fi Network mid-ride -> force PXC re-probe
                                proberActiveNetwork = null
                            }
                            if ((ConnectionState.phase == Phase.ERROR &&
                                    ConnectionState.detail.isResource(R.string.conn_detail_wifi_picker)) ||
                                (ConnectionState.phase == Phase.WAITING_FOR_BIKE &&
                                    ConnectionState.detail.isResource(R.string.conn_detail_wifi_searching))
                            ) {
                                ConnectionState.set(Phase.PXC_CONNECTING, "")
                            }
                            maybeHandoffToPxc(attempt)
                        }
                    },
                    onLost = {
                        bikeCallbacks.dispatch(attempt) {
                            bikeNetwork = null
                            proberActiveNetwork = null
                            try { prober?.stop() } catch (_: Exception) {}
                            prober = null
                            ConnectionState.set(Phase.RECONNECTING, R.string.wifi_auto_retry)
                        }
                    },
                    publishProjectionState = { update -> bikeCallbacks.dispatch(attempt, update) },
                    log = { LogBus.log("[WiFi] $it") }
                )
            } else {
                val preExisting = findActiveBikeNetwork(null)
                if (preExisting != null) {
                    LogBus.log("[Session] ตรวจพบ Wi-Fi รถเชื่อมต่ออยู่แล้ว: $preExisting")
                    bikeNetwork = preExisting
                } else {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    if (bikeNetwork == null) bikeNetwork = cm.activeNetwork
                }
            }
            maybeHandoffToPxc(attempt)
        } catch (e: Exception) {
            LogBus.log("[Session] startBikeConnection error: $e")
            ConnectionState.set(Phase.ERROR, e.message)
        }
    }

    /**
     * Consults [ProjectionHarness.evaluatePxcHandoff] before calling [startProber] so `127.0.0.1`
     * loopback AA startup is never severed by an instant pre-approved Wi-Fi join on 2nd+ connects.
     */
    private fun maybeHandoffToPxc(attempt: BikeProjectionCallbacks.Attempt) {
        if (!bikeCallbacks.isCurrent(attempt) || !isRunning || !isProjectionRequested) return
        val bikeStartedAt = bikeConnectionStartedAtElapsed ?: return

        // If bikeNetwork is null and no specifier picker/request is in flight, poll active Wi-Fi
        if (bikeNetwork == null && !BikeWifi.hasActiveSpecifierWait()) {
            val savedBike = BikeMemory.selected(this)
            val detected = findActiveBikeNetwork(savedBike?.qr?.ssid)
            if (detected != null) {
                LogBus.log("[Session] Watchdog ตรวจพบ Wi-Fi รถเชื่อมต่อแล้ว: $detected")
                bikeNetwork = detected
            }
        }

        val net = bikeNetwork
        // PXC ต้องรอ Head Unit/AA พร้อมก่อน (Wi-Fi อาจมาก่อนได้)
        if (net != null && !AaVideoBridge.headunitSessionLive && !AaVideoBridge.aaSessionLive) {
            bikeCallbacks.scheduleRetry(attempt, 500L) { maybeHandoffToPxc(attempt) }
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - bikeStartedAt
        val decision = ProjectionHarness.evaluatePxcHandoff(
            hasBikeNetwork = net != null,
            proberActiveOnSameNetwork = net != null && net == proberActiveNetwork && prober?.isRunning == true,
            aaSteadyOrDecoding = AaVideoBridge.aaDecoding || AaVideoBridge.currentFps > 0,
            elapsedSinceSessionStartMs = elapsed,
            headunitSessionLive = AaVideoBridge.headunitSessionLive,
        )

        when (decision) {
            ProjectionHarness.HandoffDecision.START_PXC_NOW -> {
                bikeCallbacks.cancelRetry()
                proberActiveNetwork = net
                TftDiagnostics.markConnectionStartup(ConnectionStartupStage.PXC_STARTED)
                startProber(net, attempt)
            }
            ProjectionHarness.HandoffDecision.DEFER_FOR_AA_LOOPBACK -> {
                val remain = (ProjectionHarness.LOOPBACK_GRACE_MS - elapsed).coerceAtLeast(250L)
                LogBus.log("[Harness] รอ AA เชื่อมต่อ Loopback :5288 ก่อนผูก Wi-Fi รถ (เหลือเวลา ${remain}ms)")
                bikeCallbacks.scheduleRetry(attempt, remain + 50L) { maybeHandoffToPxc(attempt) }
            }
            ProjectionHarness.HandoffDecision.WAIT_FOR_WIFI -> {
                // Poll every 1.5s until bike Wi-Fi appears instead of dropping into a dead black hole
                bikeCallbacks.scheduleRetry(attempt, 1500L) { maybeHandoffToPxc(attempt) }
            }
            ProjectionHarness.HandoffDecision.ALREADY_ACTIVE -> {
                bikeCallbacks.cancelRetry()
            }
        }
    }

    private fun startProber(network: Network?, attempt: BikeProjectionCallbacks.Attempt) {
        val generation = ++proberGeneration
        try {
            prober?.stop()
            val p = EasyConnProber(
                context = this,
                publishProjectionState = { update ->
                    bikeCallbacks.dispatch(attempt) {
                        if (generation == proberGeneration) update()
                    }
                },
                log = { LogBus.log("[PXC] $it") },
            )
            prober = p
            p.start(network)
            if (!p.isRunning && ConnectionState.phase != Phase.ERROR) {
                prober = null
                proberActiveNetwork = null
                bikeCallbacks.scheduleRetry(attempt, 1_000L) {
                    if (ConnectionState.phase != Phase.ERROR) {
                        maybeHandoffToPxc(attempt)
                    }
                }
            }
        } catch (e: Exception) {
            LogBus.log("[Session] startProber error: $e")
        }
    }

    /** รีเซ็ตเฉพาะลิงก์รถ (Wi-Fi/PXC) โดยไม่แตะ AaReceiver หรือ VideoPipeline */
    private fun resetBikeLinkOnly(rejoinWifi: Boolean = true) {
        encoderCleanupGeneration++
        bikeCallbacks.end()
        TftDiagnostics.stopConnectionStartup()
        proberActiveNetwork = null
        bikeConnectionStartedAtElapsed = null
        try { prober?.stop() } catch (_: Exception) {}
        prober = null
        if (rejoinWifi) {
            try {
                BikeWifi.leave(this) { LogBus.log("[WiFi] $it") }
            } catch (_: Exception) {}
        }
        bikeNetwork = null
    }

    private fun stopBikeProjection(): EasyConnProber? {
        encoderCleanupGeneration++
        bikeCallbacks.end()
        TftDiagnostics.stopConnectionStartup()
        bikeNetwork = null
        proberActiveNetwork = null
        bikeConnectionStartedAtElapsed = null

        val stoppedProber = prober
        try {
            stoppedProber?.stop()
        } catch (_: Exception) {}
        prober = null

        try {
            BikeWifi.leave(this) { LogBus.log("[WiFi] $it") }
        } catch (_: Exception) {}
        BikeWifi.unbindProcess(context = this)
        releaseLocks()
        return stoppedProber
    }

    private fun teardownCore() {
        stopBikeProjection()
        mainHandler.removeCallbacksAndMessages(null)
        currentConfiguration = null

        try {
            receiver?.stop()
            receiver = null
        } catch (_: Exception) {}

        try {
            pipeline?.stop()
            pipeline = null
            AaVideoBridge.pipeline = null
        } catch (_: Exception) {}

        AaVideoBridge.resetSessionState()
    }

    private fun stopSession() {
        isRunning = false
        isProjectionRequested = false
        sessionGeneration++
        RideScreenLiveDashboard.stop(this)
        teardownCore()
        ConnectionState.set(Phase.STOPPED, "")
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

    private fun localizedString(id: Int, vararg args: Any): String =
        androidx.core.content.ContextCompat.getContextForLanguage(this).getString(id, *args)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = localizedString(R.string.projection_channel)
            val descriptionText = localizedString(R.string.projection_channel_desc)
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
            .setContentTitle(localizedString(R.string.app_name))
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_play_vector)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop_vector, localizedString(R.string.headunit_stop_all), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        mainHandler.removeCallbacks(standbyTimeout)
        if (isRunning) {
            stopSession()
        }
        super.onDestroy()
    }
}
