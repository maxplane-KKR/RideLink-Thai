// SPDX-License-Identifier: AGPL-3.0-or-later
// ปรับแนวทาง liveBuilder และ GPS fallback จาก RideScreen AA v2.4.1, 13 พฤศจิกายน 2026
package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object RideScreenTelemetry {
    private const val MAX_AGE_MS = 5_000L

    /** ใช้ความเร็ว GPS ที่ยังใหม่เท่านั้น; ไม่มีข้อมูลให้คืน null */
    fun gpsSpeedKmh(metersPerSecond: Float?, ageMs: Long): Double? {
        if (metersPerSecond == null || !metersPerSecond.isFinite() || metersPerSecond < 0 ||
            ageMs !in 0..MAX_AGE_MS) return null
        val kmh = metersPerSecond * 3.6
        return if (kmh < 2.0) 0.0 else kmh
    }

    fun dashboardData(metersPerSecond: Float?, ageMs: Long): LiveDashboardData {
        val kmh = gpsSpeedKmh(metersPerSecond, ageMs)
        return LiveDashboardData(speedKmh = kmh, speedFromGps = kmh != null)
    }
}

/** ภาพฉายสดใช้ GPS โทรศัพท์และข้อมูลจาก ECU เมื่อมีอะแดปเตอร์ OBD */
internal object RideScreenLiveDashboard {
    private val mutableActive = MutableStateFlow(false)
    val activeState = mutableActive.asStateFlow()
    val active: Boolean get() = activeState.value
    @Volatile private var speed: Float? = null
    @Volatile private var speedAt = 0L
    @Volatile private var logAttempt: LiveDashboardLogAttempt? = null
    private var locationManager: LocationManager? = null
    @Volatile private var settings = LiveDashboardSettings()
    private val listener = LocationListener { update(it) }

    private fun update(location: Location) {
        speed = location.speed.takeIf { location.hasSpeed() }
        speedAt = location.elapsedRealtimeNanos / 1_000_000L
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, attemptId: Long): Boolean {
        val attempt = LiveDashboardLogAttempt(attemptId, TftDiagnostics::recordLiveDashboard)
        val pipeline = AaVideoBridge.pipeline ?: run {
            attempt.event(LiveDashboardEvent.PIPELINE_MISSING)
            return false
        }
        updateSettings(context.getSharedPreferences("tft_studio", Context.MODE_PRIVATE).readLiveDashboardSettings())
        val app = context.applicationContext
        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        logAttempt = attempt
        if (!pipeline.setLiveLayer(liveFrameBuilder(app, attempt), attempt::layerEvent)) {
            logAttempt = null
            attempt.event(LiveDashboardEvent.COMPOSITOR_MISSING)
            return false
        }
        attempt.attached(settings, fine)
        mutableActive.value = true
        LiveObd.setDashboardActive(app, true)
        speed = null
        speedAt = 0L
        if (fine) {
            val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager = manager
            runCatching {
                manager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::update)
                manager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }.onFailure { attempt.event(LiveDashboardEvent.GPS_REQUEST_FAILED) }
        }
        return true
    }

    fun stop(context: Context, byUser: Boolean = false) {
        mutableActive.value = false
        LiveObd.setDashboardActive(context, false)
        logAttempt?.stop(byUser)
        logAttempt = null
        AaVideoBridge.pipeline?.setLiveLayer(null)
        runCatching { locationManager?.removeUpdates(listener) }
        locationManager = null
        speed = null
        speedAt = 0L
    }

    fun reattach(context: Context) {
        val attempt = logAttempt ?: return
        if (active) {
            attempt.event(LiveDashboardEvent.REATTACH_REQUESTED)
            if (AaVideoBridge.pipeline?.setLiveLayer(
                    liveFrameBuilder(context.applicationContext, attempt), attempt::layerEvent,
                ) != true) attempt.event(LiveDashboardEvent.REATTACH_FAILED)
        }
    }

    fun updateSettings(value: LiveDashboardSettings) {
        val next = value.copy(visibleFields = value.visibleFields.toSet())
        if (active && settings != next) logAttempt?.settingsChanged(next)
        settings = next
    }

    fun videoFrameSent() {
        if (active) logAttempt?.videoSent()
    }

    private fun liveFrameBuilder(context: Context, attempt: LiveDashboardLogAttempt): (Int, Int) -> Bitmap? {
        var lastData: LiveDashboardData? = null
        var lastSettings: LiveDashboardSettings? = null
        var lastBitmap: Bitmap? = null
        return build@{ width, height ->
            if (!active || width <= 0 || height <= 0) return@build null
            // คำนวณอายุ GPS/OBD ทุกครั้ง เพื่อให้ค่าที่หมดอายุหายจากหน้าปัดตามเดิม
            val now = SystemClock.elapsedRealtime()
            val gps = RideScreenTelemetry.dashboardData(speed, now - speedAt)
            val obd = LiveObd.status.value
            val vehicleData = if (now - obd.receivedAt in 0..5_000L) obd.sample.applyTo(gps, now) else gps
            val data = vehicleData.copy(clockText = DashboardClock.formatNow())
            val config = settings
            val cached = lastBitmap
            if (cached != null && cached.width == width && cached.height == height &&
                lastData == data && lastSettings == config) return@build cached

            val bitmap = SelectedDashboardRenderer.render(
                width, height, data, config,
                context.getString(R.string.ridescreen_live_no_vehicle_data),
            )
            lastData = data
            lastSettings = config
            lastBitmap = bitmap
            attempt.bitmap(width, height, data.speedFromGps)
            bitmap
        }
    }
}
