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
        val shouldKeepPassiveWifiRequest: Boolean,
        val reasonTh: String,
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
                shouldKeepPassiveWifiRequest = false,
                reasonTh = "ปิดระบบเชื่อมต่ออัตโนมัติไว้ในหน้าตั้งค่า",
            )
        }
        if (input.savedSsid.isNullOrBlank()) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.HOLD_NO_SAVED_BIKE,
                shouldKeepPassiveWifiRequest = false,
                reasonTh = "ยังไม่มีข้อมูล Wi-Fi รถที่บันทึกไว้",
            )
        }
        if (input.sessionRunning) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.HOLD_ALREADY_RUNNING,
                shouldKeepPassiveWifiRequest = false,
                reasonTh = "ระบบกำลังทำงานอยู่แล้ว",
            )
        }
        if (input.manuallyStopped) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.HOLD_MANUAL_STOP,
                shouldKeepPassiveWifiRequest = false,
                reasonTh = "ผู้ใช้สั่งหยุดด้วยตนเอง รอจนกว่าจะกดเริ่มใหม่หรือดับเครื่องเปิดใหม่",
            )
        }
        if (input.msSinceLastTrigger in 0 until AUTO_TRIGGER_COOLDOWN_MS) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.HOLD_COOLDOWN,
                shouldKeepPassiveWifiRequest = false,
                reasonTh = "อยู่ในช่วงหน่วงเวลาป้องกันการสั่งซ้ำ",
            )
        }
        if (input.qrWifiDetected) {
            return AutoTriggerJudgment(
                decision = TriggerDecision.FIRE_PROJECTION,
                shouldKeepPassiveWifiRequest = false,
                reasonTh = "ตรวจพบสัญญาณ Wi-Fi หน้าเมนู QR ของรถ (${maskIdentifier(input.savedSsid, true)})",
            )
        }
        val standbyNote = if (input.btBikeConnected) {
            "ต่อ BT รถแล้ว — รอกดเมนู QR บนจอรถ"
        } else {
            "สแตนด์บายรอ BT และหน้าเมนู QR บนจอรถ"
        }
        return AutoTriggerJudgment(
            decision = TriggerDecision.ARM_WIFI_WATCHER_ONLY,
            shouldKeepPassiveWifiRequest = true,
            reasonTh = standbyNote,
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
    ): HandoffDecision {
        if (!hasBikeNetwork) return HandoffDecision.WAIT_FOR_WIFI
        if (proberActiveOnSameNetwork) return HandoffDecision.ALREADY_ACTIVE
        if (aaSteadyOrDecoding || elapsedSinceSessionStartMs >= LOOPBACK_GRACE_MS) {
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
        if (raw.isNullOrBlank()) return "ยังไม่ได้ระบุ"
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
