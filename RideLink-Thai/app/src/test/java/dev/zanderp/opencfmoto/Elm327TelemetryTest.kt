package dev.zanderp.opencfmoto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Elm327TelemetryTest {
    @Test fun readsLivePidsAndKeepsGpsSpeed() = runBlocking {
        val replies = "ELM327 v1.5\r>ATE0\rOK\r>OK\r>OK\r>" +
            "SEARCHING...\r41 0C 1A F8\r>41056E\r>41 42 36 B0\r>"
        val commands = ByteArrayOutputStream()
        val reader = Elm327Telemetry(ByteArrayInputStream(replies.toByteArray()), commands)
        reader.initialize()
        val sample = ObdSample(reader.read(ObdPid.RPM), reader.read(ObdPid.COOLANT), reader.read(ObdPid.VOLTAGE))
        val data = sample.applyTo(LiveDashboardData(speedKmh = 20.0, speedFromGps = true))

        assertEquals("ATZ\rATE0\rATH0\rATSP0\r010C\r0105\r0142\r", commands.toString(Charsets.US_ASCII.name()))
        assertEquals(1726.0, data.rpm)
        assertEquals(70.0, data.coolant)
        assertEquals(14.0, data.volt)
        assertEquals(20.0, data.speedKmh)
        assertTrue(data.speedFromGps)
        assertTrue(data.obdLive)
    }

    @Test fun ignoresMissingAndMalformedData() {
        assertNull(ObdPid.RPM.decode("010C\rNO DATA\r"))
        assertNull(ObdPid.RPM.decode("41 0C 1A\r"))
        assertNull(ObdPid.RPM.decode("41 05 6E\r"))
        assertNull(ObdPid.RPM.decode("41 0C XX 00\r"))
        assertEquals(0.0, ObdPid.RPM.decode("41 0C 00 00\r"))
        assertFalse(ObdSample().applyTo(LiveDashboardData()).obdLive)
    }

    @Test fun inventoriesMultipleRespondersAndContinuesOnlyWhenAdvertised() = runBlocking {
        val commands = ByteArrayOutputStream()
        val replies = "SEARCHING...\r41 00 08 10 00 01\r410000080000\r>" +
            "41 20 00 00 00 01\r>41 40 C0 00 00 00\r>"
        val reader = Elm327Telemetry(ByteArrayInputStream(replies.toByteArray()), commands)

        assertEquals(ObdInventory(listOf(0x05, 0x0C, 0x0D, 0x41, 0x42)), reader.inventory())
        assertEquals("0100\r0120\r0140\r", commands.toString(Charsets.US_ASCII.name()))
    }

    @Test fun reportsUnavailableAndPartialInventories() = runBlocking {
        val noReply = Elm327Telemetry(ByteArrayInputStream("NO DATA\r>".toByteArray()), ByteArrayOutputStream())
        val partialReplies = ByteArrayInputStream("410008000001\r>4120000F\r>".toByteArray())
        val partial = Elm327Telemetry(partialReplies, ByteArrayOutputStream())

        assertEquals(ObdInventory(emptyList(), missingRange = 0), noReply.inventory())
        assertEquals(ObdInventory(listOf(0x05), missingRange = 0x20), partial.inventory())
    }

    @Test fun decodesAndMapsAdditionalMode01Values() {
        assertEquals(0.0, ObdPid.LOAD.decode("41 04 00")!!, 0.0001)
        assertEquals(100.0, ObdPid.LOAD.decode("41 04 FF")!!, 0.0001)
        assertEquals(-40.0, ObdPid.INTAKE.decode("41 0F 00")!!, 0.0001)
        assertEquals(70.0, ObdPid.INTAKE.decode("41 0F 6E")!!, 0.0001)
        assertEquals(100.0, ObdPid.MAP.decode("41 0B 64")!!, 0.0001)
        assertEquals(12800.0 / 255.0, ObdPid.RELATIVE_THROTTLE.decode("41 45 80")!!, 0.0001)
        assertNull(ObdPid.LOAD.decode("NO DATA"))

        val data = ObdSample(load = 50.0, intake = 30.0, manifoldPressureKpa = 96.0,
            relativeThrottle = 12.0).applyTo(LiveDashboardData(speedKmh = 12.0))
        assertEquals(50.0, data.load)
        assertEquals(30.0, data.intake)
        assertEquals(96.0, data.manifoldPressureKpa)
        assertEquals(12.0, data.relativeThrottle)
        assertEquals(12.0, data.speedKmh)
        assertTrue(data.obdLive)
    }

    @Test fun selectsSecondaryPidsFromThisInventoryOnly() {
        val advertised = ObdInventory(listOf(0x04, 0x05, 0x0B, 0x0F, 0x42, 0x45))
        assertEquals(listOf(ObdPid.COOLANT, ObdPid.VOLTAGE, ObdPid.LOAD, ObdPid.INTAKE,
            ObdPid.MAP, ObdPid.RELATIVE_THROTTLE), advertised.secondaryPids())
        assertEquals(listOf(ObdPid.COOLANT, ObdPid.LOAD),
            ObdInventory(listOf(0x04, 0x05), missingRange = 0x20).secondaryPids())
        assertEquals(listOf(ObdPid.COOLANT, ObdPid.VOLTAGE),
            ObdInventory(emptyList(), missingRange = 0).secondaryPids())
        assertTrue(ObdInventory(emptyList()).secondaryPids().isEmpty())
    }

    @Test fun expiresEachReadingIndependentlyAndClearsNoData() {
        val sample = ObdSample().withReading(ObdPid.RPM, 2_000.0, 1_000L)
            .withReading(ObdPid.COOLANT, 80.0, 1_000L)
            .withReading(ObdPid.LOAD, 40.0, 1_000L)
        assertEquals(2_000.0, sample.applyTo(LiveDashboardData(), 6_000L).rpm)
        val rpmExpired = sample.applyTo(LiveDashboardData(), 6_001L)
        assertNull(rpmExpired.rpm)
        assertEquals(80.0, rpmExpired.coolant)
        assertEquals(40.0, rpmExpired.load)
        assertEquals(40.0, sample.applyTo(LiveDashboardData(), 11_000L).load)
        assertFalse(sample.applyTo(LiveDashboardData(), 11_001L).obdLive)

        val freshRpm = sample.withReading(ObdPid.RPM, 2_200.0, 12_000L)
            .applyTo(LiveDashboardData(), 12_000L)
        assertEquals(2_200.0, freshRpm.rpm)
        assertNull(freshRpm.load)
        val noData = sample.withReading(ObdPid.LOAD, null, 2_000L)
        assertNull(noData.load)
        assertFalse(ObdPid.LOAD in noData.readAtMs)
    }

    @Test fun spacesMode01CommandsIncludingInventoryAndRetry() = runBlocking {
        var now = 1_000L
        val starts = mutableListOf<Pair<String, Long>>()
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                starts.add(String(bytes, offset, length, Charsets.US_ASCII).trim() to now)
            }
        }
        val replies = "41 00 00 00 00 01\r>41 20 00 00 00 00\r>NO DATA\r>NO DATA\r>"
        val reader = Elm327Telemetry(ByteArrayInputStream(replies.toByteArray()), output,
            monotonicMillis = { now }, pause = { now += it })

        reader.inventory()
        reader.read(ObdPid.RPM)
        reader.read(ObdPid.RPM)
        assertEquals(listOf("0100" to 1_000L, "0120" to 1_500L,
            "010C" to 2_000L, "010C" to 2_500L), starts)
    }

    @Test fun retriesIncompleteInventoryAfterPositiveRpmAndReadsSupportedValues() = runBlocking {
        var now = 0L
        val starts = mutableListOf<Pair<String, Long>>()
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                starts.add(String(bytes, offset, length, Charsets.US_ASCII).trim() to now)
            }
        }
        val replies = listOf(
            "UNABLE TO CONNECT", "41 0C 00 00", "41 05 6E", "41 0C 1C 70",
            "41 00 18 32 00 01", "41 0C 1B A0", "41 20 00 00 00 01",
            "41 0C 1B A0", "41 40 48 00 00 00",
            "41 0C 1B A0", "41 05 6E", "41 0C 1B A0", "41 42 36 B0",
            "41 0C 1B A0", "41 04 80", "41 0C 1B A0", "41 0F 46",
            "41 0C 1B A0", "41 0B 60", "41 0C 1B A0", "41 45 40",
        ).joinToString("") { "$it\r>" }
        val reader = Elm327Telemetry(ByteArrayInputStream(replies.toByteArray()), output,
            monotonicMillis = { now }, pause = { now += it })
        val initial = reader.inventory()
        val retry = ObdInventoryRetry(reader, initial)

        assertEquals(ObdInventory(emptyList(), missingRange = 0), initial)
        assertFalse(retry.startIfNeeded(reader.read(ObdPid.RPM)))
        assertEquals(70.0, reader.read(initial.secondaryPids().first()))
        assertTrue(retry.startIfNeeded(reader.read(ObdPid.RPM)))
        retry.probeNext()
        assertEquals(0x20, retry.inventory.unqueriedRange)
        assertTrue(ObdPid.LOAD in retry.inventory.secondaryPids())
        assertFalse(retry.startIfNeeded(reader.read(ObdPid.RPM)))
        retry.probeNext()
        assertFalse(retry.startIfNeeded(reader.read(ObdPid.RPM)))
        retry.probeNext()
        assertNull(retry.nextRange)
        assertFalse(retry.inventory.incomplete)
        assertFalse(retry.startIfNeeded(1_800.0))

        var sample = ObdSample()
        for (pid in retry.inventory.secondaryPids()) {
            reader.read(ObdPid.RPM)
            sample = sample.withReading(pid, reader.read(pid), now)
        }
        assertEquals(12800.0 / 255.0, sample.load!!, 0.0001)
        assertEquals(30.0, sample.intake!!, 0.0001)
        assertEquals(96.0, sample.manifoldPressureKpa!!, 0.0001)
        assertEquals(6400.0 / 255.0, sample.relativeThrottle!!, 0.0001)
        assertEquals(2, starts.count { it.first == "0100" })
        assertTrue(starts.zipWithNext().all { (a, b) -> b.second - a.second >= 500L })
    }

    @Test fun retryKeepsKnownPidsOnPartialFailureAndHonorsThreeRangeBudget() = runBlocking {
        var now = 0L
        val partialReader = Elm327Telemetry(
            ByteArrayInputStream("41 00 08 00 00 01\r>NO DATA\r>".toByteArray()),
            ByteArrayOutputStream(), monotonicMillis = { now }, pause = { now += it },
        )
        val partial = ObdInventoryRetry(partialReader, ObdInventory(listOf(0x05, 0x42), missingRange = 0x40))
        assertTrue(partial.startIfNeeded(1_000.0))
        partial.probeNext()
        partial.probeNext()
        assertEquals(listOf(0x05, 0x42), partial.inventory.pids)
        assertEquals(0x40, partial.inventory.missingRange)
        assertNull(partial.nextRange)
        assertFalse(partial.startIfNeeded(1_000.0))

        val boundedReader = Elm327Telemetry(ByteArrayInputStream(
            "41 00 00 00 00 01\r>41 20 00 00 00 01\r>41 40 00 00 00 01\r>".toByteArray()),
            ByteArrayOutputStream(), monotonicMillis = { now }, pause = { now += it })
        val bounded = ObdInventoryRetry(boundedReader, ObdInventory(emptyList(), missingRange = 0))
        assertTrue(bounded.startIfNeeded(1_000.0))
        repeat(3) { bounded.probeNext() }
        assertNull(bounded.nextRange)
        assertNull(bounded.inventory.missingRange)
        assertEquals(0x60, bounded.inventory.unqueriedRange)
        assertTrue(bounded.inventory.incomplete)
    }

    @Test fun classifiesFirstMode01RepliesWithoutKeepingPayload() {
        val rpm = ObdObservedCommand.RPM
        fun kind(reply: String) = diagnoseObdResponse(rpm, reply).result

        assertEquals(ObdDiagnosticResult.PARSED, kind("SEARCHING...\r41 0C 1A F8\r"))
        assertEquals(ObdDiagnosticResult.NO_DATA, kind("SEARCHING...\rNO DATA\r"))
        assertEquals(ObdDiagnosticResult.UNABLE_TO_CONNECT, kind("UNABLE TO CONNECT\r"))
        assertEquals(ObdDiagnosticResult.BUS_ERROR, kind("CAN ERROR\r"))
        assertEquals(ObdDiagnosticResult.BUS_ERROR, kind("BUS INIT: ERROR\r"))
        assertEquals(ObdDiagnosticResult.UNSUPPORTED, kind("?\r"))
        assertEquals(ObdDiagnosticResult.UNSUPPORTED, kind("7F 01 11\r"))
        assertEquals(ObdDiagnosticResult.EMPTY, kind("\r\n"))
        assertEquals(ObdDiagnosticResult.OTHER_TEXT, kind("SEARCHING...\r"))

        val header = diagnoseObdResponse(rpm, "7E8 04 41 0C DE AD\r")
        assertEquals(ObdDiagnosticResult.UNRECOGNIZED_HEX, header.result)
        assertEquals(13, header.hexChars)
        assertEquals(5, header.prefixChars)
        assertFalse(header.toString().contains("DEAD"))
        assertNull(ObdPid.RPM.decode("7E8 04 41 0C DE AD\r"))

        val extended = diagnoseObdResponse(ObdObservedCommand.INVENTORY,
            "18DAF110 06 41 00 BE 1F A8 13\r")
        assertEquals(22, extended.hexChars)
        assertEquals(10, extended.prefixChars)
        val short = diagnoseObdResponse(rpm, "41 0C 1A\r")
        assertEquals(ObdDiagnosticResult.UNRECOGNIZED_HEX, short.result)
        assertEquals(6, short.hexChars)
        assertEquals(0, short.prefixChars)
    }

    @Test fun reportsEachObservedOutcomeOncePerReader() = runBlocking {
        val replies = "NO DATA\r>NO DATA\r>NO DATA\r>41 0C 1A F8\r>41 0C 1A F8\r>"
        val diagnostics = mutableListOf<ObdCommandDiagnostic>()
        val reader = Elm327Telemetry(ByteArrayInputStream(replies.toByteArray()), ByteArrayOutputStream()) {
            diagnostics.add(it)
        }

        reader.inventory()
        repeat(4) { reader.read(ObdPid.RPM) }
        assertEquals(listOf(
            ObdCommandDiagnostic(ObdObservedCommand.INVENTORY, ObdDiagnosticResult.NO_DATA),
            ObdCommandDiagnostic(ObdObservedCommand.RPM, ObdDiagnosticResult.NO_DATA),
            ObdCommandDiagnostic(ObdObservedCommand.RPM, ObdDiagnosticResult.PARSED),
        ), diagnostics)
    }

    @Test fun reportsTimeoutAndIoWithoutChangingFailureBehavior() = runBlocking {
        val timedOut = mutableListOf<ObdCommandDiagnostic>()
        val reader = Elm327Telemetry(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream()) { timedOut.add(it) }
        try {
            reader.read(ObdPid.RPM, timeoutMs = 50)
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) {
            assertEquals(listOf(ObdCommandDiagnostic(ObdObservedCommand.RPM, ObdDiagnosticResult.TIMEOUT)), timedOut)
        }

        val failed = mutableListOf<ObdCommandDiagnostic>()
        val broken = object : InputStream() {
            override fun available(): Int = throw IOException("private adapter details")
            override fun read(): Int = -1
        }
        val failingReader = Elm327Telemetry(broken, ByteArrayOutputStream()) { failed.add(it) }
        try {
            failingReader.inventory()
            fail("Expected I/O error")
        } catch (_: IOException) {
            assertEquals(listOf(ObdCommandDiagnostic(ObdObservedCommand.INVENTORY, ObdDiagnosticResult.IO_ERROR)), failed)
            assertFalse(failed.toString().contains("private adapter details"))
        }
    }
}
