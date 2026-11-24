package dev.zanderp.opencfmoto

/**
 * Pure-Kotlin link supervision and discovery gating policy for EasyConn/PXC sessions.
 *
 * Keeps all recovery thresholds, cooldowns, and Thinkerride scan exclusions behind a narrow
 * deterministic interface with zero Android framework dependencies.
 */
object PxcLinkSupervisor {
    const val DIALBACK_TIMEOUT_MS = 3_500L
    const val FIRST_FRAME_STALL_MS = 7_000L
    const val STREAM_STALL_MS = 12_000L
    const val RECOVERY_COOLDOWN_MS = 20_000L
    const val ERROR_REARM_MS = 15_000L

    enum class Action {
        NONE,
        REPROBE_DIALBACK_TIMEOUT,
        FORCE_RECONNECT_FIRST_FRAME_STALL,
        FORCE_RECONNECT_STREAM_STALL,
        REARM_FROM_ERROR,
    }

    data class HealthSnapshot(
        val running: Boolean,
        val inErrorState: Boolean,
        val probed: Boolean,
        val liveConns: Int,
        val framesSent: Int,
        val aaDecoding: Boolean,
        val msSinceProbeAccepted: Long,
        val msSinceFirstConnect: Long,
        val msSinceLastFrame: Long,
        val msSinceLastRecovery: Long,
    )

    /**
     * Decide whether the PXC link needs recovery action given monotonic durations in [snapshot].
     */
    fun evaluate(snapshot: HealthSnapshot): Action = when {
        !snapshot.running -> Action.NONE
        snapshot.inErrorState ->
            if (snapshot.msSinceLastRecovery >= ERROR_REARM_MS) Action.REARM_FROM_ERROR else Action.NONE
        snapshot.probed &&
            snapshot.liveConns == 0 &&
            snapshot.msSinceProbeAccepted >= DIALBACK_TIMEOUT_MS &&
            snapshot.msSinceLastRecovery >= DIALBACK_TIMEOUT_MS ->
            Action.REPROBE_DIALBACK_TIMEOUT
        snapshot.liveConns > 0 &&
            snapshot.framesSent == 0 &&
            snapshot.msSinceFirstConnect >= FIRST_FRAME_STALL_MS &&
            snapshot.msSinceLastRecovery >= FIRST_FRAME_STALL_MS ->
            Action.FORCE_RECONNECT_FIRST_FRAME_STALL
        snapshot.framesSent > 0 &&
            snapshot.aaDecoding &&
            snapshot.msSinceLastFrame >= STREAM_STALL_MS &&
            snapshot.msSinceLastRecovery >= RECOVERY_COOLDOWN_MS ->
            Action.FORCE_RECONNECT_STREAM_STALL
        else -> Action.NONE
    }

    /**
     * Suppress the 251-port Thinkerride/Kove discovery scan on known Zontes, CFMoto, Carbit, or
     * Morini dashboards so it never stalls `:10930` / NSD retries.
     */
    fun shouldRunThinkerrideScan(
        profile: BikeProfile,
        savedSsid: String?,
        qrModelId: String?,
    ): Boolean {
        if (!qrModelId.isNullOrBlank()) return false
        val isKnownBikeProfile = profile is ZontesProfile ||
            profile is ZontesHdProfile ||
            profile is Cfdl16MotoPlayLandscapeProfile ||
            profile is Cfdl26LandscapeProfile ||
            profile is Cfdl26PortraitProfile ||
            profile is Cfdl26NkTouchProfile ||
            profile is Nk800Profile ||
            profile is ClC450Profile ||
            profile is MoriniMlSoftApProfile ||
            profile is LearnedGeometryProfile
        if (isKnownBikeProfile) return false

        val ssid = savedSsid?.trim().orEmpty()
        if (ssid.isEmpty()) return true
        if (ProjectionHarness.isZontesCarbitHexSsid(ssid)) return false
        if (ssid.startsWith("ZT", ignoreCase = true) ||
            ssid.contains("ZONTES", ignoreCase = true) ||
            ssid.contains("CFMOTO", ignoreCase = true) ||
            ssid.startsWith("CFDL", ignoreCase = true) ||
            ssid.startsWith("MLN_", ignoreCase = true) ||
            ssid.startsWith("CARBIT", ignoreCase = true)
        ) {
            return false
        }
        return true
    }
}
