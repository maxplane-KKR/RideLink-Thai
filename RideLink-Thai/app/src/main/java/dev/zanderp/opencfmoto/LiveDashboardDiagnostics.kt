package dev.zanderp.opencfmoto

import java.util.concurrent.atomic.AtomicLong

internal enum class LiveDashboardEvent {
    REQUESTED, REJECTED_NO_PROJECTION, REJECTED_NOT_STREAMING, REJECTED_NO_PIPELINE,
    SERVICE_REQUEST_FAILED, SERVICE_RECEIVED, SERVICE_NOT_READY, ALREADY_ACTIVE, PIPELINE_MISSING,
    COMPOSITOR_MISSING, LAYER_ACCEPTED, FIRST_BITMAP, FIRST_ENCODER_SUBMISSION,
    RENDER_FAILED, GL_FAILED, SWAP_FAILED, GPS_REQUEST_FAILED, GPS_FRESH,
    VIDEO_SENT_WHILE_ACTIVE, REATTACH_REQUESTED, REATTACH_FAILED,
    SETTINGS_CHANGED, SETTINGS_LIMIT, USER_STOP_REQUESTED, SESSION_STOP_REQUESTED,
}

internal enum class LiveLayerEvent { COMPOSITED, RENDER_FAILED, GL_FAILED, SWAP_FAILED }

/** บันทึกเฉพาะจุดเปลี่ยน ไม่บันทึกภาพ พิกัด ความเร็ว หรือข้อมูลประจำตัว */
internal class LiveDashboardLogAttempt(
    val id: Long,
    private val write: (String) -> Unit,
) {
    private val emitted = mutableSetOf<LiveDashboardEvent>()
    private var finished = false
    private var changes = 0
    @Volatile private var waitingForVideo = false

    @Synchronized
    fun event(event: LiveDashboardEvent) = record(event)

    @Synchronized
    fun attached(settings: LiveDashboardSettings, gpsPermission: Boolean) {
        record(LiveDashboardEvent.LAYER_ACCEPTED, settingsDetails(settings) +
            " gps_permission=" + (if (gpsPermission) "granted" else "denied"))
    }

    @Synchronized
    fun bitmap(width: Int, height: Int, gpsFresh: Boolean) {
        record(LiveDashboardEvent.FIRST_BITMAP, "size=${width}x$height gps_fresh=$gpsFresh")
        if (gpsFresh) record(LiveDashboardEvent.GPS_FRESH)
    }

    @Synchronized
    fun settingsChanged(settings: LiveDashboardSettings) {
        if (finished) return
        if (changes < 12) {
            changes++
            write("live_dashboard=${LiveDashboardEvent.SETTINGS_CHANGED.name} attempt=$id ${settingsDetails(settings)}")
        } else {
            record(LiveDashboardEvent.SETTINGS_LIMIT)
        }
    }

    fun layerEvent(event: LiveLayerEvent) {
        when (event) {
            LiveLayerEvent.COMPOSITED -> event(LiveDashboardEvent.FIRST_ENCODER_SUBMISSION)
            LiveLayerEvent.RENDER_FAILED -> event(LiveDashboardEvent.RENDER_FAILED)
            LiveLayerEvent.GL_FAILED -> event(LiveDashboardEvent.GL_FAILED)
            LiveLayerEvent.SWAP_FAILED -> event(LiveDashboardEvent.SWAP_FAILED)
        }
    }

    fun videoSent() {
        if (waitingForVideo) event(LiveDashboardEvent.VIDEO_SENT_WHILE_ACTIVE)
    }

    @Synchronized
    fun stop(byUser: Boolean) {
        record(if (byUser) LiveDashboardEvent.USER_STOP_REQUESTED else LiveDashboardEvent.SESSION_STOP_REQUESTED)
        finished = true
        waitingForVideo = false
    }

    private fun record(event: LiveDashboardEvent, details: String = "") {
        if (finished || !emitted.add(event)) return
        if (event == LiveDashboardEvent.VIDEO_SENT_WHILE_ACTIVE) waitingForVideo = false
        if (event == LiveDashboardEvent.FIRST_ENCODER_SUBMISSION) waitingForVideo = true
        write("live_dashboard=${event.name} attempt=$id" + if (details.isEmpty()) "" else " $details")
    }

    private fun settingsDetails(settings: LiveDashboardSettings): String =
        "design=${settings.design.id} theme=${if (settings.light) "light" else "dark"} fields=${settings.visibleFields.size.coerceAtMost(8)}"

    companion object {
        private val nextId = AtomicLong(System.currentTimeMillis())
        fun newId(): Long = nextId.incrementAndGet()
    }
}
