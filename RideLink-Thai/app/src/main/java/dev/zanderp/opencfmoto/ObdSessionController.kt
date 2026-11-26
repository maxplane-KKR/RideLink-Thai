package dev.zanderp.opencfmoto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class ObdRfcommMode { SECURE, INSECURE }

/**
 * Minimal socket seam implemented by Android Bluetooth SPP in [LiveObd] and deterministic
 * in-memory fakes in unit tests.
 */
internal interface ObdConnection {
    val input: InputStream
    val output: OutputStream
    suspend fun connect()
    fun close()
}

/**
 * External transport & 2.4 GHz radio-coexistence seam for [ObdSessionController].
 * Production uses Android Bluetooth Classic SPP + [ConnectionState]/[SessionService];
 * unit tests inject an in-memory fake adapter across this exact seam.
 */
internal interface ObdTransport {
    /**
     * Returns true ONLY when Bluetooth RFCOMM traffic will not contend with bike Wi-Fi/PXC/AA
     * bring-up (`!ConnectionState.phase.busy && (!isProjectionRequested || phase == STREAMING)`).
     */
    fun isRadioAllowed(): Boolean

    /**
     * Creates an unconnected socket handle for [address] using [mode] without ever calling
     * discovery cancellation. Returns null if Bluetooth is disabled or [address] is not bonded.
     * Throws [SecurityException] if permission is revoked or [IOException] on creation failure.
     */
    fun openConnection(address: String, mode: ObdRfcommMode): ObdConnection?
}

internal data class ObdDemand(
    val selectedAddress: String? = null,
    val hasPermission: Boolean = false,
    val appVisible: Boolean = false,
    val dashboardActive: Boolean = false,
    val projectionStreaming: Boolean = false,
    val streamingEnteredAtMs: Long = 0L,
) {
    val dashboardNeedsObd: Boolean get() = dashboardActive && projectionStreaming
}

internal data class ObdSessionConfig(
    val postFirstFrameSettleMs: Long = 1_800L,
    val connectTimeoutMs: Long = 12_000L,
    val rfcommFallbackSettleMs: Long = 500L,
    val radioPollIntervalMs: Long = 200L,
    val manualTestWindowMs: Long = 45_000L,
    val firstReadTimeoutMs: Long = 8_000L,
    val readTimeoutMs: Long = 2_500L,
    val livePollIntervalMs: Long = 1_000L,
    val ecuWaitPollIntervalMs: Long = 2_500L,
    val noDataGraceMs: Long = 30_000L,
    val noDataPollIntervalMs: Long = 3_000L,
    val ecuInventoryReprobeIntervalMs: Long = 20_000L,
    val retryBackoffMs: List<Long> = listOf(3_000L, 6_000L, 12_000L, 25_000L),
    val unavailableRetryMs: Long = 30_000L,
    val elmConfig: Elm327Config = Elm327Config(),
)

/**
 * Pure-Kotlin deep module owning the complete OBD2/ELM327 session lifecycle behind a narrow
 * interface: radio-coexistence gating, post-FIRST_FRAME settle delay, secure-then-single-insecure
 * RFCOMM fallback, ELM327 cold-boot recovery, persistent healthy-link pre-ECU wait, bounded
 * backoff with manual test override, and off-lock async socket teardown & diagnostic logging.
 *
 * Locking invariant: [stateLock] guards ONLY in-memory state fields for nanoseconds; it NEVER
 * invokes [ObdConnection.close], [ObdTransport.openConnection], [onLog], [onSampleLog], or
 * `StateFlow` emission while held.
 */
internal class ObdSessionController(
    private val transport: ObdTransport,
    private val config: ObdSessionConfig = ObdSessionConfig(),
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val sessionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val closeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onLog: (String) -> Unit = {},
    private val onSampleLog: (String) -> Unit = {},
) {
    private class ActiveSession(
        val address: String,
        val settleUntilMs: Long,
    ) {
        @Volatile var cancelled: Boolean = false
        var job: Job? = null
        var connection: ObdConnection? = null
    }

    private class TimerHandle {
        var job: Job? = null
    }

    private class SideEffects {
        var statusChanged: Boolean = false
        var inventoryChanged: Boolean = false
        val jobsToCancel = mutableListOf<Job>()
        val connectionsToClose = mutableListOf<ObdConnection>()
        val logs = mutableListOf<String>()
        var sampleLog: String? = null
        var sessionToLaunch: ActiveSession? = null
        var retryToSchedule: Pair<TimerHandle, Long>? = null
        var testTimerToSchedule: TimerHandle? = null
    }

    private sealed interface ConnectOutcome {
        data class Connected(val connection: ObdConnection) : ConnectOutcome
        data class Failed(val timedOut: Boolean) : ConnectOutcome
        data object Unavailable : ConnectOutcome
    }

    private class RadioBusyAbortException : Exception()
    private class ConnectTimeoutException : Exception()

    private val scope = CoroutineScope(SupervisorJob() + sessionDispatcher)
    private val closeScope = CoroutineScope(SupervisorJob() + closeDispatcher)
    private val stateLock = Any()

    private val mutableStatus = MutableStateFlow(LiveObd.Status())
    val status: StateFlow<LiveObd.Status> = mutableStatus.asStateFlow()

    private val mutableInventory = MutableStateFlow<ObdInventory?>(null)
    val inventory: StateFlow<ObdInventory?> = mutableInventory.asStateFlow()

    @Volatile private var latestStatus: LiveObd.Status = LiveObd.Status()
    @Volatile private var latestInventory: ObdInventory? = null
    @Volatile private var activeWorkFlag: Boolean = false

    private var demand: ObdDemand = ObdDemand()
    private var activeSession: ActiveSession? = null
    private var retryHandle: TimerHandle? = null
    private var testHandle: TimerHandle? = null
    private var testActive: Boolean = false
    private var consecutiveFailures: Int = 0
    private var radioSkipLogged: Boolean = false

    /** Lock-free fast-path check so idle Activity/ConnectionState callbacks do zero work. */
    fun hasActiveOrPendingWork(): Boolean = activeWorkFlag

    fun updateDemand(next: ObdDemand) {
        val effects = SideEffects()
        synchronized(stateLock) {
            applyDemandLocked(next, effects)
            reconcileLocked(effects)
        }
        executeEffects(effects)
    }

    fun onProjectionStreamingChanged(streaming: Boolean, streamingEnteredAtMs: Long) {
        val effects = SideEffects()
        synchronized(stateLock) {
            demand = demand.copy(
                projectionStreaming = streaming,
                streamingEnteredAtMs = if (streaming) streamingEnteredAtMs else 0L,
            )
            reconcileLocked(effects)
        }
        executeEffects(effects)
    }

    /**
     * Starts a bounded manual OBD test window, immediately cancelling any pending retry backoff
     * (or restarting a stuck NO_DATA session) unless bike Wi-Fi/PXC/AA is currently connecting.
     *
     * Returns false ONLY when no adapter is selected, permission is missing, or app is hidden
     * (matching [LiveObd.startTest] caller contract to open the picker dialog).
     */
    fun startManualTest(next: ObdDemand): Boolean {
        if (next.selectedAddress == null || !next.hasPermission || !next.appVisible) return false
        val radioAllowed = transport.isRadioAllowed()
        val effects = SideEffects()
        synchronized(stateLock) {
            applyDemandLocked(next, effects)
            if (!radioAllowed) {
                stopActiveWorkLocked(effects, clearTest = !demand.dashboardNeedsObd)
                recordRadioSkipLocked(effects)
                publishLocked(LiveObd.Phase.IDLE, effects = effects)
                updateWorkFlagLocked()
            } else {
                radioSkipLogged = false
                consecutiveFailures = 0
                cancelRetryLocked(effects)
                cancelTestTimerLocked(effects)
                testActive = true
                val newTestHandle = TimerHandle()
                testHandle = newTestHandle
                effects.testTimerToSchedule = newTestHandle

                // หากเซสชันเดิมค้างอยู่ที่ NO_DATA และผู้ใช้กดทดสอบเอง ให้เริ่มรอบใหม่ทันที
                if (activeSession != null && latestStatus.phase == LiveObd.Phase.NO_DATA) {
                    detachSessionLocked(effects)
                }
                reconcileLocked(effects)
            }
        }
        executeEffects(effects)
        return true
    }

    fun cancelManualTest(next: ObdDemand) {
        val effects = SideEffects()
        synchronized(stateLock) {
            applyDemandLocked(next, effects)
            testActive = false
            cancelTestTimerLocked(effects)
            reconcileLocked(effects)
        }
        executeEffects(effects)
    }

    /** Cancels all internal coroutines and closes any open connection asynchronously (for tests). */
    fun shutdown() {
        val effects = SideEffects()
        synchronized(stateLock) {
            stopActiveWorkLocked(effects, clearTest = true)
            updateWorkFlagLocked()
        }
        executeEffects(effects)
        scope.cancel()
        closeScope.cancel()
    }

    private fun applyDemandLocked(next: ObdDemand, effects: SideEffects) {
        val addressChanged = next.selectedAddress != demand.selectedAddress
        demand = next
        if (addressChanged) {
            stopActiveWorkLocked(effects, clearTest = true)
            consecutiveFailures = 0
            if (latestInventory != null) {
                latestInventory = null
                effects.inventoryChanged = true
            }
        }
        if (!next.appVisible && (testActive || testHandle != null)) {
            testActive = false
            cancelTestTimerLocked(effects)
        }
    }

    private fun reconcileLocked(effects: SideEffects) {
        val address = demand.selectedAddress
        val shouldRead = demand.dashboardNeedsObd || (demand.appVisible && testActive)
        if (!shouldRead || address == null || !demand.hasPermission) {
            stopActiveWorkLocked(effects, clearTest = false)
            consecutiveFailures = 0
            radioSkipLogged = false
            publishLocked(LiveObd.Phase.IDLE, effects = effects)
            updateWorkFlagLocked()
            return
        }

        if (!transport.isRadioAllowed()) {
            stopActiveWorkLocked(effects, clearTest = !demand.dashboardNeedsObd)
            recordRadioSkipLocked(effects)
            publishLocked(LiveObd.Phase.IDLE, effects = effects)
            updateWorkFlagLocked()
            return
        }

        radioSkipLogged = false
        if (activeSession != null || retryHandle != null) {
            updateWorkFlagLocked()
            return
        }

        val settleUntil = if (demand.projectionStreaming && demand.streamingEnteredAtMs > 0L) {
            demand.streamingEnteredAtMs + config.postFirstFrameSettleMs
        } else {
            0L
        }
        val session = ActiveSession(address = address, settleUntilMs = settleUntil)
        activeSession = session
        publishLocked(LiveObd.Phase.CONNECTING, effects = effects)
        effects.sessionToLaunch = session
        updateWorkFlagLocked()
    }

    private fun stopActiveWorkLocked(effects: SideEffects, clearTest: Boolean) {
        cancelRetryLocked(effects)
        if (clearTest) {
            testActive = false
            cancelTestTimerLocked(effects)
        }
        detachSessionLocked(effects)
    }

    private fun cancelRetryLocked(effects: SideEffects) {
        retryHandle?.job?.let { effects.jobsToCancel.add(it) }
        retryHandle = null
    }

    private fun cancelTestTimerLocked(effects: SideEffects) {
        testHandle?.job?.let { effects.jobsToCancel.add(it) }
        testHandle = null
    }

    private fun detachSessionLocked(effects: SideEffects) {
        val previous = activeSession ?: return
        activeSession = null
        previous.cancelled = true
        previous.connection?.let { effects.connectionsToClose.add(it) }
        previous.connection = null
        previous.job?.let { effects.jobsToCancel.add(it) }
        previous.job = null
    }

    private fun recordRadioSkipLocked(effects: SideEffects) {
        if (!radioSkipLogged) {
            radioSkipLogged = true
            effects.logs.add("[OBD] skipped reason=radio_busy")
        }
    }

    private fun publishLocked(
        phase: LiveObd.Phase,
        sample: ObdSample = ObdSample(),
        effects: SideEffects,
    ) {
        if (latestStatus.phase != phase) {
            effects.logs.add("[OBD] ${phase.name}")
        }
        val now = monotonicMillis()
        val fresh = sample.freshAt(now)
        if (fresh.hasData) {
            effects.sampleLog = "[OBD] sample rpm=${fresh.rpm ?: "unavailable"}" +
                " coolant_c=${fresh.coolant ?: "unavailable"}" +
                " ecu_voltage_v=${fresh.volt ?: "unavailable"}" +
                " load_pct=${fresh.load ?: "unavailable"}" +
                " intake_c=${fresh.intake ?: "unavailable"}" +
                " map_kpa=${fresh.manifoldPressureKpa ?: "unavailable"}" +
                " relative_throttle_pct=${fresh.relativeThrottle ?: "unavailable"}"
        }
        latestStatus = LiveObd.Status(phase, fresh, if (fresh.hasData) now else 0L)
        effects.statusChanged = true
    }

    private fun updateWorkFlagLocked() {
        activeWorkFlag = activeSession != null || retryHandle != null || testActive
    }

    private fun executeEffects(effects: SideEffects) {
        if (effects.statusChanged) {
            var snap = latestStatus
            mutableStatus.value = snap
            while (latestStatus !== snap) {
                snap = latestStatus
                mutableStatus.value = snap
            }
        }
        if (effects.inventoryChanged) {
            var snap = latestInventory
            mutableInventory.value = snap
            while (latestInventory !== snap) {
                snap = latestInventory
                mutableInventory.value = snap
            }
        }
        for (job in effects.jobsToCancel) {
            job.cancel()
        }
        for (connection in effects.connectionsToClose) {
            closeAsync(connection)
        }
        for (line in effects.logs) {
            emitLog(line)
        }
        effects.sampleLog?.let { emitSampleLog(it) }
        effects.testTimerToSchedule?.let { handle ->
            launchTestTimer(handle)
        }
        effects.sessionToLaunch?.let { session ->
            launchSession(session)
        }
        effects.retryToSchedule?.let { (handle, delayMs) ->
            launchRetryTimer(handle, delayMs)
        }
    }

    private fun launchTestTimer(handle: TimerHandle) {
        val job = scope.launch {
            pause(config.manualTestWindowMs)
            val effects = SideEffects()
            synchronized(stateLock) {
                if (testHandle === handle) {
                    testHandle = null
                    testActive = false
                    reconcileLocked(effects)
                }
            }
            executeEffects(effects)
        }
        var shouldCancel = false
        synchronized(stateLock) {
            if (testHandle === handle) {
                handle.job = job
            } else {
                shouldCancel = true
            }
            updateWorkFlagLocked()
        }
        if (shouldCancel) job.cancel()
    }

    private fun launchSession(session: ActiveSession) {
        val job = scope.launch { runSession(session) }
        var shouldCancel = false
        synchronized(stateLock) {
            if (activeSession === session && !session.cancelled) {
                session.job = job
            } else {
                shouldCancel = true
            }
            updateWorkFlagLocked()
        }
        if (shouldCancel) job.cancel()
    }

    private fun launchRetryTimer(handle: TimerHandle, delayMs: Long) {
        val job = scope.launch {
            val radioOk = waitRetryDelayCheckingRadio(delayMs)
            val effects = SideEffects()
            synchronized(stateLock) {
                if (retryHandle === handle) {
                    retryHandle = null
                    if (!radioOk) {
                        stopActiveWorkLocked(effects, clearTest = !demand.dashboardNeedsObd)
                        recordRadioSkipLocked(effects)
                        publishLocked(LiveObd.Phase.IDLE, effects = effects)
                        updateWorkFlagLocked()
                    } else {
                        reconcileLocked(effects)
                    }
                }
            }
            executeEffects(effects)
        }
        var shouldCancel = false
        synchronized(stateLock) {
            if (retryHandle === handle) {
                handle.job = job
            } else {
                shouldCancel = true
            }
            updateWorkFlagLocked()
        }
        if (shouldCancel) job.cancel()
    }

    private suspend fun waitRetryDelayCheckingRadio(delayMs: Long): Boolean {
        val deadline = monotonicMillis() + delayMs
        while (coroutineContext.isActive) {
            if (!transport.isRadioAllowed()) return false
            val remaining = deadline - monotonicMillis()
            if (remaining <= 0L) return transport.isRadioAllowed()
            pause(minOf(remaining, config.radioPollIntervalMs))
        }
        return false
    }

    private suspend fun runSession(session: ActiveSession) {
        var endPhase = LiveObd.Phase.FAILED
        var stage = "adapter_lookup"
        var radioAborted = false
        try {
            val settleMs = (session.settleUntilMs - monotonicMillis()).coerceAtLeast(0L)
            if (settleMs > 0L) {
                emitLogIfCurrent(session, "[OBD] settle_wait delay_ms=$settleMs")
                pauseWhileRadioAllowed(session, settleMs)
            }
            ensureRadioAndCurrent(session)

            stage = "bluetooth_connect"
            val connection = connectWithSingleFallback(session)
            if (connection == null) {
                endPhase = LiveObd.Phase.UNAVAILABLE
                return
            }

            publishIfCurrent(session, LiveObd.Phase.WAITING_FOR_ECU)
            stage = "elm_init"
            val reader = Elm327Telemetry(
                input = connection.input,
                output = connection.output,
                monotonicMillis = monotonicMillis,
                pause = pause,
                config = config.elmConfig,
            ) { diagnostic ->
                recordDiagnosticIfCurrent(session, diagnostic)
            }
            ensureRadioAndCurrent(session)
            val initResult = reader.initialize()
            if (initResult.resyncedBanner || initResult.retriedCommands > 0) {
                emitLogIfCurrent(
                    session,
                    "[OBD] elm_init=recovered banner_resync=${initResult.resyncedBanner} retries=${initResult.retriedCommands}",
                )
            }

            ensureRadioAndCurrent(session)
            stage = "mode01_inventory"
            var inventory = reader.inventory()
            recordInventoryIfCurrent(session, inventory)
            val inventoryRetry = ObdInventoryRetry(reader, inventory)
            var secondaryPids = inventory.secondaryPids()
            var secondaryIndex = 0
            var sample = ObdSample()
            var firstRead = true
            var waitingSinceMs: Long? = monotonicMillis()
            var lastInventoryReprobeAtMs = monotonicMillis()
            var noDataCycles = 0

            while (coroutineContext.isActive && isSessionCurrent(session)) {
                ensureRadioAndCurrent(session)
                val cycleStartedAt = monotonicMillis()

                stage = "mode01_rpm"
                val rpm = reader.read(
                    ObdPid.RPM,
                    if (firstRead) config.firstReadTimeoutMs else config.readTimeoutMs,
                )
                firstRead = false
                ensureRadioAndCurrent(session)

                val nowAfterRpm = monotonicMillis()
                sample = sample.withReading(ObdPid.RPM, rpm, nowAfterRpm).freshAt(nowAfterRpm)

                if (sample.hasData) {
                    waitingSinceMs = null
                    noDataCycles = 0
                    markLiveReached(session)
                    publishIfCurrent(session, LiveObd.Phase.LIVE, sample)

                    if (inventory.incomplete && inventoryRetry.startIfNeeded(rpm)) {
                        emitLogIfCurrent(session, "[OBD] inventory_retry=started max_ranges=3")
                    }
                    if (inventoryRetry.nextRange != null) {
                        stage = "mode01_inventory_retry"
                        try {
                            inventoryRetry.probeNext()
                        } catch (e: TimeoutCancellationException) {
                            recordInventoryIfCurrent(session, inventoryRetry.inventory)
                            throw e
                        } catch (e: IOException) {
                            recordInventoryIfCurrent(session, inventoryRetry.inventory)
                            throw e
                        }
                        inventory = inventoryRetry.inventory
                        recordInventoryIfCurrent(session, inventory)
                        secondaryPids = inventory.secondaryPids()
                        if (secondaryPids.isNotEmpty()) secondaryIndex %= secondaryPids.size
                    } else if (secondaryPids.isNotEmpty()) {
                        val pid = secondaryPids[secondaryIndex]
                        secondaryIndex = (secondaryIndex + 1) % secondaryPids.size
                        stage = "mode01_${pid.code.lowercase()}"
                        val value = reader.read(pid, config.readTimeoutMs)
                        ensureRadioAndCurrent(session)
                        val nowAfterSecondary = monotonicMillis()
                        sample = sample.withReading(pid, value, nowAfterSecondary).freshAt(nowAfterSecondary)
                        if (sample.hasData) {
                            publishIfCurrent(session, LiveObd.Phase.LIVE, sample)
                        } else {
                            waitingSinceMs = nowAfterSecondary
                            publishIfCurrent(session, LiveObd.Phase.WAITING_FOR_ECU)
                        }
                    } else {
                        val wait = config.livePollIntervalMs - (monotonicMillis() - cycleStartedAt)
                        if (wait > 0L) pauseWhileRadioAllowed(session, wait)
                    }
                } else {
                    if (noDataCycles == 0 && secondaryPids.isNotEmpty()) {
                        val pid = secondaryPids[secondaryIndex]
                        secondaryIndex = (secondaryIndex + 1) % secondaryPids.size
                        stage = "mode01_${pid.code.lowercase()}"
                        val value = reader.read(pid, config.readTimeoutMs)
                        ensureRadioAndCurrent(session)
                        val nowAfterSecondary = monotonicMillis()
                        sample = sample.withReading(pid, value, nowAfterSecondary).freshAt(nowAfterSecondary)
                        if (sample.hasData) {
                            waitingSinceMs = null
                            noDataCycles = 0
                            markLiveReached(session)
                            publishIfCurrent(session, LiveObd.Phase.LIVE, sample)
                            continue
                        }
                    }

                    val nowWaiting = monotonicMillis()
                    if (waitingSinceMs == null) waitingSinceMs = nowWaiting
                    noDataCycles++

                    val waitElapsed = (nowWaiting - waitingSinceMs).coerceAtLeast(0L)
                    val displayPhase = if (waitElapsed >= config.noDataGraceMs) {
                        LiveObd.Phase.NO_DATA
                    } else {
                        LiveObd.Phase.WAITING_FOR_ECU
                    }
                    publishIfCurrent(session, displayPhase)

                    // คงลิงก์ Bluetooth SPP เดิมที่ปกติไว้และเช็ก 0100 เป็นระยะจน ECU ตื่น
                    if (inventory.missingRange == 0 &&
                        nowWaiting - lastInventoryReprobeAtMs >= config.ecuInventoryReprobeIntervalMs
                    ) {
                        ensureRadioAndCurrent(session)
                        stage = "mode01_inventory"
                        lastInventoryReprobeAtMs = nowWaiting
                        val refreshed = reader.inventory()
                        val merged = inventory.merge(refreshed)
                        if (merged != inventory) {
                            inventory = merged
                            recordInventoryIfCurrent(session, inventory)
                            secondaryPids = inventory.secondaryPids()
                            if (secondaryPids.isNotEmpty()) secondaryIndex %= secondaryPids.size
                        }
                    }

                    val targetInterval = if (displayPhase == LiveObd.Phase.NO_DATA) {
                        config.noDataPollIntervalMs
                    } else {
                        config.ecuWaitPollIntervalMs
                    }
                    val remaining = targetInterval - (monotonicMillis() - cycleStartedAt)
                    if (remaining > 0L) pauseWhileRadioAllowed(session, remaining)
                }
            }
        } catch (_: RadioBusyAbortException) {
            radioAborted = true
            emitLogIfCurrent(session, "[OBD] aborted reason=radio_busy")
        } catch (_: ConnectTimeoutException) {
            emitLogIfCurrent(session, "[OBD] failure stage=$stage reason=timeout")
        } catch (_: TimeoutCancellationException) {
            emitLogIfCurrent(session, "[OBD] failure stage=$stage reason=timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            emitLogIfCurrent(session, "[OBD] failure stage=$stage reason=io_error")
        } catch (_: SecurityException) {
            emitLogIfCurrent(session, "[OBD] failure stage=$stage reason=permission")
            endPhase = LiveObd.Phase.UNAVAILABLE
        } finally {
            finishSession(session, endPhase, radioAborted)
        }
    }

    private suspend fun connectWithSingleFallback(session: ActiveSession): ObdConnection? {
        when (val first = connectSingle(session, ObdRfcommMode.SECURE)) {
            is ConnectOutcome.Connected -> return first.connection
            ConnectOutcome.Unavailable -> return null
            is ConnectOutcome.Failed -> {
                ensureRadioAndCurrent(session)
                if (config.rfcommFallbackSettleMs > 0L) {
                    pauseWhileRadioAllowed(session, config.rfcommFallbackSettleMs)
                }
                ensureRadioAndCurrent(session)
                emitLogIfCurrent(session, "[OBD] rfcomm_fallback=insecure")
                return when (val fallback = connectSingle(session, ObdRfcommMode.INSECURE)) {
                    is ConnectOutcome.Connected -> fallback.connection
                    ConnectOutcome.Unavailable -> null
                    is ConnectOutcome.Failed -> {
                        if (fallback.timedOut) throw ConnectTimeoutException()
                        throw IOException("RFCOMM connect failed")
                    }
                }
            }
        }
    }

    private suspend fun connectSingle(
        session: ActiveSession,
        mode: ObdRfcommMode,
    ): ConnectOutcome {
        ensureRadioAndCurrent(session)
        val connection = try {
            transport.openConnection(session.address, mode)
        } catch (_: IOException) {
            ensureRadioAndCurrent(session)
            return ConnectOutcome.Failed(timedOut = false)
        } ?: return ConnectOutcome.Unavailable
        if (!attachConnection(session, connection)) {
            closeAsync(connection)
            throw CancellationException("Session superseded")
        }
        val timedOut = AtomicBoolean(false)
        val radioAborted = AtomicBoolean(false)
        return try {
            coroutineScope {
                val watcher = launch {
                    val startedAt = monotonicMillis()
                    while (isActive) {
                        if (!transport.isRadioAllowed()) {
                            radioAborted.set(true)
                            closeAsync(connection)
                            break
                        }
                        val elapsed = (monotonicMillis() - startedAt).coerceAtLeast(0L)
                        if (elapsed >= config.connectTimeoutMs) {
                            timedOut.set(true)
                            closeAsync(connection)
                            break
                        }
                        val step = minOf(config.radioPollIntervalMs, config.connectTimeoutMs - elapsed)
                        pause(step.coerceAtLeast(1L))
                    }
                }
                try {
                    ensureRadioAndCurrent(session)
                    connection.connect()
                } finally {
                    watcher.cancel()
                }
            }
            if (radioAborted.get() || !transport.isRadioAllowed()) {
                detachConnectionAndClose(session, connection)
                throw RadioBusyAbortException()
            }
            if (timedOut.get()) {
                detachConnectionAndClose(session, connection)
                ConnectOutcome.Failed(timedOut = true)
            } else {
                ConnectOutcome.Connected(connection)
            }
        } catch (e: RadioBusyAbortException) {
            detachConnectionAndClose(session, connection)
            throw e
        } catch (e: CancellationException) {
            detachConnectionAndClose(session, connection)
            throw e
        } catch (_: IOException) {
            detachConnectionAndClose(session, connection)
            if (radioAborted.get() || !transport.isRadioAllowed()) throw RadioBusyAbortException()
            ConnectOutcome.Failed(timedOut = timedOut.get())
        }
    }

    private suspend fun pauseWhileRadioAllowed(session: ActiveSession, durationMs: Long) {
        val deadline = monotonicMillis() + durationMs
        while (coroutineContext.isActive) {
            ensureRadioAndCurrent(session)
            val remaining = deadline - monotonicMillis()
            if (remaining <= 0L) break
            pause(minOf(remaining, config.radioPollIntervalMs))
        }
        ensureRadioAndCurrent(session)
    }

    private fun ensureRadioAndCurrent(session: ActiveSession) {
        if (!isSessionCurrent(session)) throw CancellationException("Session superseded")
        if (!transport.isRadioAllowed()) throw RadioBusyAbortException()
    }

    private fun isSessionCurrent(session: ActiveSession): Boolean = synchronized(stateLock) {
        activeSession === session && !session.cancelled
    }

    private fun attachConnection(session: ActiveSession, connection: ObdConnection): Boolean = synchronized(stateLock) {
        if (activeSession !== session || session.cancelled) return false
        session.connection = connection
        true
    }

    private fun detachConnectionAndClose(session: ActiveSession, connection: ObdConnection) {
        synchronized(stateLock) {
            if (session.connection === connection) {
                session.connection = null
            }
        }
        closeAsync(connection)
    }

    private fun markLiveReached(session: ActiveSession) {
        synchronized(stateLock) {
            if (activeSession === session && !session.cancelled) {
                consecutiveFailures = 0
            }
        }
    }

    private fun publishIfCurrent(
        session: ActiveSession,
        phase: LiveObd.Phase,
        sample: ObdSample = ObdSample(),
    ) {
        val effects = SideEffects()
        synchronized(stateLock) {
            if (activeSession !== session || session.cancelled) return
            publishLocked(phase, sample, effects)
        }
        executeEffects(effects)
    }

    private fun recordDiagnosticIfCurrent(session: ActiveSession, diagnostic: ObdCommandDiagnostic) {
        if (!isSessionCurrent(session)) return
        emitLog(buildString {
            append("[OBD] command=").append(diagnostic.command.request)
            append(" result=").append(diagnostic.result.name.lowercase())
            if (diagnostic.result == ObdDiagnosticResult.UNRECOGNIZED_HEX) {
                append(" hex_chars=").append(diagnostic.hexChars)
                append(" prefix_chars=").append(diagnostic.prefixChars ?: "none")
            }
        })
    }

    private fun recordInventoryIfCurrent(session: ActiveSession, result: ObdInventory) {
        val effects = SideEffects()
        synchronized(stateLock) {
            if (activeSession !== session || session.cancelled) return
            latestInventory = result
            effects.inventoryChanged = true
            val ids = result.pids.joinToString(" ") { it.obdHex() }
            val missing = result.missingRange?.obdHex() ?: "none"
            val pending = result.unqueriedRange?.let { " unqueried_range=${it.obdHex()}" }.orEmpty()
            effects.logs.add("[OBD] Mode 01 supported_pids=[$ids] missing_range=$missing$pending")
        }
        executeEffects(effects)
    }

    private fun finishSession(session: ActiveSession, endPhase: LiveObd.Phase, radioAborted: Boolean) {
        val effects = SideEffects()
        synchronized(stateLock) {
            if (activeSession !== session) {
                session.connection?.let { effects.connectionsToClose.add(it) }
                session.connection = null
            } else {
                activeSession = null
                session.cancelled = true
                session.connection?.let { effects.connectionsToClose.add(it) }
                session.connection = null

                if (radioAborted) {
                    if (!demand.dashboardNeedsObd) {
                        testActive = false
                        cancelTestTimerLocked(effects)
                    }
                    publishLocked(LiveObd.Phase.IDLE, effects = effects)
                } else {
                    publishLocked(endPhase, effects = effects)
                    val eligibleAddress = demand.selectedAddress != null && demand.hasPermission
                    val dashboardRetry = demand.dashboardNeedsObd && eligibleAddress
                    val testRetry = !dashboardRetry &&
                        testActive &&
                        demand.appVisible &&
                        eligibleAddress &&
                        endPhase != LiveObd.Phase.UNAVAILABLE

                    if ((dashboardRetry || testRetry) && transport.isRadioAllowed()) {
                        val delayMs = if (endPhase == LiveObd.Phase.UNAVAILABLE) {
                            config.unavailableRetryMs
                        } else {
                            consecutiveFailures++
                            val backoff = config.retryBackoffMs
                            if (backoff.isEmpty()) config.unavailableRetryMs
                            else backoff[(consecutiveFailures - 1).coerceAtMost(backoff.lastIndex)]
                        }
                        effects.logs.add("[OBD] retry_scheduled delay_ms=$delayMs attempt=$consecutiveFailures")
                        val handle = TimerHandle()
                        retryHandle = handle
                        effects.retryToSchedule = handle to delayMs
                    } else {
                        if (!dashboardRetry) {
                            testActive = false
                            cancelTestTimerLocked(effects)
                        }
                        if ((dashboardRetry || testRetry) && !transport.isRadioAllowed()) {
                            recordRadioSkipLocked(effects)
                        }
                    }
                }
                updateWorkFlagLocked()
            }
        }
        executeEffects(effects)
    }

    private fun emitLogIfCurrent(session: ActiveSession, line: String) {
        if (isSessionCurrent(session)) emitLog(line)
    }

    private fun emitLog(line: String) {
        runCatching { onLog(line) }
    }

    private fun emitSampleLog(line: String) {
        runCatching { onSampleLog(line) }
    }

    private fun closeAsync(connection: ObdConnection) {
        closeScope.launch {
            runCatching { connection.close() }
        }
    }
}
