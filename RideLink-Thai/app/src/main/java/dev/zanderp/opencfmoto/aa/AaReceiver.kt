// OpenCfMoto glue (uses AGPLv3 code ported from headunit-revived). Orchestrates the loopback
// "self-mode" Android Auto Projection receiver:
//   1. Listen on TCP 127.0.0.1:5288 (+ NSD _aawireless._tcp).
//   2. Launch Google Android Auto's WirelessStartupActivity pointed at 127.0.0.1:5288 (no VPN).
//   3. Accept the inbound socket, run the AAP version+SSL handshake, point the H.264 decoder at
//      the supplied encoder Surface, and start the message loop → AA video flows into the encoder.
//
// RideLink TH v0.2.8: the head unit server (:5277) path is driven by ONE coordinator loop whose
// every step is a typed judgment from [ProjectionHarness.evaluateAaConnectStep]. It owns the only
// socket RideLink ever opens to :5277, waits a settle window after every teardown, survives failed
// handshakes and transient drops, and stop() closes with an AAP ByeByeRequest off the main thread.
package dev.zanderp.opencfmoto.aa

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.BikeWifi
import dev.zanderp.opencfmoto.NightPrefs
import dev.zanderp.opencfmoto.ProjectionHarness
import dev.zanderp.opencfmoto.ProjectionHarness.AaConnectAction
import dev.zanderp.opencfmoto.ProjectionHarness.HeadunitLinkStatus
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.Surface
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AaReceiver(
    private val context: Context,
    private val encoderSurface: Surface,
    private val log: (String) -> Unit,
) {
    companion object {
        const val PORT = 5288

        /**
         * Android Auto's own head unit server — the port the Desktop Head Unit talks to over
         * adb forward. AA 17.4 ships WirelessStartupReceiver with android:enabled="false" and the
         * activity it forwards to is not exported, so every AaSelfMode poke is swallowed silently:
         * result=0, no log, no error. This port is the path Google left open — the rider starts
         * it once from Android Auto's developer settings and gearhead listens here.
         */
        const val HEADUNIT_SERVER_PORT = 5277

        private const val DIAL_TIMEOUT_MS = 800

        // Process-wide teardown clock: survives Stop → Connect (a NEW AaReceiver instance), so the
        // next session never dials :5277 while Gearhead is still releasing the previous one.
        @Volatile private var lastTeardownElapsed: Long = -1L
        @Volatile private var lastTeardownGraceful: Boolean = true
        private val teardownsInFlight = AtomicInteger(0)

        private fun markTeardown(graceful: Boolean) {
            lastTeardownGraceful = graceful
            lastTeardownElapsed = SystemClock.elapsedRealtime()
        }
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var dialThread: Thread? = null
    @Volatile private var nightAutoThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile private var transport: AapTransport? = null
    @Volatile private var connection: SocketAccessoryConnection? = null
    @Volatile private var activeInput: AaInput? = null
    /**
     * Held between claiming the session slot and the session ending, so an inbound accept and
     * the head-unit-server dial-out can never both start a session.
     */
    @Volatile private var sessionStarting = false
    private val sessionLock = Any()
    @Volatile private var steadyVideoFired = false

    // Coordinator facts (fed into ProjectionHarness.evaluateAaConnectStep).
    @Volatile private var consecutiveRefusals = 0
    @Volatile private var consecutiveHandshakeFailures = 0
    @Volatile private var selfModeFallbackTriggered = false
    @Volatile private var headunitVerified = false
    @Volatile var allowSelfModeRecovery: Boolean = true
    @Volatile private var guidanceSurfaced = false

    /**
     * Fired when an AA session ends. [userExit] is true when the rider tapped Exit in Android Auto
     * (VIDEO_FOCUS_NATIVE) — the caller should fully stop projection so the dash doesn't freeze on the
     * last frame; false means a transient drop: the coordinator redials :5277 by itself.
     * Never fired for a local stop() or for a session that never finished its handshake.
     */
    @Volatile var onSessionEnded: ((userExit: Boolean) -> Unit)? = null

    /**
     * Fired when :5277 refuses, signalling that Wireless Projection (self-mode broadcast/intent to
     * :5288) should be triggered. When :5277 connects directly, this is NEVER invoked.
     */
    @Volatile var onNeedSelfModeFallback: (() -> Unit)? = null

    /** Thai rider guidance when the head unit server is off or wedged (coordinator keeps retrying). */
    @Volatile var onHeadunitGuidance: ((Int) -> Unit)? = null
    @Volatile var onHeadunitConnected: (() -> Unit)? = null

    private fun notifyVideoDecoding(fps: Int?, reason: String) {
        AaVideoBridge.aaDecoding = true
        if (fps != null) {
            AaVideoBridge.reportFps(fps)
        } else {
            try { AaVideoBridge.fpsListener?.invoke(AaVideoBridge.currentFps) } catch (_: Exception) {}
        }
        if (!steadyVideoFired) {
            steadyVideoFired = true
            log("[AA] steady video reached ($reason) — signalling ready for bike hand-off")
            try { AaVideoBridge.onSteadyVideo?.invoke() } catch (_: Exception) {}
        }
    }

    private val videoDecoder = VideoDecoder().apply {
        fallbackWidth = ServiceDiscoveryResponse.AA_WIDTH
        fallbackHeight = ServiceDiscoveryResponse.AA_HEIGHT
        onFpsChanged = { fps ->
            log("[AA] decode fps=$fps")
            if (fps >= 1) {
                notifyVideoDecoding(fps, "fps=$fps")
            } else {
                AaVideoBridge.reportFps(fps)
            }
        }
    }

    private fun openReusableServerSocket(port: Int): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port), 16)
        }

    /** Ensure Conscrypt/AAP logging are wired before anything touches SSL. */
    fun start() {
        if (running) { log("[AA] already running"); return }
        running = true
        steadyVideoFired = false
        consecutiveRefusals = 0
        consecutiveHandshakeFailures = 0
        selfModeFallbackTriggered = false
        headunitVerified = false
        guidanceSurfaced = false
        AaLog.sink = log
        ConscryptInitializer.initialize()

        // Always clear any stale bike Network process bind before opening loopback :5288.
        BikeWifi.unbindProcess(context = context)
        try {
            serverSocket = openReusableServerSocket(PORT)
            log("[AA] WirelessServer listening on :$PORT (SO_REUSEADDR)")
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            log("[AA] bind :$PORT retry after unbind ($msg)")
            BikeWifi.unbindProcess(context = context)
            try {
                Thread.sleep(150)
                serverSocket = openReusableServerSocket(PORT)
                log("[AA] WirelessServer listening on :$PORT (after retry)")
            } catch (e2: Exception) {
                log("[AA] fallback port :$PORT unavailable — continuing with :5277 (${e2.message})")
            }
        }

        if (serverSocket != null) {
            registerNsd()
            acceptThread = thread(name = "aa-accept", isDaemon = true) { acceptLoop() }
        }
        // Self-mode (launching Google Android Auto) is triggered via onNeedSelfModeFallback.
        // Those pokes never land on AA 17.4+, so the coordinator also dials OUT to :5277.
        dialThread = thread(name = "aa-coordinator", isDaemon = true) {
            try { ensureCoordinatorLoop() } catch (_: InterruptedException) {}
        }
    }

    /**
     * While this session is alive, re-evaluate the auto day/night value every minute and push it if it
     * changed — so a ride that crosses sunset flips the dash to dark on its own. Only [MapTheme.AUTO]
     * is time-driven; Day/Night are fixed and handled by the initial send + the UI toggle.
     */
    private fun startNightAutoLoop(t: AapTransport) = thread(name = "aa-night", isDaemon = true) {
        try {
            while (transport === t && running) {
                Thread.sleep(60_000)
                if (transport !== t) break
                val want = NightPrefs.isNightNow(context)
                if (want != t.nightMode) {
                    log("[AA] auto map theme → ${if (want) "night" else "day"}")
                    t.sendNightMode(want)
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun clearBridgeSession() {
        AaVideoBridge.aaSessionLive = false
        AaVideoBridge.headunitSessionLive = false
        AaVideoBridge.aaHandshaking = false
        AaVideoBridge.aaDecoding = false
        AaVideoBridge.reportFps(0)
        AaVideoBridge.touchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.voiceMicrophone = null
        AaVideoBridge.scrollSink = null
        AaVideoBridge.previewTouchSink = null
        AaVideoBridge.nightSink = null
    }

    /**
     * Non-blocking stop. The live transport is closed on a background thread with an AAP
     * ByeByeRequest (so Gearhead's :5277 releases its session instead of crashing with
     * "Already connected" on the next Connect); the teardown clock records when it finished.
     */
    fun stop() {
        val t: AapTransport?
        val c: SocketAccessoryConnection?
        synchronized(sessionLock) {
            running = false
            t = transport; transport = null
            c = connection; connection = null
            sessionStarting = false
        }
        AaSelfMode.cancel()
        clearBridgeSession()
        try { activeInput?.shutdown() } catch (_: Exception) {}
        activeInput = null
        videoDecoder.onFirstFrameListener = null
        try { videoDecoder.stop("AaReceiver.stop") } catch (_: Exception) {}
        unregisterNsd()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt(); acceptThread = null
        dialThread?.interrupt(); dialThread = null
        nightAutoThread?.interrupt(); nightAutoThread = null

        if (t != null) {
            teardownsInFlight.incrementAndGet()
            thread(name = "aa-teardown", isDaemon = true) {
                var graceful = false
                try {
                    graceful = t.stopGracefully()
                } catch (e: Exception) {
                    log("[AA] graceful stop error: ${e.message}")
                } finally {
                    try { c?.disconnect() } catch (_: Exception) {}
                    markTeardown(graceful)
                    teardownsInFlight.decrementAndGet()
                    log("[AA] session closed (ByeBye=${if (graceful) "sent" else "skipped"})")
                }
            }
        } else {
            try { c?.disconnect() } catch (_: Exception) {}
        }
        AaLog.sink = null
        log("[AA] receiver stopped")
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) log("[AA] accept ended: ${e.message}")
                break
            }
            log("[AA] <<< Android Auto connected from ${client.inetAddress?.hostAddress}")
            if (!headunitVerified || !allowSelfModeRecovery) {
                log("[AA] waiting for the first :5277 handshake before accepting self-mode")
                try { client.close() } catch (_: Exception) {}
                continue
            }
            if (!claimSession()) {
                log("[AA] already have a session (or stopped) — dropping extra connection")
                try { client.close() } catch (_: Exception) {}
                continue
            }
            thread(name = "aa-session", isDaemon = true) { handleConnection(client, viaHeadunit = false) }
        }
    }

    private fun handleConnection(client: Socket, viaHeadunit: Boolean) {
        val conn = SocketAccessoryConnection(client)
        val t = try {
            AapTransport(videoDecoder, context)
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            synchronized(sessionLock) { sessionStarting = false }
            if (viaHeadunit && running) AaVideoBridge.headunitStatus = HeadunitLinkStatus.HANDSHAKE_FAILED
            log("[AA] TLS initialization failed: ${e.javaClass.simpleName}")
            return
        }
        val input = AaInput(t, log)
        t.onQuit = { clean ->
            val userExit = t.wasUserExit
            val local = t.localShutdown || !running
            val wasLive = t.handshakeComplete
            log("[AA] transport quit (clean=$clean, userExit=$userExit, local=$local, live=$wasLive)")
            input.shutdown()
            t.microphone.stop()
            try { conn.disconnect() } catch (_: Exception) {}
            if (!local) {
                // Peer-side drop (EOF / failed handshake): Gearhead lingers — long settle window.
                markTeardown(graceful = false)
                var owned = false
                synchronized(sessionLock) {
                    if (transport === t) {
                        transport = null
                        connection = null
                        sessionStarting = false
                        owned = true
                    }
                }
                if (owned) {
                    clearBridgeSession()
                    videoDecoder.onFirstFrameListener = null
                    activeInput = null
                    // Let the coordinator start fresh: Self-Mode may be re-poked and guidance re-shown.
                    selfModeFallbackTriggered = false
                    guidanceSurfaced = false
                    if (wasLive) {
                        try { onSessionEnded?.invoke(userExit) } catch (_: Exception) {}
                    }
                }
            }
        }

        synchronized(sessionLock) {
            if (!running) {
                sessionStarting = false
                try { conn.disconnect() } catch (_: Exception) {}
                return
            }
            connection = conn
            transport = t
        }
        activeInput = input
        AaVideoBridge.aaHandshaking = true
        AaVideoBridge.aaDecoding = false
        steadyVideoFired = false

        // Bike touchscreen → Android Auto: EasyConnProber decodes dash touches (PXC cmdType 32) and
        // calls this sink with raw bike-canvas coords + a normalised action. Letterbox-map into AA
        // video space and forward over the AAP INPUT channel. Dropped if the point is in a black bar.
        var loggedTouchMap = false
        AaVideoBridge.touchSink = { action, pointerId, cx, cy ->
            val mapped = AaVideoBridge.pipeline?.mapBikeTouchToSource(cx, cy)
            if (mapped != null) {
                if (!loggedTouchMap || action != AaInput.ACTION_MOVE) {
                    log("[AA] touch action=$action p$pointerId bike=($cx,$cy) → AA=(${mapped.first},${mapped.second})")
                    loggedTouchMap = true
                }
                input.sendTouch(action, pointerId, mapped.first, mapped.second)
            }
        }

        // Phone/handlebar D-pad + rotary knob → Android Auto (drives a non-touch dash, and lets the
        // handlebar buttons navigate AA — see MediaButtonBridge). Cleared on transport quit / stop().
        AaVideoBridge.keySink = { keycode -> input.sendKey(keycode) }
        AaVideoBridge.voiceMicrophone = t.microphone
        AaVideoBridge.scrollSink = { delta -> input.sendScroll(delta) }

        // In-app HUD preview (HudViewActivity) touches — already in AA source space, sent as-is.
        AaVideoBridge.previewTouchSink = { action, pointerId, sx, sy -> input.sendTouch(action, pointerId, sx, sy) }

        // Map day/night → Android Auto NIGHT sensor (drives Maps' dark/light theme). Seed from the
        // user's Map theme setting, let the UI push live changes, and — in Auto mode — re-evaluate on
        // a timer so the dash follows nightfall mid-ride without any interaction.
        t.nightMode = NightPrefs.isNightNow(context)
        AaVideoBridge.nightSink = { on -> t.sendNightMode(on) }


        log("[AA] starting AAP handshake (version + SSL)…")
        if (!t.startHandshake(conn)) {
            // startHandshake() already ran quit() → onQuit cleaned up the slot.
            AaVideoBridge.aaHandshaking = false
            if (running) {
                consecutiveHandshakeFailures++
                if (viaHeadunit) AaVideoBridge.headunitStatus = HeadunitLinkStatus.HANDSHAKE_FAILED
                log("[AA] handshake FAILED (#$consecutiveHandshakeFailures) — coordinator will back off and retry")
            }
            return
        }
        if (!running || transport !== t) {
            AaVideoBridge.aaHandshaking = false
            return
        }
        // Only now is the session real: the UI and AaSelfMode may trust aaSessionLive.
        consecutiveHandshakeFailures = 0
        consecutiveRefusals = 0
        guidanceSurfaced = false
        if (viaHeadunit) headunitVerified = true
        AaVideoBridge.aaHandshaking = false
        AaVideoBridge.aaSessionLive = true
        AaVideoBridge.headunitSessionLive = viaHeadunit
        if (viaHeadunit) AaVideoBridge.headunitStatus = HeadunitLinkStatus.CONNECTED
        AaVideoBridge.aaSessionSeen = true
        nightAutoThread = startNightAutoLoop(t)
        log("[AA] handshake OK — pointing decoder at encoder surface and starting read loop")
        videoDecoder.onFirstFrameListener = {
            if (running && transport === t) {
                notifyVideoDecoding(null, "first frame")
            }
        }
        videoDecoder.setSurface(encoderSurface)
        t.startReading()
        if (viaHeadunit) onHeadunitConnected?.invoke()
        log("[AA] read loop started — expecting ServiceDiscovery then video")
    }

    /** Claim the single session slot; false when stopped, one is live, or another path got there first. */
    private fun claimSession(): Boolean = synchronized(sessionLock) {
        if (!running || sessionStarting || transport != null) false else { sessionStarting = true; true }
    }

    private fun sessionBusy(): Boolean = sessionStarting || transport != null

    private val coordinatorWakeLock = java.lang.Object()
    @Volatile private var coordinatorWakeRequested = false
    @Volatile private var dialNow = false

    /** ปลุก coordinator ให้ลอง :5277 ทันที (เช่นผู้ใช้กดเริ่มเชื่อมต่อระหว่างรอรอบถัดไป) */
    fun wakeCoordinator() {
        synchronized(coordinatorWakeLock) {
            coordinatorWakeRequested = true
            dialNow = true
            coordinatorWakeLock.notifyAll()
        }
    }

    private fun waitCoordinator(delayMs: Long) {
        synchronized(coordinatorWakeLock) {
            if (coordinatorWakeRequested) {
                coordinatorWakeRequested = false
                return
            }
            coordinatorWakeLock.wait(delayMs)
            coordinatorWakeRequested = false
        }
    }

    /**
     * The single owner of RideLink's :5277 traffic. Every iteration asks
     * [ProjectionHarness.evaluateAaConnectStep] what to do; the loop lives as long as the receiver,
     * so a failed handshake or a transient drop is retried automatically (no more Stop → Connect).
     * AAP roles never change — we are still the head unit; only the TCP direction flips.
     */
    private fun ensureCoordinatorLoop() {
        var lastDialAt = -1L
        while (running) {
            if (dialNow) { dialNow = false; lastDialAt = -1L }
            val now = SystemClock.elapsedRealtime()
            val tdAt = lastTeardownElapsed
            val judgment = ProjectionHarness.evaluateAaConnectStep(
                ProjectionHarness.AaConnectInput(
                    running = running,
                    sessionBusy = sessionBusy(),
                    teardownInProgress = teardownsInFlight.get() > 0,
                    msSinceLastTeardown = if (tdAt < 0) Long.MAX_VALUE else now - tdAt,
                    lastTeardownGraceful = lastTeardownGraceful,
                    msSinceLastDial = if (lastDialAt < 0) Long.MAX_VALUE else now - lastDialAt,
                    consecutiveRefusals = consecutiveRefusals,
                    consecutiveHandshakeFailures = consecutiveHandshakeFailures,
                    selfModeFallbackTriggered = selfModeFallbackTriggered,
                    guidanceSurfaced = guidanceSurfaced,
                    allowSelfModeFallback = headunitVerified && allowSelfModeRecovery,
                )
            )
            when (judgment.action) {
                AaConnectAction.STOP -> return
                AaConnectAction.HOLD_SESSION_ACTIVE,
                AaConnectAction.WAIT_SETTLE_COOLDOWN -> waitCoordinator(judgment.delayMs.coerceIn(50L, 2_000L))
                AaConnectAction.TRIGGER_SELF_MODE_AND_RETRY_5277 -> {
                    selfModeFallbackTriggered = true
                    log("[AA] ${androidx.core.content.ContextCompat.getContextForLanguage(context).getString(judgment.reasonRes)}")
                    try { onNeedSelfModeFallback?.invoke() } catch (_: Exception) {}
                }
                AaConnectAction.SURFACE_HEADUNIT_ACTION_NEEDED -> {
                    guidanceSurfaced = true
                    log("[AA] ${androidx.core.content.ContextCompat.getContextForLanguage(context).getString(judgment.reasonRes)}")
                    try { onHeadunitGuidance?.invoke(judgment.reasonRes) } catch (_: Exception) {}
                }
                AaConnectAction.DIAL_HEADUNIT_5277 -> {
                    lastDialAt = now
                    dialHeadunitOnce()
                }
            }
        }
    }

    /** One real AAP attempt on :5277 (never a throwaway probe). Runs the handshake inline. */
    private fun dialHeadunitOnce() {
        try {
            SingleKeyKeyManager.fromResources(context)
        } catch (e: Exception) {
            if (running) AaVideoBridge.headunitStatus = HeadunitLinkStatus.HANDSHAKE_FAILED
            log("[AA] Head Unit identity unavailable: ${e.javaClass.simpleName}")
            return
        }
        if (!running) return
        val sock = try {
            connectLoopback(HEADUNIT_SERVER_PORT)
        } catch (e: Exception) {
            consecutiveRefusals++
            if (!sessionBusy()) AaVideoBridge.headunitStatus = HeadunitLinkStatus.REFUSED
            if (consecutiveRefusals <= 2 || consecutiveRefusals == 5 || consecutiveRefusals == 10 || consecutiveRefusals % 30 == 0) {
                log(
                    "[AA] head unit server :$HEADUNIT_SERVER_PORT no answer " +
                        "(try $consecutiveRefusals) — ${e.javaClass.simpleName}: ${e.message}",
                )
            }
            null
        } ?: return

        if (!claimSession()) {
            // A :5288 session won the race (or we were stopped mid-connect).
            try { sock.close() } catch (_: Exception) {}
            return
        }
        consecutiveRefusals = 0
        log("[AA] >>> dialled OUT to Android Auto head unit server :$HEADUNIT_SERVER_PORT (AA 17.4 path)")
        handleConnection(sock, viaHeadunit = true)
    }

    /**
     * Loopback connect that survives the bike Wi-Fi bind. While the process is bound to the bike
     * [android.net.Network] there is no route to 127.0.0.1 — the same ENONET [start] already
     * works around for the ServerSocket. Drop the bind for the few ms the connect takes, then
     * restore the exact same Network. Established sockets ignore the process bind, so the bike link
     * never notices.
     */
    private fun connectLoopback(port: Int): Socket {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val bound = try { cm?.boundNetworkForProcess } catch (_: Exception) { null }
        if (bound != null) {
            // Unbind process immediately so 127.0.0.1 loopback routing is never intercepted by bike Wi-Fi AP.
            // Do NOT re-bind in finally: leaving process unbound ensures the AAP TLS handshake and packet
            // stream over 127.0.0.1 succeed without being killed mid-flight.
            try { cm?.bindProcessToNetwork(null) } catch (_: Exception) {}
        }
        return Socket().apply { connect(InetSocketAddress("127.0.0.1", port), DIAL_TIMEOUT_MS) }
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) { log("[AA] NSD unavailable"); return }
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless"
                serviceType = "_aawireless._tcp"
                port = PORT
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = log("[AA] NSD registered: ${info.serviceName}")
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD reg fail: $err")
                override fun onServiceUnregistered(info: NsdServiceInfo) = log("[AA] NSD unregistered")
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = log("[AA] NSD unreg fail: $err")
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            log("[AA] NSD register error: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
    }
}
