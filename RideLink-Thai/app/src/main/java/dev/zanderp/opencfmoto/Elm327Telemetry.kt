package dev.zanderp.opencfmoto

import android.os.SystemClock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal data class ObdSample(
    val rpm: Double? = null,
    val coolant: Double? = null,
    val volt: Double? = null,
    val load: Double? = null,
    val intake: Double? = null,
    val manifoldPressureKpa: Double? = null,
    val relativeThrottle: Double? = null,
    val readAtMs: Map<ObdPid, Long> = emptyMap(),
) {
    val hasData: Boolean get() = rpm != null || coolant != null || volt != null || load != null ||
        intake != null || manifoldPressureKpa != null || relativeThrottle != null

    fun withReading(pid: ObdPid, value: Double?, nowMs: Long): ObdSample {
        val times = if (value == null) readAtMs - pid else readAtMs + (pid to nowMs)
        return when (pid) {
            ObdPid.RPM -> copy(rpm = value, readAtMs = times)
            ObdPid.COOLANT -> copy(coolant = value, readAtMs = times)
            ObdPid.VOLTAGE -> copy(volt = value, readAtMs = times)
            ObdPid.LOAD -> copy(load = value, readAtMs = times)
            ObdPid.INTAKE -> copy(intake = value, readAtMs = times)
            ObdPid.MAP -> copy(manifoldPressureKpa = value, readAtMs = times)
            ObdPid.RELATIVE_THROTTLE -> copy(relativeThrottle = value, readAtMs = times)
        }
    }

    fun freshAt(nowMs: Long): ObdSample {
        if (readAtMs.isEmpty()) return this
        fun fresh(pid: ObdPid, value: Double?, maxAgeMs: Long): Double? = value?.takeIf {
            val readAt = readAtMs[pid] ?: return@takeIf false
            nowMs - readAt in 0..maxAgeMs
        }
        return copy(
            rpm = fresh(ObdPid.RPM, rpm, 5_000L),
            coolant = fresh(ObdPid.COOLANT, coolant, 10_000L),
            volt = fresh(ObdPid.VOLTAGE, volt, 10_000L),
            load = fresh(ObdPid.LOAD, load, 10_000L),
            intake = fresh(ObdPid.INTAKE, intake, 10_000L),
            manifoldPressureKpa = fresh(ObdPid.MAP, manifoldPressureKpa, 10_000L),
            relativeThrottle = fresh(ObdPid.RELATIVE_THROTTLE, relativeThrottle, 10_000L),
        )
    }

    fun applyTo(data: LiveDashboardData, nowMs: Long? = null): LiveDashboardData {
        val sample = if (readAtMs.isEmpty()) this else freshAt(nowMs ?: SystemClock.elapsedRealtime())
        return data.copy(rpm = sample.rpm, coolant = sample.coolant, volt = sample.volt,
            load = sample.load, intake = sample.intake, manifoldPressureKpa = sample.manifoldPressureKpa,
            relativeThrottle = sample.relativeThrottle, obdLive = sample.hasData)
    }
}

internal enum class ObdPid(val code: String, private val bytes: Int) {
    RPM("0C", 2), COOLANT("05", 1), VOLTAGE("42", 2), LOAD("04", 1),
    INTAKE("0F", 1), MAP("0B", 1), RELATIVE_THROTTLE("45", 1);

    fun decode(response: String): Double? {
        val value = mode01Values(response, code, bytes).firstOrNull() ?: return null
        return when (this) {
            RPM -> value / 4.0
            COOLANT, INTAKE -> value - 40.0
            VOLTAGE -> value / 1000.0
            LOAD, RELATIVE_THROTTLE -> value * 100.0 / 255.0
            MAP -> value.toDouble()
        }
    }
}

internal data class ObdInventory(
    val pids: List<Int>,
    val missingRange: Int? = null,
    val unqueriedRange: Int? = null,
) {
    val incomplete: Boolean get() = missingRange != null || unqueriedRange != null

    fun secondaryPids(): List<ObdPid> = listOf(
        ObdPid.COOLANT, ObdPid.VOLTAGE, ObdPid.LOAD,
        ObdPid.INTAKE, ObdPid.MAP, ObdPid.RELATIVE_THROTTLE,
    ).filter { pid ->
        pid.code.toInt(16) in pids ||
            (missingRange == 0 && (pid == ObdPid.COOLANT || pid == ObdPid.VOLTAGE))
    }

    /** รวมเฉพาะหลักฐานที่อ่านจากเซสชันเดียวกัน และคงช่วงที่เคยอ่านได้แล้ว */
    fun merge(other: ObdInventory): ObdInventory {
        val mergedPids = (pids + other.pids).distinct().sorted()
        val currentLimit = missingRange ?: unqueriedRange
        val otherLimit = other.missingRange ?: other.unqueriedRange
        if (currentLimit == null || otherLimit == null) return ObdInventory(mergedPids)
        val best = when {
            currentLimit > otherLimit -> this
            otherLimit > currentLimit -> other
            missingRange != null -> this
            else -> other
        }
        return ObdInventory(mergedPids, best.missingRange, best.unqueriedRange)
    }
}

internal data class ObdInventoryRange(val pids: List<Int>, val hasNext: Boolean)

/** สำรวจซ้ำครั้งเดียวในลิงก์เดิม หลัง RPM เป็นบวก จำกัดสามคำสั่งและทำทีละช่วง */
internal class ObdInventoryRetry(private val reader: Elm327Telemetry, private val initial: ObdInventory) {
    var inventory: ObdInventory = initial
        private set
    var nextRange: Int? = null
        private set
    private var attempted = false
    private var budget = 3
    private val retryPids = mutableListOf<Int>()

    fun startIfNeeded(rpm: Double?): Boolean {
        if (attempted || !initial.incomplete || rpm == null || rpm <= 0.0) return false
        attempted = true
        nextRange = 0
        return true
    }

    suspend fun probeNext() {
        val range = nextRange ?: return
        try {
            val result = reader.inventoryRange(range)
            if (result == null) {
                fail(range)
                return
            }
            retryPids.addAll(result.pids)
            budget--
            val next = if (result.hasNext && range < 0xE0) range + 0x20 else null
            inventory = initial.merge(ObdInventory(retryPids.toList(), unqueriedRange = next))
            nextRange = if (budget > 0) next else null
        } catch (e: TimeoutCancellationException) {
            fail(range)
            throw e
        } catch (e: IOException) {
            fail(range)
            throw e
        }
    }

    private fun fail(range: Int) {
        inventory = initial.merge(ObdInventory(retryPids.toList(), missingRange = range))
        nextRange = null
    }
}

internal fun Int.obdHex(): String = toString(16).uppercase().padStart(2, '0')

internal enum class ObdObservedCommand(val request: String, val pid: String, val bytes: Int) {
    INVENTORY("0100", "00", 4), RPM("010C", "0C", 2),
}

internal enum class ObdDiagnosticResult {
    PARSED, NO_DATA, UNABLE_TO_CONNECT, BUS_ERROR, UNSUPPORTED,
    UNRECOGNIZED_HEX, EMPTY, OTHER_TEXT, TIMEOUT, IO_ERROR,
}

/** เก็บเฉพาะชนิดคำตอบและรูปแบบ ไม่เก็บข้อความหรือ payload ของรถ */
internal data class ObdCommandDiagnostic(
    val command: ObdObservedCommand,
    val result: ObdDiagnosticResult,
    val hexChars: Int? = null,
    val prefixChars: Int? = null,
)

internal fun diagnoseObdResponse(command: ObdObservedCommand, response: String): ObdCommandDiagnostic {
    fun result(kind: ObdDiagnosticResult) = ObdCommandDiagnostic(command, kind)
    if (mode01Values(response, command.pid, command.bytes).any()) return result(ObdDiagnosticResult.PARSED)

    val lines = response.lineSequence().map { it.filterNot(Char::isWhitespace) }
        .filter { it.isNotEmpty() }.toList()
    val normalized = lines.map { it.uppercase(Locale.ROOT) }
    if (normalized.any { it == "NODATA" }) return result(ObdDiagnosticResult.NO_DATA)
    if (normalized.any { it == "UNABLETOCONNECT" }) return result(ObdDiagnosticResult.UNABLE_TO_CONNECT)
    if (normalized.any {
            it.contains("CANERROR") || it.contains("BUSERROR") ||
                (it.contains("BUSINIT") && it.contains("ERROR"))
        }) return result(ObdDiagnosticResult.BUS_ERROR)
    if (normalized.any {
            it == "?" || it == "NOTSUPPORTED" || it == "UNSUPPORTED" ||
                it == "7F0111" || it == "7F0112"
        }) return result(ObdDiagnosticResult.UNSUPPORTED)

    val hexLines = normalized.filter { line -> line.all { it.digitToIntOrNull(16) != null } }
    val expected = "41${command.pid}"
    val hex = hexLines.firstOrNull { it.contains(expected) } ?: hexLines.firstOrNull()
    if (hex != null) {
        // ระยะก่อน 41+PID ช่วยแยก header/PCI โดยไม่บันทึกเนื้อหา hex
        return ObdCommandDiagnostic(command, ObdDiagnosticResult.UNRECOGNIZED_HEX,
            hexChars = hex.length, prefixChars = hex.indexOf(expected).takeIf { it >= 0 })
    }
    return result(if (lines.isEmpty()) ObdDiagnosticResult.EMPTY else ObdDiagnosticResult.OTHER_TEXT)
}

private fun mode01Values(response: String, pid: String, bytes: Int): Sequence<Long> {
    val prefix = "41$pid"
    return response.lineSequence().mapNotNull { line ->
        val frame = line.filterNot(Char::isWhitespace)
        if (!frame.startsWith(prefix, ignoreCase = true)) return@mapNotNull null
        val payload = frame.drop(prefix.length)
        if (payload.length < bytes * 2 || payload.length % 2 != 0 ||
            !payload.all { it.digitToIntOrNull(16) != null }) return@mapNotNull null
        payload.take(bytes * 2).toLong(16)
    }
}

/** คุยกับอะแดปเตอร์ทีละคำสั่งและแยกข้อความตอบด้วย prompt ของ ELM327 */
internal class Elm327Telemetry(
    private val input: InputStream,
    private val output: OutputStream,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val onDiagnostic: (ObdCommandDiagnostic) -> Unit = {},
) {
    private val reportedDiagnostics = mutableSetOf<Pair<ObdObservedCommand, ObdDiagnosticResult>>()
    private var lastMode01WriteAt: Long? = null

    private fun report(diagnostic: ObdCommandDiagnostic) {
        if (reportedDiagnostics.add(diagnostic.command to diagnostic.result)) {
            runCatching { onDiagnostic(diagnostic) }
        }
    }
    suspend fun initialize() {
        command("ATZ", 5_000L)
        for (setting in listOf("ATE0", "ATH0", "ATSP0")) {
            if (command(setting).lineSequence().none { it.trim().equals("OK", ignoreCase = true) }) {
                throw IOException("ELM327 rejected setup")
            }
        }
    }

    suspend fun read(pid: ObdPid, timeoutMs: Long = 2_500L): Double? =
        pid.decode(command("01${pid.code}", timeoutMs))

    suspend fun inventory(): ObdInventory {
        val pids = mutableListOf<Int>()
        for (range in 0..0xE0 step 0x20) {
            val block = inventoryRange(range) ?: return ObdInventory(pids, missingRange = range)
            pids.addAll(block.pids)
            if (!block.hasNext) break
        }
        return ObdInventory(pids)
    }

    suspend fun inventoryRange(range: Int): ObdInventoryRange? {
        require(range in 0..0xE0 && range % 0x20 == 0)
        val code = range.obdHex()
        val response = command("01$code", if (range == 0) 8_000 else 2_500)
        val masks = mode01Values(response, code, 4).toList()
        if (masks.isEmpty()) return null
        val mask = masks.fold(0L, Long::or)
        // บิตสุดท้ายระบุว่ามีช่วงถัดไป จึงไม่นับเป็นข้อมูลที่ใช้บนหน้าปัด
        val pids = (1..31).filter { mask and (1L shl (32 - it)) != 0L }.map { range + it }
        return ObdInventoryRange(pids, hasNext = mask and 1L != 0L)
    }

    private suspend fun command(text: String, timeoutMs: Long = 2_500L): String {
        val observed = ObdObservedCommand.entries.firstOrNull { it.request == text }
        return try {
            val response = executeCommand(text, timeoutMs)
            if (observed != null) report(diagnoseObdResponse(observed, response))
            response
        } catch (e: TimeoutCancellationException) {
            if (observed != null) report(ObdCommandDiagnostic(observed, ObdDiagnosticResult.TIMEOUT))
            throw e
        } catch (e: IOException) {
            if (observed != null) report(ObdCommandDiagnostic(observed, ObdDiagnosticResult.IO_ERROR))
            throw e
        }
    }

    private suspend fun executeCommand(text: String, timeoutMs: Long): String {
        val mode01 = text.startsWith("01")
        if (mode01) {
            lastMode01WriteAt?.let { previous ->
                while (true) {
                    val elapsed = (monotonicMillis() - previous).coerceAtLeast(0L)
                    if (elapsed >= 500L) break
                    pause(500L - elapsed)
                }
            }
        }
        return withTimeout(timeoutMs) {
            output.write("$text\r".toByteArray(Charsets.US_ASCII))
            output.flush()
            if (mode01) lastMode01WriteAt = monotonicMillis()
            val response = StringBuilder()
            // อ่านเฉพาะเมื่อมีข้อมูลเพื่อให้ timeout ยกเลิกการรอได้แม้ adapter ไม่ส่ง prompt
            while (response.length < 4096) {
                if (input.available() == 0) {
                    delay(20)
                    continue
                }
                val next = input.read()
                if (next == -1) throw IOException("ELM327 disconnected")
                if (next == '>'.code) return@withTimeout response.toString()
                response.append(next.toChar())
            }
            throw IOException("ELM327 response too long")
        }
    }
}
