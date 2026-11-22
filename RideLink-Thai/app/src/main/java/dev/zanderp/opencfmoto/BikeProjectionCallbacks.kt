package dev.zanderp.opencfmoto

/** อายุ callback และ watchdog ของ Wi-Fi/PXC แยกจากอายุเซสชัน Head Unit */
internal class BikeProjectionCallbacks(
    private val post: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    class Attempt

    var current: Attempt? = null
        private set
    private var retry: Runnable? = null

    fun begin(): Attempt {
        end()
        return Attempt().also { current = it }
    }

    fun isCurrent(attempt: Attempt): Boolean = current === attempt

    fun dispatch(attempt: Attempt, action: () -> Unit) {
        post(Runnable { if (isCurrent(attempt)) action() })
    }

    fun scheduleRetry(attempt: Attempt, delayMs: Long, action: () -> Unit) {
        if (!isCurrent(attempt) || retry != null) return
        val task = object : Runnable {
            override fun run() {
                if (retry !== this || !isCurrent(attempt)) return
                retry = null
                action()
            }
        }
        retry = task
        postDelayed(task, delayMs)
    }

    fun cancelRetry() {
        retry?.let(remove)
        retry = null
    }

    fun end() {
        current = null
        cancelRetry()
    }
}
