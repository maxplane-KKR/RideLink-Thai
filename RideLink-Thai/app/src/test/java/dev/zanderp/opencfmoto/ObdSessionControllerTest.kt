package dev.zanderp.opencfmoto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [ObdSessionController] strictly across its external interface & [ObdTransport] seam
 * using an in-memory fake transport adapter — verifying every RL-0008 recovery flow and
 * every 2.4 GHz radio-coexistence / off-lock safety invariant.
 */
class ObdSessionControllerTest {

    private val testAddress = "AA:BB:CC:DD:EE:FF"

    private fun fastConfig(
        postFirstFrameSettleMs: Long = 0L,
        connectTimeoutMs: Long = 12_000L,
        rfcommFallbackSettleMs: Long = 500L,
        radioPollIntervalMs: Long = 200L,
        manualTestWindowMs: Long = 45_000L,
        noDataGraceMs: Long = 30_000L,
        ecuWaitPollIntervalMs: Long = 2_500L,
        noDataPollIntervalMs: Long = 3_000L,
        ecuInventoryReprobeIntervalMs: Long = 20_000L,
        retryBackoffMs: List<Long> = listOf(3_000L, 6_000L, 12_000L, 25_000L),
    ) = ObdSessionConfig(
        postFirstFrameSettleMs = postFirstFrameSettleMs,
        connectTimeoutMs = connectTimeoutMs,
        rfcommFallbackSettleMs = rfcommFallbackSettleMs,
        radioPollIntervalMs = radioPollIntervalMs,
        manualTestWindowMs = manualTestWindowMs,
        firstReadTimeoutMs = 500L,
        readTimeoutMs = 500L,
        livePollIntervalMs = 500L,
        ecuWaitPollIntervalMs = ecuWaitPollIntervalMs,
        noDataGraceMs = noDataGraceMs,
        noDataPollIntervalMs = noDataPollIntervalMs,
        ecuInventoryReprobeIntervalMs = ecuInventoryReprobeIntervalMs,
        retryBackoffMs = retryBackoffMs,
        unavailableRetryMs = 30_000L,
        elmConfig = Elm327Config(
            atzTimeoutMs = 150L,
            commandTimeoutMs = 150L,
            bannerSyncTimeoutMs = 80L,
            shiftedReplyTimeoutMs = 80L,
            retrySettleMs = 0L,
        ),
    )

    private fun streamingDemand(
        address: String? = testAddress,
        streaming: Boolean = true,
        streamingEnteredAtMs: Long = 0L,
        appVisible: Boolean = true,
    ) = ObdDemand(
        selectedAddress = address,
        hasPermission = true,
        appVisible = appVisible,
        dashboardActive = true,
        projectionStreaming = streaming,
        streamingEnteredAtMs = streamingEnteredAtMs,
    )

    private suspend fun awaitCondition(timeoutMs: Long = 4_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            delay(5)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }

    @Test fun recoversFromSecureRfcommFailureWithSingleInsecureFallbackAfterSettle() = runBlocking {
        val clock = AtomicLong(1_000L)
        val logs = CopyOnWriteArrayList<String>()
        val openAttempts = CopyOnWriteArrayList<Pair<ObdRfcommMode, Long>>()
        val transport = FakeObdTransport(
            onOpen = { _, mode ->
                openAttempts.add(mode to clock.get())
                when (mode) {
                    ObdRfcommMode.SECURE -> FakeObdConnection(connectError = IOException("secure refused"))
                    ObdRfcommMode.INSECURE -> FakeObdConnection(
                        responder = { cmd, _ ->
                            when (cmd) {
                                "ATZ" -> "ELM327 v1.5\r>"
                                "ATE0", "ATH0", "ATSP0" -> "OK\r>"
                                "0100" -> "41 00 08 10 00 00\r>"
                                "010C" -> "41 0C 1A F8\r>"
                                "0105" -> "41 05 6E\r>"
                                else -> "NO DATA\r>"
                            }
                        },
                    )
                }
            },
        )
        val controller = ObdSessionController(
            transport = transport,
            config = fastConfig(rfcommFallbackSettleMs = 500L),
            monotonicMillis = clock::get,
            pause = { ms ->
                clock.addAndGet(ms)
                delay(1)
            },
            onLog = { logs.add(it) },
        )
        try {
            controller.updateDemand(streamingDemand())
            awaitCondition { controller.status.value.phase == LiveObd.Phase.LIVE }

            assertEquals(2, openAttempts.size)
            assertEquals(ObdRfcommMode.SECURE, openAttempts[0].first)
            assertEquals(ObdRfcommMode.INSECURE, openAttempts[1].first)
            assertTrue("Expected >= 500ms settle before insecure fallback", openAttempts[1].second - openAttempts[0].second >= 500L)
            assertEquals(1726.0, controller.status.value.sample.rpm)
            assertTrue(logs.contains("[OBD] rfcomm_fallback=insecure"))
            assertTrue(logs.none { it.contains(testAddress) })
        } finally {
            controller.shutdown()
        }
    }

    @Test fun triesInsecureRfcommWhenSecureSocketCreationFails() = runBlocking {
        val openedModes = CopyOnWriteArrayList<ObdRfcommMode>()
        val transport = FakeObdTransport { _, mode ->
            openedModes.add(mode)
            if (mode == ObdRfcommMode.SECURE) throw IOException("Cannot create secure socket")
            FakeObdConnection(blockingConnect = true)
        }
        val controller = ObdSessionController(transport, fastConfig(rfcommFallbackSettleMs = 0L))
        try {
            controller.updateDemand(streamingDemand())
            awaitCondition { openedModes.size >= 2 }
            assertEquals(listOf(ObdRfcommMode.SECURE, ObdRfcommMode.INSECURE), openedModes)
        } finally {
            controller.shutdown()
        }
    }

    @Test fun reportsReadTimeoutBeforeRetrying() = runBlocking {
        val logs = CopyOnWriteArrayList<String>()
        val transport = FakeObdTransport { _, _ ->
            FakeObdConnection(responder = { command, _ ->
                when (command) {
                    "ATZ" -> "ELM327 v1.5\r>"
                    "ATE0", "ATH0", "ATSP0" -> "OK\r>"
                    "0100" -> "41 00 00 10 00 00\r>"
                    else -> null
                }
            })
        }
        val controller = ObdSessionController(transport, fastConfig(), onLog = { logs.add(it) })
        try {
            controller.updateDemand(streamingDemand())
            awaitCondition { logs.any { it.startsWith("[OBD] retry_scheduled ") } }
            assertTrue(logs.contains("[OBD] failure stage=mode01_rpm reason=timeout"))
        } finally {
            controller.shutdown()
        }
    }

    @Test fun cancelsAndClosesManualTestWhenDeviceSelectionHasChanged() = runBlocking {
        val connectEntered = AtomicBoolean(false)
        val connectionClosed = AtomicBoolean(false)
        val transport = FakeObdTransport { _, _ ->
            FakeObdConnection(
                blockingConnect = true,
                onConnectEntered = { connectEntered.set(true) },
                onClosed = { connectionClosed.set(true) },
            )
        }
        val controller = ObdSessionController(transport, fastConfig())
        try {
            val manualDemand = streamingDemand(streaming = false).copy(dashboardActive = false)
            assertTrue(controller.startManualTest(manualDemand))
            awaitCondition { connectEntered.get() }

            controller.cancelManualTest(manualDemand.copy(selectedAddress = null))

            awaitCondition { connectionClosed.get() }
            assertEquals(LiveObd.Phase.IDLE, controller.status.value.phase)
            assertFalse(controller.hasActiveOrPendingWork())
        } finally {
            controller.shutdown()
        }
    }

    @Test fun recoversFromColdBootWakePromptAndBannerShiftEndToEnd() = runBlocking {
        val clock = AtomicLong(5_000L)
        val logs = CopyOnWriteArrayList<String>()
        val transport = FakeObdTransport(
            onOpen = { _, _ ->
                FakeObdConnection(
                    initialBytes = ">", // Stale wake prompt queued before ATZ
                    responder = { cmd, _ ->
                        when (cmd) {
                            "ATZ" -> "ELM327 v1.5\r>"
                            "ATE0", "ATH0", "ATSP0" -> "OK\r>"
                            "0100" -> "41 00 08 10 00 00\r>"
                            "010C" -> "41 0C 1C 70\r>"
                            "0105" -> "41 05 6E\r>"
                            else -> "NO DATA\r>"
                        }
                    },
                )
            },
        )
        val controller = ObdSessionController(
            transport = transport,
            config = fastConfig(manualTestWindowMs = 600_000L),
            monotonicMillis = clock::get,
            pause = { ms ->
                if (ms >= 60_000L) CompletableDeferred<Unit>().await()
                clock.addAndGet(ms)
                delay(1)
            },
            onLog = { logs.add(it) },
        )
        try {
            controller.updateDemand(streamingDemand())
            awaitCondition {
                controller.status.value.phase == LiveObd.Phase.LIVE &&
                    controller.status.value.sample.rpm == 1820.0
            }

            assertTrue(logs.contains("[OBD] elm_init=recovered banner_resync=true retries=0"))
            assertEquals(ObdInventory(listOf(0x05, 0x0C)), controller.inventory.value)
        } finally {
            controller.shutdown()
        }
    }

    @Test fun staysConnectedOnHealthySppLinkThroughPreIgnitionWaitAndNoDataUntilEngineStarts() = runBlocking {
        val clock = AtomicLong(1_000L)
        val logs = CopyOnWriteArrayList<String>()
        val openCount = AtomicInteger(0)
        val engineStarted = AtomicBoolean(false)
        val rpmPolls = AtomicInteger(0)

        val transport = FakeObdTransport(
            onOpen = { _, _ ->
                openCount.incrementAndGet()
                FakeObdConnection(
                    responder = { cmd, _ ->
                        when (cmd) {
                            "ATZ" -> "ELM327 v1.5\r>"
                            "ATE0", "ATH0", "ATSP0" -> "OK\r>"
                            "0100" -> if (engineStarted.get()) "41 00 08 10 00 00\r>" else "UNABLE TO CONNECT\r>"
                            "010C" -> {
                                rpmPolls.incrementAndGet()
                                if (engineStarted.get()) "41 0C 1C 70\r>" else "NO DATA\r>"
                            }
                            "0105" -> if (engineStarted.get()) "41 05 78\r>" else "NO DATA\r>"
                            "0142" -> if (engineStarted.get()) "41 42 36 B0\r>" else "NO DATA\r>"
                            else -> "NO DATA\r>"
                        }
                    },
                )
            },
        )
        val controller = ObdSessionController(
            transport = transport,
            config = fastConfig(
                noDataGraceMs = 10_000L,
                ecuWaitPollIntervalMs = 2_500L,
                noDataPollIntervalMs = 3_000L,
                ecuInventoryReprobeIntervalMs = 8_000L,
            ),
            monotonicMillis = clock::get,
            pause = { ms ->
                clock.addAndGet(ms)
                delay(1)
            },
            onLog = { logs.add(it) },
        )
        try {
            controller.updateDemand(streamingDemand())
            // Wait until healthy link transitions WAITING_FOR_ECU -> NO_DATA after >10s without closing socket
            awaitCondition { controller.status.value.phase == LiveObd.Phase.NO_DATA && rpmPolls.get() >= 5 }

            assertEquals("Must keep the single healthy Bluetooth SPP socket open", 1, openCount.get())
            // Verify zero per-poll log spam during pre-ECU wait & NO_DATA
            assertEquals(1, logs.count { it == "[OBD] WAITING_FOR_ECU" })
            assertEquals(1, logs.count { it == "[OBD] NO_DATA" })

            // Now rider starts Zontes 150X engine -> must flip immediately to LIVE on the SAME socket!
            engineStarted.set(true)
            awaitCondition {
                controller.status.value.phase == LiveObd.Phase.LIVE &&
                    controller.status.value.sample.rpm == 1820.0 &&
                    controller.inventory.value == ObdInventory(listOf(0x05, 0x0C))
            }

            assertEquals("Engine wake must reuse the exact same SPP socket without reconnecting", 1, openCount.get())
            assertTrue(logs.contains("[OBD] LIVE"))
        } finally {
            controller.shutdown()
        }
    }

    @Test fun usesBoundedBackoffAndManualTestOverridesPendingBackoffImmediately() = runBlocking {
        val clock = AtomicLong(1_000L)
        val logs = CopyOnWriteArrayList<String>()
        val failSessions = AtomicBoolean(true)
        val holdRetryGate = AtomicBoolean(false)
        val fourthBackoffEntered = CompletableDeferred<Unit>()
        val releaseBackoffGate = CompletableDeferred<Unit>()

        val transport = FakeObdTransport(
            onOpen = { _, _ ->
                if (failSessions.get()) {
                    FakeObdConnection(connectError = IOException("adapter still cold"))
                } else {
                    FakeObdConnection(
                        responder = { cmd, _ ->
                            when (cmd) {
                                "ATZ" -> "ELM327 v1.5\r>"
                                "ATE0", "ATH0", "ATSP0" -> "OK\r>"
                                "0100" -> "41 00 00 10 00 00\r>"
                                "010C" -> "41 0C 1A F8\r>"
                                else -> "NO DATA\r>"
                            }
                        },
                    )
                }
            },
        )
        val controller = ObdSessionController(
            transport = transport,
            config = fastConfig(
                rfcommFallbackSettleMs = 0L,
                manualTestWindowMs = 600_000L,
                retryBackoffMs = listOf(3_000L, 6_000L, 12_000L, 25_000L),
            ),
            monotonicMillis = clock::get,
            pause = { ms ->
                if (ms >= 60_000L) CompletableDeferred<Unit>().await()
                if (holdRetryGate.get()) {
                    fourthBackoffEntered.complete(Unit)
                    releaseBackoffGate.await()
                }
                clock.addAndGet(ms)
                delay(1)
            },
            onLog = { line ->
                logs.add(line)
                if (line == "[OBD] retry_scheduled delay_ms=25000 attempt=4") {
                    holdRetryGate.set(true)
                }
            },
        )
        try {
            val demand = streamingDemand()
            controller.updateDemand(demand)
            awaitCondition { logs.contains("[OBD] retry_scheduled delay_ms=25000 attempt=4") }

            val scheduled = logs.filter { it.startsWith("[OBD] retry_scheduled ") }
            assertEquals(
                listOf(
                    "[OBD] retry_scheduled delay_ms=3000 attempt=1",
                    "[OBD] retry_scheduled delay_ms=6000 attempt=2",
                    "[OBD] retry_scheduled delay_ms=12000 attempt=3",
                    "[OBD] retry_scheduled delay_ms=25000 attempt=4",
                ),
                scheduled.take(4),
            )

            // Wait until retry #4 is parked in backoff, then tap manual "ทดสอบ OBD"
            fourthBackoffEntered.await()
            failSessions.set(false)
            holdRetryGate.set(false)
            assertTrue(controller.startManualTest(demand))

            // Must cancel 25s backoff immediately and reach LIVE without waiting for Stop -> Start!
            awaitCondition {
                controller.status.value.phase == LiveObd.Phase.LIVE &&
                    controller.status.value.sample.rpm == 1726.0
            }
        } finally {
            releaseBackoffGate.complete(Unit)
            controller.shutdown()
        }
    }

    @Test fun enforcesPostFirstFrameSettleDelayAndSkipsBluetoothIfStreamingDropsDuringSettle() = runBlocking {
        val clock = AtomicLong(10_000L)
        val logs = CopyOnWriteArrayList<String>()
        val openedAtMs = CopyOnWriteArrayList<Long>()
        val settleGate = CompletableDeferred<Unit>()
        val enteredSettle = CompletableDeferred<Unit>()
        val holdSettle = AtomicBoolean(true)

        val transport = FakeObdTransport(
            onOpen = { _, _ ->
                openedAtMs.add(clock.get())
                FakeObdConnection(
                    responder = { cmd, _ ->
                        when (cmd) {
                            "ATZ" -> "ELM327 v1.5\r>"
                            "ATE0", "ATH0", "ATSP0" -> "OK\r>"
                            "0100" -> "41 00 00 10 00 00\r>"
                            "010C" -> "41 0C 1A F8\r>"
                            else -> "NO DATA\r>"
                        }
                    },
                )
            },
        )
        val controller = ObdSessionController(
            transport = transport,
            config = fastConfig(postFirstFrameSettleMs = 1_800L, radioPollIntervalMs = 200L),
            monotonicMillis = clock::get,
            pause = { ms ->
                if (holdSettle.get()) {
                    enteredSettle.complete(Unit)
                    settleGate.await()
                }
                clock.addAndGet(ms)
                delay(1)
            },
            onLog = { logs.add(it) },
        )
        try {
            // 1. Streaming enters at t=10_000 -> drops before 1_800ms settle finishes -> ZERO Bluetooth opens!
            controller.updateDemand(streamingDemand(streaming = true, streamingEnteredAtMs = 10_000L))
            enteredSettle.await()
            controller.onProjectionStreamingChanged(streaming = false, streamingEnteredAtMs = 0L)
            settleGate.complete(Unit)
            awaitCondition { controller.status.value.phase == LiveObd.Phase.IDLE }
            assertEquals("Must NOT touch Bluetooth if streaming drops during settle", 0, openedAtMs.size)
            assertTrue(logs.contains("[OBD] settle_wait delay_ms=1800"))

            // 2. Streaming enters cleanly at t=20_000 -> waits full 1_800ms settle before first RFCOMM open!
            holdSettle.set(false)
            clock.set(20_000L)
            controller.onProjectionStreamingChanged(streaming = true, streamingEnteredAtMs = 20_000L)
            awaitCondition { controller.status.value.phase == LiveObd.Phase.LIVE }
            assertEquals(1, openedAtMs.size)
            assertTrue("RFCOMM opened too early: ${openedAtMs[0]}", openedAtMs[0] >= 21_800L)
        } finally {
            settleGate.complete(Unit)
            controller.shutdown()
        }
    }

    @Test fun enforcesRadioCoexistenceGateForManualTestStartMidConnectAbortAndBackoffAbort() = runBlocking {
        val clock = AtomicLong(1_000L)
        val logs = CopyOnWriteArrayList<String>()
        val radioAllowed = AtomicBoolean(false)
        val openCount = AtomicInteger(0)
        val connectEntered = CompletableDeferred<Unit>()
        val connectClosed = CompletableDeferred<Unit>()

        val transport = FakeObdTransport(
            radioAllowed = { radioAllowed.get() },
            onOpen = { _, _ ->
                openCount.incrementAndGet()
                FakeObdConnection(
                    blockingConnect = true,
                    onConnectEntered = { connectEntered.complete(Unit) },
                    onClosed = { connectClosed.complete(Unit) },
                )
            },
        )
        val controller = ObdSessionController(
            transport = transport,
            config = fastConfig(radioPollIntervalMs = 50L, manualTestWindowMs = 600_000L),
            monotonicMillis = clock::get,
            pause = { ms ->
                if (ms >= 60_000L) CompletableDeferred<Unit>().await()
                clock.addAndGet(ms)
                delay(5)
            },
            onLog = { logs.add(it) },
        )
        try {
            val idleTestDemand = ObdDemand(
                selectedAddress = testAddress,
                hasPermission = true,
                appVisible = true,
                dashboardActive = false,
                projectionStreaming = false,
            )

            // (a) Bike is busy connecting (radioAllowed == false): startManualTest returns true
            //     (configured adapter, don't pop up picker) but touches ZERO Bluetooth APIs!
            assertTrue(controller.startManualTest(idleTestDemand))
            assertEquals(0, openCount.get())
            assertEquals(LiveObd.Phase.IDLE, controller.status.value.phase)
            assertTrue(logs.contains("[OBD] skipped reason=radio_busy"))

            // (b) Radio becomes allowed -> manual test starts blocking connect() ->
            //     bike starts connecting (radioAllowed flips false WITHOUT onProjectionStateChanged!) ->
            //     watcher closes socket immediately, aborts to IDLE, and does NOT retry!
            radioAllowed.set(true)
            assertTrue(controller.startManualTest(idleTestDemand))
            connectEntered.await()
            assertEquals(1, openCount.get())

            radioAllowed.set(false)
            connectClosed.await()
            awaitCondition { controller.status.value.phase == LiveObd.Phase.IDLE && !controller.hasActiveOrPendingWork() }

            assertEquals("Must not open fallback or retry after radio-busy abort", 1, openCount.get())
            assertTrue(logs.contains("[OBD] aborted reason=radio_busy"))

            // (c) Standalone manual test fails and enters backoff -> radioAllowed flips false during backoff ->
            //     retry timer aborts cleanly to IDLE with ZERO further Bluetooth opens!
            val retryFails = AtomicBoolean(true)
            val backoffOpenCount = AtomicInteger(0)
            val backoffRadio = AtomicBoolean(true)
            val backoffLogs = CopyOnWriteArrayList<String>()
            val backoffEntered = CompletableDeferred<Unit>()
            val releaseBackoffCheck = CompletableDeferred<Unit>()
            val backoffTransport = FakeObdTransport(
                radioAllowed = { backoffRadio.get() },
                onOpen = { _, _ ->
                    backoffOpenCount.incrementAndGet()
                    if (retryFails.get()) FakeObdConnection(connectError = IOException("cold adapter")) else null
                },
            )
            val backoffController = ObdSessionController(
                transport = backoffTransport,
                config = fastConfig(
                    rfcommFallbackSettleMs = 0L,
                    radioPollIntervalMs = 50L,
                    manualTestWindowMs = 600_000L,
                    retryBackoffMs = listOf(3_000L),
                ),
                monotonicMillis = clock::get,
                pause = { ms ->
                    if (ms >= 60_000L) CompletableDeferred<Unit>().await()
                    if (backoffLogs.any { it.startsWith("[OBD] retry_scheduled ") }) {
                        backoffEntered.complete(Unit)
                        releaseBackoffCheck.await()
                    }
                    clock.addAndGet(ms)
                    delay(1)
                },
                onLog = { backoffLogs.add(it) },
            )
            try {
                assertTrue(backoffController.startManualTest(idleTestDemand))
                backoffEntered.await()
                val opensBeforeAbort = backoffOpenCount.get()
                backoffRadio.set(false)
                releaseBackoffCheck.complete(Unit)
                awaitCondition {
                    backoffController.status.value.phase == LiveObd.Phase.IDLE &&
                        !backoffController.hasActiveOrPendingWork()
                }
                assertEquals("Must not open Bluetooth after radio becomes busy in backoff", opensBeforeAbort, backoffOpenCount.get())
                assertTrue(backoffLogs.contains("[OBD] skipped reason=radio_busy"))
            } finally {
                releaseBackoffCheck.complete(Unit)
                backoffController.shutdown()
            }
        } finally {
            controller.shutdown()
        }
    }

    @Test fun neverBlocksCallerThreadWithConnectionCloseOrHoldsStateLockDuringLogging() = runBlocking {
        val closeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-obd-close") }
        val logExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-obd-log") }
        val closeStarted = CountDownLatch(1)
        val allowCloseToFinish = CountDownLatch(1)
        val closeThreadName = AtomicReference<String>()
        val logBlockLatch = CountDownLatch(1)
        val blockNextIdleLog = AtomicBoolean(false)
        val reentrantCheckDone = AtomicBoolean(false)
        val callerThreadName = Thread.currentThread().name

        val transport = FakeObdTransport(
            onOpen = { _, _ ->
                FakeObdConnection(
                    responder = { cmd, _ ->
                        when (cmd) {
                            "ATZ" -> "ELM327 v1.5\r>"
                            "ATE0", "ATH0", "ATSP0" -> "OK\r>"
                            "0100" -> "41 00 00 10 00 00\r>"
                            "010C" -> "41 0C 1A F8\r>"
                            else -> "NO DATA\r>"
                        }
                    },
                    onClosed = {
                        closeThreadName.set(Thread.currentThread().name)
                        closeStarted.countDown()
                        allowCloseToFinish.await(2, TimeUnit.SECONDS)
                    },
                )
            },
        )
        lateinit var controller: ObdSessionController
        controller = ObdSessionController(
            transport = transport,
            config = fastConfig(),
            closeDispatcher = closeExecutor.asCoroutineDispatcher(),
            onLog = { line ->
                if (blockNextIdleLog.get() && line == "[OBD] IDLE") {
                    blockNextIdleLog.set(false)
                    // Prove stateLock is NOT held during onLog: another thread can immediately enter
                    // cancelManualTest (acquiring stateLock) without deadlocking!
                    logExecutor.execute {
                        controller.cancelManualTest(streamingDemand().copy(dashboardActive = false))
                        reentrantCheckDone.set(true)
                        logBlockLatch.countDown()
                    }
                    assertTrue("Deadlock: stateLock was held while invoking onLog", logBlockLatch.await(2, TimeUnit.SECONDS))
                }
            },
        )
        try {
            controller.updateDemand(streamingDemand())
            awaitCondition { controller.status.value.phase == LiveObd.Phase.LIVE }

            // Stop capture on the caller thread while connection.close() is deliberately blocked!
            // updateDemand MUST return immediately without waiting for connection.close().
            blockNextIdleLog.set(true)
            val startedNs = System.nanoTime()
            controller.updateDemand(streamingDemand().copy(dashboardActive = false))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)

            assertEquals(LiveObd.Phase.IDLE, controller.status.value.phase)
            assertTrue("Caller thread stalled waiting for socket.close(): ${elapsedMs}ms", elapsedMs < 500L)
            assertTrue("Expected stateLock-free re-entry check during onLog", reentrantCheckDone.get())
            assertTrue("Expected async close to start on closeDispatcher", closeStarted.await(2, TimeUnit.SECONDS))
            val actualCloseThread = closeThreadName.get().orEmpty()
            assertTrue("Expected close on test-obd-close thread, got $actualCloseThread", actualCloseThread.startsWith("test-obd-close"))
            assertNotEquals(callerThreadName, actualCloseThread)
        } finally {
            logBlockLatch.countDown()
            allowCloseToFinish.countDown()
            controller.shutdown()
            closeExecutor.shutdownNow()
            logExecutor.shutdownNow()
        }
    }

    @Test fun handlesUnavailableAdapterPermissionRevocationAndPiiSafety() = runBlocking {
        val clock = AtomicLong(1_000L)
        val logs = CopyOnWriteArrayList<String>()
        val modeRequested = CopyOnWriteArrayList<ObdRfcommMode>()
        val throwPermission = AtomicBoolean(false)

        val transport = FakeObdTransport(
            onOpen = { _, mode ->
                modeRequested.add(mode)
                if (throwPermission.get()) throw SecurityException("BLUETOOTH_CONNECT denied")
                null // Bluetooth disabled or device unbonded
            },
        )
        val controller = ObdSessionController(
            transport = transport,
            config = fastConfig(manualTestWindowMs = 600_000L),
            monotonicMillis = clock::get,
            pause = { ms ->
                if (ms >= 60_000L) CompletableDeferred<Unit>().await()
                clock.addAndGet(ms)
                delay(1)
            },
            onLog = { logs.add(it) },
        )
        try {
            val testDemand = ObdDemand(
                selectedAddress = testAddress,
                hasPermission = true,
                appVisible = true,
                dashboardActive = false,
                projectionStreaming = false,
            )
            // Missing address / permission returns false so UI opens device picker
            assertFalse(controller.startManualTest(testDemand.copy(selectedAddress = null)))
            assertFalse(controller.startManualTest(testDemand.copy(hasPermission = false)))

            // Unbonded / disabled adapter -> UNAVAILABLE with NO insecure fallback and NO retry in test mode
            assertTrue(controller.startManualTest(testDemand))
            awaitCondition { controller.status.value.phase == LiveObd.Phase.UNAVAILABLE && !controller.hasActiveOrPendingWork() }
            assertEquals(listOf(ObdRfcommMode.SECURE), modeRequested)

            // Revoked permission -> UNAVAILABLE + failure reason=permission, zero MAC PII in logs
            throwPermission.set(true)
            assertTrue(controller.startManualTest(testDemand))
            awaitCondition { logs.contains("[OBD] failure stage=bluetooth_connect reason=permission") }
            assertEquals(LiveObd.Phase.UNAVAILABLE, controller.status.value.phase)
            assertTrue("MAC address leaked in OBD logs: $logs", logs.none { it.contains(testAddress, ignoreCase = true) })
        } finally {
            controller.shutdown()
        }
    }

    private class FakeObdTransport(
        private val radioAllowed: () -> Boolean = { true },
        private val onOpen: (String, ObdRfcommMode) -> ObdConnection?,
    ) : ObdTransport {
        override fun isRadioAllowed(): Boolean = radioAllowed()
        override fun openConnection(address: String, mode: ObdRfcommMode): ObdConnection? = onOpen(address, mode)
    }

    private class FakeObdConnection(
        private val connectError: IOException? = null,
        private val blockingConnect: Boolean = false,
        private val initialBytes: String = "",
        private val responder: (command: String, attempt: Int) -> String? = { _, _ -> "NO DATA\r>" },
        private val onConnectEntered: () -> Unit = {},
        private val onClosed: () -> Unit = {},
    ) : ObdConnection {
        private val rx = LinkedBlockingQueue<Int>()
        private val txBuffer = StringBuilder()
        private val counts = mutableMapOf<String, Int>()
        private val closed = AtomicBoolean(false)
        private val connectGate = CompletableDeferred<Unit>()

        init {
            for (ch in initialBytes) rx.add(ch.code)
        }

        override val input: InputStream = object : InputStream() {
            override fun available(): Int {
                if (closed.get()) throw IOException("Socket closed")
                return rx.size
            }

            override fun read(): Int {
                if (closed.get()) return -1
                return rx.poll() ?: -1
            }
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                if (closed.get()) throw IOException("Socket closed")
                if (b == '\r'.code) {
                    val cmd = txBuffer.toString().trim()
                    txBuffer.setLength(0)
                    val attempt = (counts[cmd] ?: 0) + 1
                    counts[cmd] = attempt
                    val reply = responder(cmd, attempt)
                    if (reply != null) {
                        for (ch in reply) rx.add(ch.code)
                    }
                } else {
                    txBuffer.append(b.toChar())
                }
            }
        }

        override suspend fun connect() {
            onConnectEntered()
            if (blockingConnect) {
                connectGate.await()
                throw IOException("Connect aborted by close")
            }
            connectError?.let { throw it }
            if (closed.get()) throw IOException("Closed before connect")
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                connectGate.complete(Unit)
                onClosed()
            }
        }
    }
}
