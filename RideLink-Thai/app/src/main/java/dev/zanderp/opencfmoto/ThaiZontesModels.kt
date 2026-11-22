// SPDX-License-Identifier: AGPL-3.0-or-later
// ปรับตารางจอจาก RideScreen AA v2.4.1 (mgb1982/ridescreen-aa), 13 พฤศจิกายน 2026
package dev.zanderp.opencfmoto

import android.content.Context
import java.security.MessageDigest

/** รุ่นที่จำหน่ายในไทย; รุ่นที่ยังไม่มีหลักฐานขนาดจอให้ใช้การตรวจอัตโนมัติเดิม */
internal enum class ThaiZontesModel(val label: String) {
    Z150X("Zontes 150X"),
    Z350D("Zontes 350D"),
    Z350E("Zontes 350E"),
    Z368D("Zontes 368D"),
    Z368E("Zontes 368E"),
    Z368G("Zontes 368G"),
    Z368K("Zontes 368K"),
}

internal object ThaiZontesModels {
    data class ScreenHint(val panel: Pair<Int, Int>, val motoPlayBaseline: Boolean)

    /** ขนาดเป็นค่าตั้งต้นจากต้นทาง ขนาดที่รถส่งจริงต้องมีลำดับความสำคัญกว่า */
    fun byQrModelId(modelId: String?): ScreenHint? = when (modelId?.trim()) {
        "21334", "368E", "368G", "368K" -> ScreenHint(1024 to 425, true)
        "21313", "21321" -> ScreenHint(1024 to 464, true)
        else -> null
    }

    fun screenHint(modelId: String?, selected: ThaiZontesModel?): ScreenHint? {
        byQrModelId(modelId)?.let { return it }
        // 48406 เป็นรหัสของรถ 150X ที่ตรวจจริงในโปรเจกต์นี้ อย่านำขนาด 368 มาแทน
        if (modelId?.trim() == "48406") return null
        return when (selected) {
            ThaiZontesModel.Z368E, ThaiZontesModel.Z368G, ThaiZontesModel.Z368K ->
                ScreenHint(1024 to 425, true)
            else -> null
        }
    }

    fun preferMeasured(measured: Pair<Int, Int>?, hint: ScreenHint?): Pair<Int, Int>? =
        measured ?: hint?.panel
}

internal object ThaiZontesModelPrefs {
    private const val PREFS = "ridelink_thai_bike_model"

    private fun key(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "m_" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun selected(context: Context, raw: String? = BikeMemory.lastRaw(context)): ThaiZontesModel? {
        if (raw.isNullOrBlank()) return null
        val name = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(raw), null)
        return ThaiZontesModel.entries.firstOrNull { it.name == name }
    }

    fun set(context: Context, raw: String, model: ThaiZontesModel?) {
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (model == null) editor.remove(key(raw)) else editor.putString(key(raw), model.name)
        editor.apply()
    }
}
