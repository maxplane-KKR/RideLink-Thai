package dev.zanderp.opencfmoto

import android.content.res.Resources
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** ตรวจการเก็บ log จริงในโฟลเดอร์ทดสอบ ไม่เชื่อมรถหรือใช้ GPS จริง */
object LiveDashboardLogSmoke {
    @JvmStatic fun main(args: Array<String>) {
        try {
            val directory = File(args.single())
            check(directory.isDirectory && !File(directory, "tft-diagnostics.txt").exists())
            TftDiagnostics.initialize(directory, Resources.getSystem())
            val attempt = LiveDashboardLogAttempt(987654, TftDiagnostics::recordLiveDashboard)
            attempt.event(LiveDashboardEvent.REQUESTED)
            attempt.attached(LiveDashboardSettings(design = LiveDashboardDesign.SPORT), false)
            repeat(500) { attempt.bitmap(800, 368, false) }
            attempt.layerEvent(LiveLayerEvent.COMPOSITED)
            repeat(500) { attempt.videoSent() }
            attempt.stop(byUser = true)
            attempt.layerEvent(LiveLayerEvent.GL_FAILED)
            val saved = runBlocking { TftDiagnostics.read() }
            val lines = saved.lineSequence().filter { it.contains("live_dashboard=") }.toList()
            check(lines.size == 6)
            check(lines.any { it.contains("FIRST_BITMAP") })
            check(lines.any { it.contains("FIRST_ENCODER_SUBMISSION") })
            check(lines.any { it.contains("VIDEO_SENT_WHILE_ACTIVE") })
            check(lines.none { it.contains("GL_FAILED") })
            val report = TftDiagnostics.formatQuickReport(saved, "", "test", "test", "test", null, null, "test", "test")
            check(report.contains("สถานะในแอป ไม่ยืนยันภาพบนจอรถ"))
            check(report.contains("VIDEO_SENT_WHILE_ACTIVE"))
            check(File(directory, "tft-diagnostics.txt").length() < 2_048)
            println("live_log_smoke=pass events=${lines.size}")
            exitProcess(0)
        } catch (error: Throwable) {
            println("live_log_smoke=failed:${error.javaClass.simpleName}")
            error.printStackTrace()
            exitProcess(1)
        }
    }
}
