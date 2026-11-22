package dev.zanderp.opencfmoto

internal enum class ConnectionStartupStage {
    HEADUNIT_READY, WIFI_REQUESTED, WIFI_AVAILABLE, PXC_STARTED, FIRST_FRAME, REPEAT_REQUEST
}

/** เก็บเฉพาะเวลาของแต่ละขั้น ไม่เก็บข้อมูลรถ เครือข่าย หรือพิกัด */
internal class ConnectionStartupTiming(private val write: (String) -> Unit) {
    private var startedAt: Long? = null
    private val emitted = mutableSetOf<ConnectionStartupStage>()

    @Synchronized
    fun begin(nowMs: Long) {
        startedAt = nowMs
        emitted.clear()
        write("startup=requested elapsed_ms=0")
    }

    @Synchronized
    fun mark(stage: ConnectionStartupStage, nowMs: Long) {
        val start = startedAt ?: return
        if (!emitted.add(stage)) return
        write("startup=${stage.name.lowercase()} elapsed_ms=${(nowMs - start).coerceAtLeast(0L)}")
    }

    @Synchronized
    fun stop() {
        startedAt = null
        emitted.clear()
    }
}
