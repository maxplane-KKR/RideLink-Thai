// SPDX-License-Identifier: AGPL-3.0-or-later
// รูปแบบและตัวเลือกปรับจาก DashboardMode ของ RideScreen AA v2.4.1
package dev.zanderp.opencfmoto

import android.content.SharedPreferences

internal enum class LiveDashboardDesign(val id: String, val label: String) {
    SPORT("sport", "Sport"), RACING("racing", "Racing"), TOURING("touring", "Touring"),
    C01_NEO_SPORT("c01", "Neo Sport"), C02_CIRCUIT("c02", "Circuit"),
    C03_ENDURANCE("c03", "Endurance"), C04_TRACK_FOCUS("c04", "Track Focus"),
    C06_STREET_SPORT("c06", "Street Sport"), C07_APEX("c07", "Apex"),
    C08_CARBON_RACE("c08", "Carbon Race"), C09_MINIMAL_BLACK("c09", "Minimal Black"),
    C11_AMBER_CLASSIC("c11", "Amber Classic"), C19_STEALTH("c19", "Stealth"),
    HORIZON("horizon", "Horizon");

    val isConcept: Boolean get() = ordinal >= C01_NEO_SPORT.ordinal
    val hasRpmScale: Boolean get() = when (this) {
        RACING, C01_NEO_SPORT, C02_CIRCUIT, C03_ENDURANCE, C04_TRACK_FOCUS -> true
        else -> false
    }
    val hasSeparateRpmValue: Boolean get() = when (this) {
        RACING, C01_NEO_SPORT, C03_ENDURANCE, C04_TRACK_FOCUS -> true
        else -> false
    }

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: SPORT
    }
}

internal object LiveDashboardSpec {
    const val RPM_RED = 9_000.0
    const val COOLANT_AMBER = 105.0
    val speedOptions = listOf(120, 140, 160, 200, 240)
    val rpmOptions = listOf(10_000, 12_000, 14_000)
    val fields = listOf("speed", "rpm", "coolant", "volt", "load", "intake", "baro", "throttle")
    val defaultFields = setOf("speed", "rpm", "coolant", "volt")
}

/** ข้อมูลที่ไม่มีแหล่งจริงต้องเป็น null เสมอ */
internal data class LiveDashboardData(
    val speedKmh: Double? = null,
    val speedFromGps: Boolean = false,
    val rpm: Double? = null,
    val coolant: Double? = null,
    val volt: Double? = null,
    val load: Double? = null,
    val intake: Double? = null,
    val manifoldPressureKpa: Double? = null,
    val absoluteLoad: Double? = null,
    val relativeThrottle: Double? = null,
    val obdLive: Boolean = false,
)

internal data class LiveDashboardSettings(
    val design: LiveDashboardDesign = LiveDashboardDesign.SPORT,
    val light: Boolean = false,
    val speedMax: Int = 160,
    val rpmMax: Int = 10_000,
    val visibleFields: Set<String> = LiveDashboardSpec.defaultFields,
)

internal fun SharedPreferences.readLiveDashboardSettings(): LiveDashboardSettings {
    val speed = getInt("live_speed_max", 160).takeIf { it in LiveDashboardSpec.speedOptions } ?: 160
    val rpm = getInt("live_rpm_max", 10_000).takeIf { it in LiveDashboardSpec.rpmOptions } ?: 10_000
    val fields = getStringSet("live_fields", null)?.intersect(LiveDashboardSpec.fields.toSet())
        ?: LiveDashboardSpec.defaultFields
    return LiveDashboardSettings(
        design = LiveDashboardDesign.byId(getString("live_design", null)),
        light = getBoolean("live_light", false),
        speedMax = speed,
        rpmMax = rpm,
        visibleFields = fields,
    )
}

internal fun SharedPreferences.writeLiveDashboardSettings(settings: LiveDashboardSettings) {
    edit().putString("live_design", settings.design.id)
        .putBoolean("live_light", settings.light)
        .putInt("live_speed_max", settings.speedMax)
        .putInt("live_rpm_max", settings.rpmMax)
        .putStringSet("live_fields", settings.visibleFields.intersect(LiveDashboardSpec.fields.toSet()))
        .apply()
}
