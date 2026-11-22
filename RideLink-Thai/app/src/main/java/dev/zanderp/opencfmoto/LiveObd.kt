package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import dev.zanderp.opencfmoto.Phase as ProjectionPhase
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException

internal object LiveObd {
    enum class Phase { IDLE, CONNECTING, WAITING_FOR_ECU, LIVE, NO_DATA, UNAVAILABLE, FAILED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val sample: ObdSample = ObdSample(),
        val receivedAt: Long = 0L,
    )

    private class Session {
        var job: Job? = null
        var socket: BluetoothSocket? = null
    }

    private val serialPort = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()
    private val mutableInventory = MutableStateFlow<ObdInventory?>(null)
    val inventory = mutableInventory.asStateFlow()
    private var session: Session? = null
    private var retry: Job? = null
    private var appVisible = false
    private var test: Job? = null
    @Volatile private var dashboardApp: Context? = null

    private fun prefs(context: Context) = context.getSharedPreferences("tft_studio", Context.MODE_PRIVATE)

    fun selectedAddress(context: Context): String? = prefs(context).getString("live_obd_address", null)

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
            .sortedBy { it.name.orEmpty() }
    }

    @Synchronized
    fun select(context: Context, address: String?) {
        if (address != selectedAddress(context)) {
            clearTest()
            stopCapture()
            mutableInventory.value = null
            prefs(context).edit().putString("live_obd_address", address).apply()
        }
        reconcile(context.applicationContext)
    }

    @Synchronized
    fun setAppVisible(context: Context, visible: Boolean) {
        appVisible = visible
        if (!visible) clearTest()
        reconcile(context.applicationContext)
    }

    @Synchronized
    fun setDashboardActive(context: Context, active: Boolean) {
        val app = context.applicationContext
        dashboardApp = if (active) app else null
        reconcile(app)
    }

    fun onProjectionStateChanged() {
        val app = dashboardApp ?: return
        scope.launch {
            synchronized(this@LiveObd) {
                if (dashboardApp === app) reconcile(app)
            }
        }
    }

    @Synchronized
    fun startTest(context: Context): Boolean {
        val app = context.applicationContext
        if (selectedAddress(app) == null || !hasPermission(app) || !appVisible) return false
        clearTest()
        test = scope.launch {
            delay(45_000L)
            synchronized(this@LiveObd) {
                if (isActive) {
                    test = null
                    reconcile(app)
                }
            }
        }
        reconcile(app)
        return true
    }

    @Synchronized
    fun cancelTest(context: Context) {
        if (test == null) return
        clearTest()
        reconcile(context.applicationContext)
    }

    private fun clearTest() {
        test?.cancel()
        test = null
    }

    private fun dashboardNeedsObd() = dashboardApp != null && ConnectionState.phase == ProjectionPhase.STREAMING

    private fun reconcile(app: Context) {
        val address = selectedAddress(app)
        val shouldRead = dashboardNeedsObd() || (appVisible && test != null)
        if (!shouldRead || address == null || !hasPermission(app)) {
            stopCapture()
            return
        }
        if (session != null || retry != null) return
        val current = Session()
        session = current
        publish(Phase.CONNECTING)
        current.job = scope.launch { readSession(app, address, current) }
    }

    private fun stopCapture() {
        retry?.cancel()
        retry = null
        val previous = session
        session = null
        previous?.socket?.let(::close)
        previous?.job?.cancel()
        publish(Phase.IDLE)
    }

    @SuppressLint("MissingPermission")
    private suspend fun readSession(app: Context, address: String, current: Session) {
        var end = Phase.FAILED
        var stage = "adapter_lookup"
        try {
            val device = pairedDevices(app).firstOrNull { it.address == address }
            if (device == null) {
                end = Phase.UNAVAILABLE
                return
            }
            stage = "bluetooth_connect"
            val socket = device.createRfcommSocketToServiceRecord(serialPort)
            if (!attach(current, socket)) {
                close(socket)
                return
            }
            end = coroutineScope {
                val connectTimer = launch {
                    delay(12_000)
                    close(socket) // ปลดการรอ connect() ที่กำลังบล็อก
                }
                try {
                    socket.connect()
                } finally {
                    connectTimer.cancel()
                }
                update(current, Phase.WAITING_FOR_ECU)
                stage = "elm_init"
                val reader = Elm327Telemetry(socket.inputStream, socket.outputStream,
                    monotonicMillis = SystemClock::elapsedRealtime) {
                    recordDiagnostic(current, it)
                }
                reader.initialize()
                stage = "mode01_inventory"
                val inventory = reader.inventory()
                recordInventory(current, inventory)
                val inventoryRetry = ObdInventoryRetry(reader, inventory)
                var secondaryPids = inventory.secondaryPids()
                var misses = 0
                var sample = ObdSample()
                var secondaryIndex = 0
                var firstRead = true
                while (isActive && misses < 3) {
                    val rpmStartedAt = SystemClock.elapsedRealtime()
                    stage = "mode01_rpm"
                    val rpm = reader.read(ObdPid.RPM, if (firstRead) 8_000 else 2_500)
                    firstRead = false
                    sample = sample.withReading(ObdPid.RPM, rpm, SystemClock.elapsedRealtime())
                        .freshAt(SystemClock.elapsedRealtime())
                    update(current, if (sample.hasData) Phase.LIVE else Phase.WAITING_FOR_ECU, sample)

                    if (!isActive) break
                    if (inventoryRetry.startIfNeeded(rpm)) {
                        LogBus.log("[OBD] inventory_retry=started max_ranges=3")
                    }
                    if (inventoryRetry.nextRange != null) {
                        stage = "mode01_inventory_retry"
                        try {
                            inventoryRetry.probeNext()
                        } catch (e: TimeoutCancellationException) {
                            recordInventory(current, inventoryRetry.inventory)
                            throw e
                        } catch (e: IOException) {
                            recordInventory(current, inventoryRetry.inventory)
                            throw e
                        }
                        recordInventory(current, inventoryRetry.inventory)
                        secondaryPids = inventoryRetry.inventory.secondaryPids()
                        if (secondaryPids.isNotEmpty()) secondaryIndex %= secondaryPids.size
                    } else if (secondaryPids.isNotEmpty()) {
                        val pid = secondaryPids[secondaryIndex]
                        secondaryIndex = (secondaryIndex + 1) % secondaryPids.size
                        stage = "mode01_${pid.code.lowercase()}"
                        val value = reader.read(pid)
                        sample = sample.withReading(pid, value, SystemClock.elapsedRealtime())
                            .freshAt(SystemClock.elapsedRealtime())
                        update(current, if (sample.hasData) Phase.LIVE else Phase.WAITING_FOR_ECU, sample)
                    } else {
                        val wait = 1_000L - (SystemClock.elapsedRealtime() - rpmStartedAt)
                        if (wait > 0L) delay(wait)
                    }
                    misses = if (sample.hasData) 0 else misses + 1
                }
                Phase.NO_DATA
            }
        } catch (_: TimeoutCancellationException) {
            recordFailure(current, stage, "timeout")
        } catch (_: IOException) {
            recordFailure(current, stage, "io_error")
        } catch (_: SecurityException) {
            recordFailure(current, stage, "permission")
            end = Phase.UNAVAILABLE
        } finally {
            finish(app, current, end)
        }
    }

    @Synchronized
    private fun attach(current: Session, socket: BluetoothSocket): Boolean {
        if (session !== current) return false
        current.socket = socket
        return true
    }

    @Synchronized
    private fun update(current: Session, phase: Phase, sample: ObdSample = ObdSample()) {
        if (session === current) publish(phase, sample)
    }

    @Synchronized
    private fun recordDiagnostic(current: Session, diagnostic: ObdCommandDiagnostic) {
        if (session !== current) return
        LogBus.log(buildString {
            append("[OBD] command=").append(diagnostic.command.request)
            append(" result=").append(diagnostic.result.name.lowercase())
            if (diagnostic.result == ObdDiagnosticResult.UNRECOGNIZED_HEX) {
                append(" hex_chars=").append(diagnostic.hexChars)
                append(" prefix_chars=").append(diagnostic.prefixChars ?: "none")
            }
        })
    }

    @Synchronized
    private fun recordFailure(current: Session, stage: String, reason: String) {
        if (session === current) LogBus.log("[OBD] failure stage=$stage reason=$reason")
    }

    @Synchronized
    private fun recordInventory(current: Session, result: ObdInventory) {
        if (session !== current) return
        mutableInventory.value = result
        val ids = result.pids.joinToString(" ") { it.obdHex() }
        val missing = result.missingRange?.obdHex() ?: "none"
        val pending = result.unqueriedRange?.let { " unqueried_range=${it.obdHex()}" }.orEmpty()
        LogBus.log("[OBD] Mode 01 supported_pids=[$ids] missing_range=$missing$pending")
    }

    @Synchronized
    private fun finish(app: Context, current: Session, phase: Phase) {
        if (session !== current) return
        session = null
        current.socket?.let(::close)
        publish(phase)
        if (!dashboardNeedsObd()) {
            clearTest()
            return
        }
        retry = scope.launch {
            delay(30_000)
            synchronized(this@LiveObd) {
                if (isActive) {
                    retry = null
                    reconcile(app)
                }
            }
        }
    }

    private fun publish(phase: Phase, sample: ObdSample = ObdSample()) {
        if (mutableStatus.value.phase != phase) LogBus.log("[OBD] ${phase.name}")
        val now = SystemClock.elapsedRealtime()
        val fresh = sample.freshAt(now)
        if (fresh.hasData) {
            LogBus.logThrottled(
                "obd_sample",
                "[OBD] sample rpm=${fresh.rpm ?: "unavailable"}" +
                    " coolant_c=${fresh.coolant ?: "unavailable"}" +
                    " ecu_voltage_v=${fresh.volt ?: "unavailable"}" +
                    " load_pct=${fresh.load ?: "unavailable"}" +
                    " intake_c=${fresh.intake ?: "unavailable"}" +
                    " map_kpa=${fresh.manifoldPressureKpa ?: "unavailable"}" +
                    " relative_throttle_pct=${fresh.relativeThrottle ?: "unavailable"}",
                minIntervalMs = 5_000L,
            )
        }
        mutableStatus.value = Status(phase, fresh, if (fresh.hasData) now else 0L)
    }

    private fun close(socket: BluetoothSocket) {
        runCatching { socket.close() }
    }
}
