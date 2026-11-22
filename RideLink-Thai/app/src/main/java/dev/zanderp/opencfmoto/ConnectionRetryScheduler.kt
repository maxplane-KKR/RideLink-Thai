package dev.zanderp.opencfmoto

/** จัดคิวลอง Wi-Fi ใหม่ โดยข้ามงานเก่าและเร่งเฉพาะงานที่รออยู่แล้ว */
internal class ConnectionRetryScheduler(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val eligible: () -> Boolean,
    private val retry: () -> Unit,
) {
    private var pending: Runnable? = null

    @Synchronized
    fun schedule(delayMs: Long) {
        pending?.let(remove)
        lateinit var task: Runnable
        task = Runnable {
            val claimed = synchronized(this) {
                if (pending !== task) false else {
                    pending = null
                    true
                }
            }
            if (claimed && eligible()) retry()
        }
        pending = task
        post(task, delayMs.coerceAtLeast(0L))
    }

    @Synchronized
    fun retryNow(): Boolean {
        val task = pending ?: return false
        if (!eligible()) return false
        remove(task)
        post(task, 0L)
        return true
    }

    @Synchronized
    fun cancel() {
        pending?.let(remove)
        pending = null
    }
}
