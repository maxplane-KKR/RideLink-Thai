package dev.zanderp.opencfmoto

import android.content.Context
import androidx.annotation.StringRes

/**
 * Coarse, process-global view of what the app is currently doing, so the UI can show a single
 * human-readable status line (instead of making users read the raw log) and so the reconnect
 * logic ([EasyConnProber] / [MainActivity]) can react to link drops.
 *
 * Lives as a process global like [AaVideoBridge] / [BikeProfileHolder]: the foreground service,
 * the prober, and the activity all publish transitions here and the activity observes them.
 *
 * [labelRes] is what the UI shows (localized). [logLabel] stays English for shared logs / support.
 */
enum class Phase(@StringRes val labelRes: Int, val logLabel: String, val busy: Boolean) {
    IDLE(R.string.conn_ready, "Ready", false),
    STARTING_AA(R.string.conn_starting_aa, "Starting Android Auto…", true),
    AA_VIDEO_LIVE(R.string.conn_aa_video_live, "Android Auto live — joining bike…", true),
    JOINING_WIFI(R.string.conn_joining_wifi, "Connecting to bike Wi-Fi…", true),
    PXC_CONNECTING(R.string.conn_pxc_connecting, "Linking to dashboard…", true),
    STREAMING(R.string.conn_streaming, "Connected — projecting to dash", false),
    MIRRORING(R.string.conn_mirroring, "Mirroring screen to dash", false),
    RECONNECTING(R.string.conn_reconnecting, "Link dropped — reconnecting…", true),
    WAITING_FOR_BIKE(R.string.conn_waiting_for_bike, "Bike out of range — waiting…", true),
    STOPPED(R.string.conn_stopped, "Stopped", false),
    ERROR(R.string.conn_error, "Error — see logs", false),
}

object ConnectionState {
    data class ProjectionVersions(val client: String?, val sdk: String?)

    @Volatile
    var projectionVersions: ProjectionVersions? = null
        private set

    class Detail(private val raw: String = "", @StringRes private val res: Int = 0, private vararg val args: Any) {
        fun resolve(context: Context): String = if (res == 0) raw else context.getString(res, *args)
        fun isResource(@StringRes resource: Int): Boolean = res == resource
        fun diagnostic(): String = if (res == 0) raw else "detail resource 0x${res.toString(16)}"
        internal fun resourceId(): Int = res
    }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** Extra detail appended to the phase label (e.g. bike name, retry count). */
    @Volatile
    var detail = Detail()
        private set

    /** Observer (MainActivity) — receives (phase, detail) on every transition, on any thread. */
    @Volatile
    var listener: ((Phase, Detail) -> Unit)? = null

    /**
     * Move to [newPhase]. Pass [newDetail] to change the trailing detail, or leave it null to keep
     * the existing detail (e.g. the bike name set when the connection started, so a background
     * component can flip to [Phase.STREAMING] without knowing which bike it is).
     */
    fun set(newPhase: Phase, newDetail: String? = null) {
        publish(newPhase, newDetail?.let { Detail(it) })
    }

    fun set(newPhase: Phase, @StringRes detailRes: Int, vararg args: Any) {
        publish(newPhase, Detail(res = detailRes, args = args))
    }

    fun setProjectionStreaming(versions: ProjectionVersions?) {
        TftDiagnostics.markConnectionStartup(ConnectionStartupStage.FIRST_FRAME)
        projectionVersions = versions
        publish(Phase.STREAMING, null)
    }

    private fun publish(newPhase: Phase, newDetail: Detail?) {
        val streamingChanged = (phase == Phase.STREAMING) != (newPhase == Phase.STREAMING)
        if (newPhase != Phase.STREAMING) projectionVersions = null
        phase = newPhase
        if (streamingChanged) LiveObd.onProjectionStateChanged()
        if (newDetail != null) detail = newDetail
        val d = detail
        val diagnostic = d.diagnostic()
        TftDiagnostics.recordConnection(
            newPhase, newDetail?.resourceId()?.takeIf { it != 0 }, projectionVersions,
        )
        val text = if (diagnostic.isBlank()) newPhase.logLabel else "${newPhase.logLabel} — $diagnostic"
        LogBus.log("[state] $text")
        try {
            listener?.invoke(newPhase, d)
        } catch (_: Exception) {
        }
    }
}
