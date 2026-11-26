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
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Thin Android adapter over [ObdSessionController].
 * Preserves the exact caller ABI used by [ConnectionState], [MainActivity],
 * [RideScreenLiveDashboard], and [LiveDashboardStudio], while keeping all Bluetooth IPC,
 * socket teardown, and diagnostic logging strictly off the Main Thread and outside any lock.
 */
internal object LiveObd {
    enum class Phase { IDLE, CONNECTING, WAITING_FOR_ECU, LIVE, NO_DATA, UNAVAILABLE, FAILED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val sample: ObdSample = ObdSample(),
        val receivedAt: Long = 0L,
    )

    private val serialPort = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val logExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ridelink-obd-log").apply { isDaemon = true }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var appVisible: Boolean = false
    @Volatile private var dashboardApp: Context? = null
    @Volatile private var lastStreamingEnteredAtMs: Long = 0L

    private class AndroidObdConnection(private val socket: BluetoothSocket) : ObdConnection {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream

        @SuppressLint("MissingPermission")
        override suspend fun connect() {
            withContext(Dispatchers.IO) { socket.connect() }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private val transport = object : ObdTransport {
        override fun isRadioAllowed(): Boolean {
            val phase = ConnectionState.phase
            if (phase.busy) return false
            return !SessionService.isProjectionRequested || phase == ProjectionPhase.STREAMING
        }

        @SuppressLint("MissingPermission")
        override fun openConnection(address: String, mode: ObdRfcommMode): ObdConnection? {
            val app = appContext ?: return null
            if (!hasPermission(app)) throw SecurityException("BLUETOOTH_CONNECT missing")
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            if (!adapter.isEnabled) return null
            // ค้นหาจากรายการที่จับคู่แล้วโดยไม่ตัด dual-mode ที่ Android แคชชนิดเป็น LE ชั่วคราว
            // และห้ามสั่งหยุดการค้นหา Bluetooth เนื่องจากแอปไม่มีสิทธิ์ BLUETOOTH_SCAN
            val device = adapter.bondedDevices
                .firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?: return null
            val socket = when (mode) {
                ObdRfcommMode.SECURE -> device.createRfcommSocketToServiceRecord(serialPort)
                ObdRfcommMode.INSECURE -> device.createInsecureRfcommSocketToServiceRecord(serialPort)
            }
            return AndroidObdConnection(socket)
        }
    }

    private val controller = ObdSessionController(
        transport = transport,
        monotonicMillis = { SystemClock.elapsedRealtime() },
        onLog = { line -> logExecutor.execute { LogBus.log(line) } },
        onSampleLog = { line ->
            logExecutor.execute { LogBus.logThrottled("obd_sample", line, minIntervalMs = 5_000L) }
        },
    )

    val status: StateFlow<Status> = controller.status
    val inventory: StateFlow<ObdInventory?> = controller.inventory

    private fun prefs(context: Context) = context.getSharedPreferences("tft_studio", Context.MODE_PRIVATE)

    fun selectedAddress(context: Context): String? = prefs(context).getString("live_obd_address", null)

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        val selected = selectedAddress(context)
        return adapter.bondedDevices.filter { device ->
            device.type != BluetoothDevice.DEVICE_TYPE_LE ||
                device.address.equals(selected, ignoreCase = true) ||
                device.uuids?.any { it.uuid == serialPort } == true
        }
            .sortedBy { it.name.orEmpty() }
    }

    fun select(context: Context, address: String?) {
        val app = context.applicationContext
        appContext = app
        val changed = address != selectedAddress(app)
        if (changed) {
            prefs(app).edit().putString("live_obd_address", address).apply()
        }
        if (changed || dashboardApp != null || controller.hasActiveOrPendingWork()) {
            controller.updateDemand(snapshotDemand(app))
        }
    }

    fun setAppVisible(context: Context, visible: Boolean) {
        val app = context.applicationContext
        appContext = app
        appVisible = visible
        // เมื่อไม่ได้เปิดหน้าปัดสดและไม่ได้ทดสอบ OBD ห้ามแตะ Bluetooth หรือสร้างงานเบื้องหลังระหว่าง onStart/onStop
        if (dashboardApp == null && !controller.hasActiveOrPendingWork()) return
        controller.updateDemand(snapshotDemand(app))
    }

    fun setDashboardActive(context: Context, active: Boolean) {
        val app = context.applicationContext
        appContext = app
        dashboardApp = if (active) app else null
        controller.updateDemand(snapshotDemand(app))
    }

    fun onProjectionStateChanged() {
        val streaming = ConnectionState.phase == ProjectionPhase.STREAMING
        val now = if (streaming) SystemClock.elapsedRealtime() else 0L
        lastStreamingEnteredAtMs = now
        val app = dashboardApp
        if (app == null && !controller.hasActiveOrPendingWork()) return
        if (app != null) {
            appContext = app
            controller.updateDemand(snapshotDemand(app))
        } else {
            controller.onProjectionStreamingChanged(streaming, now)
        }
    }

    fun startTest(context: Context): Boolean {
        val app = context.applicationContext
        appContext = app
        return controller.startManualTest(snapshotDemand(app))
    }

    fun cancelTest(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!controller.hasActiveOrPendingWork()) return
        controller.cancelManualTest(snapshotDemand(app))
    }

    private fun snapshotDemand(app: Context): ObdDemand {
        val streaming = ConnectionState.phase == ProjectionPhase.STREAMING
        return ObdDemand(
            selectedAddress = selectedAddress(app),
            hasPermission = hasPermission(app),
            appVisible = appVisible,
            dashboardActive = dashboardApp != null,
            projectionStreaming = streaming,
            streamingEnteredAtMs = if (streaming) lastStreamingEnteredAtMs else 0L,
        )
    }
}
