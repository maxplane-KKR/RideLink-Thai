package dev.zanderp.opencfmoto

import android.os.SystemClock
import android.view.Surface

/**
 * Shared hand-off between the Android Auto receiver (which owns the H.264 encoder/[VideoPipeline]
 * fed by AA's decoded video), [MainActivity] (live preview + touch/stick controller), and
 * [EasyConnProber] (the bike PXC pipeline that pulls encoded frames).
 */
object AaVideoBridge {
    @Volatile var pipeline: VideoPipeline? = null

    /** Active phone preview Surface (Dashboard Tab 1 or Full AA View Tab 2). */
    @Volatile var activePreviewSurface: Surface? = null
        private set
    @Volatile var activePreviewW: Int = 0
        private set
    @Volatile var activePreviewH: Int = 0
        private set

    /**
     * Register the currently visible phone preview surface and attach it immediately if the
     * [VideoPipeline] is already running. If the pipeline starts later, [reattachActivePreview]
     * attaches this saved surface as soon as the compositor comes online.
     */
    @Synchronized
    fun bindPreview(surface: Surface, w: Int, h: Int) {
        if (!surface.isValid) return
        val pw = w.coerceAtLeast(320)
        val ph = h.coerceAtLeast(180)
        activePreviewSurface = surface
        activePreviewW = pw
        activePreviewH = ph
        pipeline?.setPreviewSurface(surface, pw, ph)
    }

    @Synchronized
    fun updatePreviewDimensions(surface: Surface, w: Int, h: Int) {
        if (surface !== activePreviewSurface || !surface.isValid) return
        val pw = w.coerceAtLeast(320)
        val ph = h.coerceAtLeast(180)
        activePreviewW = pw
        activePreviewH = ph
        pipeline?.updatePreviewSize(pw, ph)
    }

    /**
     * Detach only if [surface] is still the active preview (prevents Tab 1 surfaceDestroyed from
     * detaching Tab 2's newly-bound surface during a tab switch).
     */
    @Synchronized
    fun unbindPreview(surface: Surface? = null) {
        if (surface != null && surface !== activePreviewSurface) return
        activePreviewSurface = null
        activePreviewW = 0
        activePreviewH = 0
        pipeline?.clearPreviewSurface()
    }

    /** Called by [SessionService] right after creating a new [VideoPipeline]. */
    @Synchronized
    fun reattachActivePreview() {
        val s = activePreviewSurface
        if (s != null && s.isValid && activePreviewW > 0 && activePreviewH > 0) {
            pipeline?.setPreviewSurface(s, activePreviewW, activePreviewH)
        }
    }

    /** True while an AAP session is up on :5288 / :5277. */
    @Volatile var aaSessionLive: Boolean = false

    /** True if an AAP session came up at least once this Connect. */
    @Volatile var aaSessionSeen: Boolean = false

    /** True once we have decoded at least one AA video fps sample this session. */
    @Volatile var aaDecoding: Boolean = false

    /** Live decoded AA FPS for UI telemetry badge. */
    @Volatile var currentFps: Int = 0
    @Volatile var fpsListener: ((Int) -> Unit)? = null

    fun reportFps(fps: Int) {
        currentFps = fps
        try { fpsListener?.invoke(fps) } catch (_: Exception) {}
    }

    @Volatile private var aaAudioHoldUntilElapsed = 0L

    fun noteAaAudioActive(holdMs: Long = 5_000L) {
        val until = SystemClock.elapsedRealtime() + holdMs
        if (until > aaAudioHoldUntilElapsed) aaAudioHoldUntilElapsed = until
    }

    fun isAaAudioHoldActive(): Boolean =
        SystemClock.elapsedRealtime() < aaAudioHoldUntilElapsed

    /** Fired once when decoded Android Auto video reaches steady playback. */
    @Volatile var onSteadyVideo: (() -> Unit)? = null

    /** Bike-touchscreen → Android Auto input bridge. */
    @Volatile var touchSink: ((action: Int, pointerId: Int, canvasX: Int, canvasY: Int) -> Unit)? = null

    /** In-app phone preview → Android Auto input bridge (coordinates in AA coded source space). */
    @Volatile var previewTouchSink: ((action: Int, pointerId: Int, sourceX: Int, sourceY: Int) -> Unit)? = null

    /** Phone/handlebar D-pad & Virtual Stick → Android Auto input bridge. */
    @Volatile var keySink: ((keycode: Int) -> Unit)? = null

    /** Rotary-knob → Android Auto bridge (-1 = prev, +1 = next). */
    @Volatile var scrollSink: ((delta: Int) -> Unit)? = null

    /** Map day/night → Android Auto bridge. */
    @Volatile var nightSink: ((isNight: Boolean) -> Unit)? = null

    /** Reset all transient session flags cleanly between reconnects. */
    fun resetSessionState() {
        aaSessionLive = false
        aaSessionSeen = false
        aaDecoding = false
        currentFps = 0
        onSteadyVideo = null
        touchSink = null
        previewTouchSink = null
        keySink = null
        scrollSink = null
        nightSink = null
        try { fpsListener?.invoke(0) } catch (_: Exception) {}
    }
}
