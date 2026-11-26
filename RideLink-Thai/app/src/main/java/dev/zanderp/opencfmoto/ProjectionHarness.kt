package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Typed Decision & State Harness (Typesafe-AI Architecture) for RideLink TH.
 *
 * Centralizes noisy multi-transport signals (Bluetooth link, Zontes QR Wi-Fi AP, loopback :5288
 * AAP handshake, PXC :10920-10922 link, privacy masking, and Zontes TFT template safe-area math)
 * into deterministic, unit-testable typed judgments.
 */
object ProjectionHarness {

    /**
     * Grace window reserved for Android Auto's 127.0.0.1:5288 loopback handshake before binding
     * the app process to the bike's internet-less Wi-Fi interface.
     *
     * AaSelfMode escalates Activity (0ms) -> Receiver (900ms) -> HUR BT MAC (1800ms) + ~1.5s SSL.
     * 5500ms ensures Connect #2, #3, #N never lose the 127.0.0.1 loopback route when an
     * already-approved WifiNetworkSpecifier joins the bike AP in ~200ms.
     */
    const val LOOPBACK_GRACE_MS = 5_500L

    /** Cooldown between automatic QR-menu projection triggers to prevent rapid re-fire loops. */
    const val AUTO_TRIGGER_COOLDOWN_MS = 8_000L

    /** Recommended WVGA 800x480 DPI so AA renders 360dp+ height and never clips 3rd-row icons. */
    const val ZONTES_5INCH_RECOMMENDED_DPI = 135
    const val ZONTES_HD_RECOMMENDED_DPI = 160
    const val MIN_MANUAL_DPI = 100
    const val MAX_MANUAL_DPI = 220

    private val HEX_CARBIT_SSID_REGEX = Regex("^[0-9A-Fa-f]{16}$")

    // =========================================================================
    // 1. Auto-Connect Judgment (BT Paired + Zontes QR Menu Wi-Fi Gate)
    // =========================================================================

    enum class TriggerDecision {
        /** Bike QR menu Wi-Fi AP is live and all gates pass -> launch SessionService + AaSelfMode. */
        FIRE_PROJECTION,
        /** Ready in standby: watch passively for the rider to open the QR menu on the dash. */
        ARM_WIFI_WATCHER_ONLY,
        /** User explicitly pressed Stop during this ignition cycle; hold until manual Start or BT cycle. */
        HOLD_MANUAL_STOP,
        /** Session is already running. */
        HOLD_ALREADY_RUNNING,
        /** User disabled Auto-Connect in Settings. */
        HOLD_DISABLED,
        /** No saved bike SSID/QR yet. */
        HOLD_NO_SAVED_BIKE,
        /** Recently triggered; prevent duplicate bounce. */
        HOLD_COOLDOWN,
    }

    data class AutoTriggerInput(
        val autoConnectEnabled: Boolean,
        val savedSsid: String?,
        val sessionRunning: Boolean,
        val manuallyStopped: Boolean,
        val btBikeConnected: Boolean,
        val qrWifiDetected: Boolean,
        val msSinceLastTrigger: Long,
    )

    data class AutoTriggerJudgment(
        val decision: TriggerDecision,
        val reasonRes: Int,
        val maskedSsid: String? = null,
    )

    /**
     * Evaluates whether to auto-launch Android Auto projection.
     *
     * Crucial Rider Rule: Normal everyday riding connects Bluetooth to the Zontes TFT,
     * NOT Android Auto. Projection ONLY fires when [AutoTriggerInput.qrWifiDetected] is true
     * (which happens only after the rider holds SET on the Zontes handlebar to open the QR menu).
     */
    fun evaluateAutoTrigger(input: AutoTriggerInput): AutoTriggerJudgment {
        if (!input.autoConnectEnabled) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.HOLD_DISABLED,
                reasonRes = R.string.auto_disabled_reason,
            )
        }
        if (input.savedSsid.isNullOrBlank()) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.HOLD_NO_SAVED_BIKE,
                reasonRes = R.string.auto_no_wifi,
            )
        }
        if (input.sessionRunning) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.HOLD_ALREADY_RUNNING,
                reasonRes = R.string.auto_already_running,
            )
        }
        if (input.manuallyStopped) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.HOLD_MANUAL_STOP,
                reasonRes = R.string.auto_manually_stopped,
            )
        }
        if (input.msSinceLastTrigger in 0 until AUTO_TRIGGER_COOLDOWN_MS) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.HOLD_COOLDOWN,
                reasonRes = R.string.auto_cooldown,
            )
        }
        if (input.qrWifiDetected) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.FIRE_PROJECTION,
                reasonRes = R.string.auto_qr_found,
                maskedSsid = maskIdentifier(input.savedSsid, true),
            )
        }
        val standbyNote = if (input.btBikeConnected) {
            R.string.auto_wait_qr
        } else {
            R.string.auto_standby
        }
        return AutoTriggerJudgment(
            decision = TriggerDecision.ARM_WIFI_WATCHER_ONLY,
            reasonRes = standbyNote,
        )
    }

    // =========================================================================
    // 2. PXC vs Loopback Handoff Judgment (Fixes "Connects Only Once" Race)
    // =========================================================================

    enum class HandoffDecision {
        /** Safe to bind process to bike Wi-Fi and start EasyConnProber (:10920-10922). */
        START_PXC_NOW,
        /** Hold PXC process bind briefly so AA can connect to 127.0.0.1:5288 first. */
        DEFER_FOR_AA_LOOPBACK,
        /** Waiting for BikeWifi onAvailable(network). */
        WAIT_FOR_WIFI,
        /** EasyConnProber is already running on this exact Network instance. */
        ALREADY_ACTIVE,
    }

    fun evaluatePxcHandoff(
        hasBikeNetwork: Boolean,
        proberActiveOnSameNetwork: Boolean,
        aaSteadyOrDecoding: Boolean,
        elapsedSinceSessionStartMs: Long,
        headunitSessionLive: Boolean = false,
    ): HandoffDecision {
        if (!hasBikeNetwork) return HandoffDecision.WAIT_FOR_WIFI
        if (proberActiveOnSameNetwork) return HandoffDecision.ALREADY_ACTIVE
        if (headunitSessionLive || aaSteadyOrDecoding || elapsedSinceSessionStartMs >= LOOPBACK_GRACE_MS) {
            return HandoffDecision.START_PXC_NOW
        }
        return HandoffDecision.DEFER_FOR_AA_LOOPBACK
    }

    // =========================================================================
    // 3. Carbit / Zontes Identifier & Privacy Mask Judgment
    // =========================================================================

    /**
     * Real Zontes 150X / 350D Carbit units broadcast a 16-char hex HUID as their Wi-Fi SSID
     * (e.g. `A004DC0D30888555`) rather than `ZT_*`.
     */
    fun isZontesCarbitHexSsid(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        return HEX_CARBIT_SSID_REGEX.matches(ssid.trim())
    }

    /**
     * Masks a sensitive SSID or bike identifier (`A004DC0D30888555` -> `••••••••8555`)
     * when [masked] is true.
     */
    fun maskIdentifier(raw: String?, masked: Boolean): String {
        if (raw.isNullOrBlank()) return ""
        val clean = raw.trim()
        if (!masked) return clean
        return if (clean.length <= 4) {
            "••••"
        } else {
            "••••••••${clean.takeLast(4)}"
        }
    }

    /**
     * Sanitizes status/detail lines so a hidden SSID does not leak in the subtitle while masked.
     */
    fun sanitizeStatusText(text: String, savedSsid: String?, masked: Boolean): String {
        if (!masked || savedSsid.isNullOrBlank() || text.isBlank()) return text
        val cleanSsid = savedSsid.trim()
        if (!text.contains(cleanSsid, ignoreCase = true)) return text
        return text.replace(cleanSsid, maskIdentifier(cleanSsid, masked = true), ignoreCase = true)
    }

    // =========================================================================
    // 4. Viewport & Safe-Area Calibration Presets for Zontes Screen Templates
    // =========================================================================

    enum class SafeAreaPreset {
        /** 100% full panel, 0px margins. */
        FULL_PANEL,
        /** Balanced inset for Zontes OEM projection frame template (92% scale + top/bottom/side guard). */
        ZONTES_TEMPLATE_SAFE,
        /** Extra bottom/top clearance when OEM status bars are thick. */
        ZONTES_DEEP_FRAME,
    }

    data class SafeAreaConfig(
        val scalePercent: Int,
        val topPx: Int,
        val bottomPx: Int,
        val leftPx: Int,
        val rightPx: Int,
    )

    fun presetConfig(preset: SafeAreaPreset): SafeAreaConfig = when (preset) {
        SafeAreaPreset.FULL_PANEL -> SafeAreaConfig(
            scalePercent = 100,
            topPx = 0,
            bottomPx = 0,
            leftPx = 0,
            rightPx = 0,
        )
        SafeAreaPreset.ZONTES_TEMPLATE_SAFE -> SafeAreaConfig(
            scalePercent = 94,
            topPx = 24,
            bottomPx = 20,
            leftPx = 20,
            rightPx = 20,
        )
        SafeAreaPreset.ZONTES_DEEP_FRAME -> SafeAreaConfig(
            scalePercent = 88,
            topPx = 36,
            bottomPx = 32,
            leftPx = 32,
            rightPx = 32,
        )
    }

    fun clampManualDpi(dpi: Int): Int = dpi.coerceIn(MIN_MANUAL_DPI, MAX_MANUAL_DPI)

    // =========================================================================
    // 5. Head Unit Server (:5277) / Self-Mode (:5288) Connect Coordinator
    // =========================================================================
    //
    // Gearhead 17.9's DeveloperHeadUnitNetworkService treats EVERY accepted TCP socket on :5277 as
    // a real head unit session. A second socket while the previous one is still tearing down throws
    // `IllegalStateException: Already connected` and kills the whole `:projection` process — the
    // Head Unit Server then stays dead until the rider restarts it by hand in AA settings.
    // So: one socket at a time, a settle window after every teardown, and no throwaway probes.

    /** Settle window after we closed the previous session WITH an AAP ByeByeRequest. */
    const val SETTLE_AFTER_BYEBYE_MS = 650L
    /** Settle window after an ungraceful drop (EOF / failed handshake) — Gearhead lingers longer. */
    const val SETTLE_AFTER_UNGRACEFUL_MS = 1_500L
    /** Poll interval while a session is handshaking/live. */
    const val SESSION_HOLD_POLL_MS = 500L
    const val REFUSED_RETRY_FIRST_MS = 1_500L
    const val REFUSED_RETRY_MS = 2_000L
    const val HANDSHAKE_BACKOFF_BASE_MS = 1_000L
    const val REFUSALS_BEFORE_GUIDANCE = 5
    const val HANDSHAKE_FAILURES_BEFORE_GUIDANCE = 2

    enum class AaConnectAction {
        STOP,
        HOLD_SESSION_ACTIVE,
        WAIT_SETTLE_COOLDOWN,
        WAIT_FOR_HEADUNIT_RECHECK,
        DIAL_HEADUNIT_5277,
        TRIGGER_SELF_MODE_AND_RETRY_5277,
        SURFACE_HEADUNIT_ACTION_NEEDED,
    }

    data class AaConnectInput(
        val running: Boolean,
        /** A socket is claimed (handshaking) or an AAP session is live on :5277 or :5288. */
        val sessionBusy: Boolean,
        /** A ByeBye/close of the previous session is still running on a background thread. */
        val teardownInProgress: Boolean,
        /** Long.MAX_VALUE when there was no previous teardown in this process. */
        val msSinceLastTeardown: Long,
        val lastTeardownGraceful: Boolean,
        /** Long.MAX_VALUE when never dialled in this receiver. */
        val msSinceLastDial: Long,
        val consecutiveRefusals: Int,
        val consecutiveHandshakeFailures: Int,
        val selfModeFallbackTriggered: Boolean,
        val guidanceSurfaced: Boolean,
        val allowSelfModeFallback: Boolean = true,
    )

    data class AaConnectJudgment(
        val action: AaConnectAction,
        val delayMs: Long,
        val reasonRes: Int,
    )

    fun retryIntervalMs(consecutiveRefusals: Int, consecutiveHandshakeFailures: Int): Long {
        return when {
            consecutiveHandshakeFailures > 0 -> HANDSHAKE_BACKOFF_BASE_MS
            consecutiveRefusals <= 0 -> 0L
            consecutiveRefusals == 1 -> REFUSED_RETRY_FIRST_MS
            else -> REFUSED_RETRY_MS
        }
    }

    fun evaluateAaConnectStep(input: AaConnectInput): AaConnectJudgment {
        if (!input.running) {
            return AaConnectJudgment(AaConnectAction.STOP, 0L, R.string.headunit_stopped)
        }
        if (input.sessionBusy) {
            return AaConnectJudgment(
                AaConnectAction.HOLD_SESSION_ACTIVE, SESSION_HOLD_POLL_MS,
                R.string.headunit_already_connected,
            )
        }
        if (input.teardownInProgress) {
            return AaConnectJudgment(
                AaConnectAction.WAIT_SETTLE_COOLDOWN, 100L,
                R.string.headunit_closing,
            )
        }
        val settle = if (input.lastTeardownGraceful) SETTLE_AFTER_BYEBYE_MS else SETTLE_AFTER_UNGRACEFUL_MS
        if (input.msSinceLastTeardown in 0 until settle) {
            return AaConnectJudgment(
                AaConnectAction.WAIT_SETTLE_COOLDOWN, settle - input.msSinceLastTeardown,
                R.string.headunit_recovering,
            )
        }
        if (input.allowSelfModeFallback && input.consecutiveRefusals >= 1 && !input.selfModeFallbackTriggered) {
            return AaConnectJudgment(
                AaConnectAction.TRIGGER_SELF_MODE_AND_RETRY_5277, 0L,
                R.string.headunit_self_mode,
            )
        }
        if (!input.guidanceSurfaced) {
            val guidance = headunitGuidance(confirmedHeadunitFailure(input))
            if (guidance != null) {
                return AaConnectJudgment(AaConnectAction.SURFACE_HEADUNIT_ACTION_NEEDED, 0L, guidance)
            }
        }
        if (input.consecutiveHandshakeFailures >= HANDSHAKE_FAILURES_BEFORE_GUIDANCE) {
            return AaConnectJudgment(
                AaConnectAction.WAIT_FOR_HEADUNIT_RECHECK, 0L, R.string.headunit_wedged,
            )
        }
        val interval = retryIntervalMs(input.consecutiveRefusals, input.consecutiveHandshakeFailures)
        if (input.msSinceLastDial in 0 until interval) {
            return AaConnectJudgment(
                AaConnectAction.WAIT_SETTLE_COOLDOWN, interval - input.msSinceLastDial,
                R.string.headunit_wait_retry,
            )
        }
        return AaConnectJudgment(AaConnectAction.DIAL_HEADUNIT_5277, 0L, R.string.headunit_connecting)
    }

    // =========================================================================
    // 6. Server Status UI (derived from coordinator facts — ZERO socket probes)
    // =========================================================================

    /** Result of the current :5277 check. Failures become visible after repeated attempts. */
    enum class HeadunitLinkStatus { UNKNOWN, SERVER_REACHED, CONNECTED, REFUSED, HANDSHAKE_FAILED }

    fun confirmedHeadunitFailure(input: AaConnectInput): HeadunitLinkStatus = when {
        input.consecutiveHandshakeFailures >= HANDSHAKE_FAILURES_BEFORE_GUIDANCE ->
            HeadunitLinkStatus.HANDSHAKE_FAILED
        input.consecutiveRefusals >= REFUSALS_BEFORE_GUIDANCE -> HeadunitLinkStatus.REFUSED
        else -> HeadunitLinkStatus.UNKNOWN
    }

    fun headunitGuidance(status: HeadunitLinkStatus): Int? = when (status) {
        HeadunitLinkStatus.REFUSED -> R.string.headunit_refused
        HeadunitLinkStatus.HANDSHAKE_FAILED -> R.string.headunit_wedged
        else -> null
    }

    fun evaluateHeadunitHeader(
        setupComplete: Boolean,
        sessionRunning: Boolean,
        headunitLive: Boolean,
        androidAutoLive: Boolean,
        headunit: HeadunitLinkStatus,
        projectionRequested: Boolean = true,
    ): ServerUiJudgment = when {
        sessionRunning && headunitLive && !projectionRequested ->
            ServerUiJudgment(R.string.headunit_header_standby, UiLevel.OK)
        sessionRunning && headunitLive -> ServerUiJudgment(R.string.headunit_header_ready, UiLevel.OK)
        !setupComplete && !sessionRunning -> ServerUiJudgment(R.string.headunit_header_setup, UiLevel.WARN)
        !sessionRunning -> ServerUiJudgment(R.string.headunit_header_unverified, UiLevel.NEUTRAL)
        androidAutoLive -> ServerUiJudgment(R.string.headunit_header_fallback, UiLevel.WARN)
        headunit == HeadunitLinkStatus.REFUSED -> ServerUiJudgment(R.string.headunit_header_off, UiLevel.WARN)
        headunit == HeadunitLinkStatus.HANDSHAKE_FAILED -> ServerUiJudgment(R.string.headunit_header_failed, UiLevel.ERROR)
        headunit == HeadunitLinkStatus.SERVER_REACHED -> ServerUiJudgment(R.string.headunit_header_waiting_response, UiLevel.BUSY)
        else -> ServerUiJudgment(R.string.headunit_header_checking, UiLevel.BUSY)
    }

    enum class UiLevel { OK, BUSY, WARN, ERROR, NEUTRAL }

    data class TftSyncJudgment(
        val statusRes: Int,
        val hintRes: Int,
        val canStop: Boolean,
    )

    fun evaluateTftSyncUi(phase: Phase, sessionRunning: Boolean, hasSavedBikeWifi: Boolean): TftSyncJudgment {
        if (!sessionRunning && !hasSavedBikeWifi) {
            return TftSyncJudgment(R.string.tft_sync_setup, R.string.tft_sync_dashboard_hint, false)
        }
        if (phase == Phase.ERROR) {
            return TftSyncJudgment(R.string.conn_error, R.string.tft_sync_dashboard_hint, sessionRunning)
        }
        if (!sessionRunning) {
            val status = if (phase == Phase.STOPPED) R.string.conn_stopped else R.string.conn_ready
            return TftSyncJudgment(status, R.string.tft_sync_dashboard_hint, false)
        }
        return when (phase) {
            Phase.STREAMING -> TftSyncJudgment(R.string.tft_sync_sending, R.string.tft_sync_check_display, true)
            Phase.RECONNECTING, Phase.WAITING_FOR_BIKE ->
                TftSyncJudgment(phase.labelRes, R.string.tft_sync_wait_hint, true)
            else -> TftSyncJudgment(R.string.tft_sync_connecting, R.string.tft_sync_wait_hint, true)
        }
    }

    data class ServerUiJudgment(val textRes: Int, val level: UiLevel)

    fun evaluateServerStatusUi(
        sessionRunning: Boolean,
        handshakeLive: Boolean,
        decoding: Boolean,
        fps: Int,
        headunit: HeadunitLinkStatus,
    ): ServerUiJudgment {
        if (sessionRunning && (decoding || fps > 0)) {
            return ServerUiJudgment(R.string.headunit_live_badge, UiLevel.OK)
        }
        if (sessionRunning && handshakeLive) {
            return ServerUiJudgment(R.string.headunit_first_frame, UiLevel.OK)
        }
        return when (headunit) {
            HeadunitLinkStatus.REFUSED -> ServerUiJudgment(
                if (sessionRunning) R.string.headunit_refused_badge
                else R.string.headunit_prev_refused_badge,
                UiLevel.WARN,
            )
            HeadunitLinkStatus.HANDSHAKE_FAILED -> ServerUiJudgment(
                R.string.headunit_wedged_badge,
                UiLevel.ERROR,
            )
            else -> if (sessionRunning) {
                ServerUiJudgment(R.string.headunit_connecting_badge, UiLevel.BUSY)
            } else {
                ServerUiJudgment(R.string.headunit_standby_badge, UiLevel.NEUTRAL)
            }
        }
    }

    // =========================================================================
    // 7. Bike Bluetooth MAC selection (START_WIRELESS_PROJECTION)
    // =========================================================================

    data class BtDeviceCandidate(val address: String, val name: String?, val connected: Boolean)

    private val ACCESSORY_DENY = listOf(
        "watch", "buds", "airpods", "earbud", "headphone", "headset", "band", "fitbit", "garmin",
        "amazfit", "pods", "speaker", "keyboard", "mouse",
    )
    private val HEX_NAME_REGEX = Regex("^[0-9A-Fa-f]{8,16}$")
    private val BIKE_KEYWORDS = listOf(
        "Zontes", "Carbit", "EasyConn", "150", "310", "350", "368", "703", "CFMOTO", "CFDL", "Bike",
        "Moto", "VOGE", "QJ", "Benelli", "Morini", "Alltrhike", "Kove",
    )

    fun isBikeDeviceName(name: String?, savedSsids: List<String>): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        if (ACCESSORY_DENY.any { n.contains(it, ignoreCase = true) }) return false
        for (candidate in savedSsids) {
            val clean = candidate.trim()
            if (clean.isBlank()) continue
            if (n.contains(clean, ignoreCase = true)) return true
            if (clean.length >= 6 && n.contains(clean.takeLast(6), ignoreCase = true)) return true
            if (clean.length >= 4 && n.endsWith(clean.takeLast(4), ignoreCase = true)) return true
        }
        if (HEX_NAME_REGEX.matches(n)) return true
        if (n.startsWith("ZT", ignoreCase = true)) return true
        return BIKE_KEYWORDS.any { n.contains(it, ignoreCase = true) }
    }

    /** Connected bike cluster first, then a bonded bike cluster — never an arbitrary accessory. */
    fun selectBikeBluetoothMac(candidates: List<BtDeviceCandidate>, savedSsids: List<String>): String? {
        val bikes = candidates.filter { isBikeDeviceName(it.name, savedSsids) }
        return (bikes.firstOrNull { it.connected } ?: bikes.firstOrNull())?.address
    }
}

/**
 * Persistent preferences for Auto-Connect (BT + QR Wi-Fi trigger) and UI privacy masking.
 */
object AutoConnectPrefs {
    private const val PREFS = "ridelink_autoconnect_prefs"
    private const val KEY_AUTO_CONNECT = "auto_connect_qr_enabled"
    private const val KEY_PRIVACY_MASKED = "privacy_mask_bike_id"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAutoConnectEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnectEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    /** Default true ("ซ่อนรายละเอียด/กดเพื่อแสดง" per field UX requirement). */
    fun isPrivacyMasked(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PRIVACY_MASKED, true)

    fun setPrivacyMasked(ctx: Context, masked: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PRIVACY_MASKED, masked).apply()
    }
}
