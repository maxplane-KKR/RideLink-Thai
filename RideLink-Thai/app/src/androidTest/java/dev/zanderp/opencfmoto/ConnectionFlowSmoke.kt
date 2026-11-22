package dev.zanderp.opencfmoto

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** ทดสอบตัวตัดสินและคิวลองใหม่บนมือถือ โดยไม่สั่งเชื่อม Wi-Fi หรือรถ */
object ConnectionFlowSmoke {
    @JvmStatic fun main(args: Array<String>) {
        var stage = "setup"
        val thread = HandlerThread("connection-smoke").apply { start() }
        try {
            val handler = Handler(thread.looper)
            val available = AtomicBoolean(false)
            val calls = AtomicInteger()
            val firstRetry = CountDownLatch(1)
            val scheduler = ConnectionRetryScheduler(
                post = { task, delay -> handler.postDelayed(task, delay) },
                remove = { task -> handler.removeCallbacks(task) },
                eligible = { !available.get() },
                retry = { calls.incrementAndGet(); firstRetry.countDown() },
            )

            stage = "manual_retry"
            scheduler.schedule(2_500L)
            val start = SystemClock.elapsedRealtime()
            check(scheduler.retryNow())
            check(firstRetry.await(1_500L, TimeUnit.MILLISECONDS))
            check(SystemClock.elapsedRealtime() - start < 1_500L)
            val settled = CountDownLatch(1)
            handler.postDelayed({ settled.countDown() }, 2_700L)
            check(settled.await(3_500L, TimeUnit.MILLISECONDS))
            check(calls.get() == 1)

            stage = "network_recovered"
            available.set(true)
            scheduler.schedule(0L)
            val afterRecovery = CountDownLatch(1)
            handler.post { afterRecovery.countDown() }
            check(afterRecovery.await(1_500L, TimeUnit.MILLISECONDS))
            check(calls.get() == 1)

            stage = "stop"
            available.set(false)
            scheduler.schedule(150L)
            scheduler.cancel()
            val afterStop = CountDownLatch(1)
            handler.postDelayed({ afterStop.countDown() }, 200L)
            check(afterStop.await(1_500L, TimeUnit.MILLISECONDS))
            check(calls.get() == 1)
            println("connection_flow_smoke=pass")
        } catch (error: Throwable) {
            println("connection_flow_smoke=failed:$stage:${error.javaClass.simpleName}")
            error.printStackTrace()
            exitProcess(1)
        } finally {
            thread.quitSafely()
        }
    }
}
