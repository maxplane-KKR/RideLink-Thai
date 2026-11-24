package dev.zanderp.opencfmoto

/**
 * จุดตัดสินใจเดียวของวงจรเซสชัน: เริ่มใหม่/ใช้ของเดิม/ยกระดับสแตนด์บาย และสถานะปุ่มหลัก (เชื่อมต่อ ⇄ หยุด)
 */
internal object SessionStartPolicy {
    enum class Action { REUSE_PROJECTION, PROMOTE_STANDBY, RESTART_BIKE_LINK, RESTART }

    enum class ToggleCommand { START_CONNECT, STOP_SESSION }

    data class ToggleUiState(
        val command: ToggleCommand,
        val textRes: Int,
        val backgroundRes: Int,
        val iconRes: Int,
    )

    /** ผู้ใช้กดหยุดครั้งเดียวต้องหยุดทั้งเซสชัน ไม่ค้างสแตนด์บายให้กดซ้ำ */
    @Suppress("UNUSED_PARAMETER")
    fun keepHeadunitAfterSyncStop(projecting: Boolean, verifiedHeadunitLive: Boolean): Boolean = false

    fun stopHiddenStandby(running: Boolean, projecting: Boolean, appVisible: Boolean): Boolean =
        running && !projecting && !appVisible

    /** ปุ่มหลักเดียว: กำลังเชื่อม/ฉายอยู่ → หยุด, นอกนั้น → เริ่มเชื่อมต่อ */
    fun evaluateToggleControl(running: Boolean, projecting: Boolean, phase: Phase): ToggleUiState {
        val active = projecting ||
            (running && (phase.busy || phase == Phase.STREAMING || phase == Phase.MIRRORING))
        return if (active) {
            ToggleUiState(
                ToggleCommand.STOP_SESSION,
                R.string.disconnect,
                R.drawable.btn_liquid_ruby,
                R.drawable.ic_stop_vector,
            )
        } else {
            ToggleUiState(
                ToggleCommand.START_CONNECT,
                R.string.connect_start,
                R.drawable.btn_liquid_emerald,
                R.drawable.ic_play_vector,
            )
        }
    }

    fun decide(
        requested: Boolean,
        running: Boolean,
        projecting: Boolean,
        receiverReady: Boolean,
        sameConfiguration: Boolean,
        error: Boolean,
        wifiPickerError: Boolean,
        headunitLive: Boolean = true,
        forceBikeReconnect: Boolean = false,
    ): Action {
        if (!requested || !running || !receiverReady || !sameConfiguration) return Action.RESTART
        if (!projecting) return Action.PROMOTE_STANDBY
        if (forceBikeReconnect) {
            return if (headunitLive) Action.RESTART_BIKE_LINK else Action.RESTART
        }
        if (!error || wifiPickerError) return Action.REUSE_PROJECTION
        return if (headunitLive) Action.RESTART_BIKE_LINK else Action.RESTART
    }
}
