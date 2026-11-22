package dev.zanderp.opencfmoto

/** เลือกว่าจะใช้การเชื่อมต่อเดิม ยกระดับสแตนด์บาย หรือเริ่มใหม่เมื่อค่ารถเปลี่ยน */
internal object SessionStartPolicy {
    enum class Action { REUSE_PROJECTION, PROMOTE_STANDBY, RESTART }

    fun keepHeadunitAfterSyncStop(projecting: Boolean, verifiedHeadunitLive: Boolean): Boolean =
        projecting && verifiedHeadunitLive

    fun stopHiddenStandby(running: Boolean, projecting: Boolean, appVisible: Boolean): Boolean =
        running && !projecting && !appVisible

    fun decide(
        requested: Boolean,
        running: Boolean,
        projecting: Boolean,
        receiverReady: Boolean,
        sameConfiguration: Boolean,
        error: Boolean,
        wifiPickerError: Boolean,
    ): Action {
        if (!requested || !running || !receiverReady || !sameConfiguration) return Action.RESTART
        if (!projecting) return Action.PROMOTE_STANDBY
        return if (!error || wifiPickerError) Action.REUSE_PROJECTION else Action.RESTART
    }
}
