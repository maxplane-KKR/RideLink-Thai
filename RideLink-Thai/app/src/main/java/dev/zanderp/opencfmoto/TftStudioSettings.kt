package dev.zanderp.opencfmoto

import android.content.SharedPreferences
import kotlin.math.roundToInt

internal data class TftStudioSettings(
    val style: TftStyle = TftStyle.FACTORY,
    val base: TftStyle = TftStyle.BLUE_ARC,
    val custom: Boolean = false,
    val day: Boolean = false,
    val accent: Int = 0,
    val digits: Int = 0,
    val readout: TftReadout = TftReadout.ODO,
    val strength: Int = DEFAULT_PHOTO_STRENGTH,
    val fit: Int = 0,
    val position: Int = 0,
    val photoUri: String? = null,
    val crop: TftPhotoCrop? = null,
) {
    val photoCrop get() = crop ?: TftPhotoCrop.fromPosition(position)

    fun preview(style: TftStyle) = copy(style = style, custom = false)

    fun useCustom(base: TftStyle = this.base) = copy(style = base, base = base, custom = true)

    fun customizeSelected() = if (style.is150xPreset) useCustom(style) else this

    fun resetCustom() = useCustom().copy(
        accent = 0,
        digits = 0,
        strength = DEFAULT_PHOTO_STRENGTH,
        fit = 0,
        position = 0,
        photoUri = null,
        crop = null,
    )

    companion object {
        const val MIN_PHOTO_STRENGTH = 5
        const val MAX_PHOTO_STRENGTH = 100
        const val DEFAULT_PHOTO_STRENGTH = 32
    }
}

internal fun SharedPreferences.readTftStudioSettings(): TftStudioSettings {
    fun readStyle(key: String): TftStyle? {
        val name = getString(key, null)
        return TftStyle.entries.firstOrNull { it.name == name }
    }
    val style = readStyle("style")?.takeUnless { it == TftStyle.MIRROR } ?: TftStyle.FACTORY
    val base = readStyle("base")?.takeUnless { it == TftStyle.FACTORY || it == TftStyle.MIRROR }
        ?: TftStyle.BLUE_ARC
    val oldStrength = getInt("strength", 16)
    val defaultStrength = if (oldStrength == 16) TftStudioSettings.DEFAULT_PHOTO_STRENGTH else oldStrength
    return TftStudioSettings(
        style = style,
        base = base,
        custom = getBoolean("custom", false) && style == base,
        day = getBoolean("day", false),
        accent = getInt("accent", 0),
        digits = getInt("digits", 0),
        readout = TftReadout.entries.getOrElse(getInt("readout", 0)) { TftReadout.ODO },
        strength = getInt("photo_strength", defaultStrength)
            .coerceIn(TftStudioSettings.MIN_PHOTO_STRENGTH, TftStudioSettings.MAX_PHOTO_STRENGTH),
        fit = getInt("fit", 0).coerceIn(0, 1),
        position = getInt("position", 0).coerceIn(0, 4),
        photoUri = getString("photo", null),
        crop = if (getBoolean("has_photo_crop", false)) TftPhotoCrop(
            x = getInt("photo_crop_x", 5000).coerceIn(0, 10000) / 10000f,
            y = getInt("photo_crop_y", 5000).coerceIn(0, 10000) / 10000f,
            zoom = getInt("photo_crop_zoom", 100).coerceIn(100, (TftPhotoCrop.MAX_ZOOM * 100).toInt()) / 100f,
        ) else null,
    )
}

internal fun SharedPreferences.writeTftStudioSettings(settings: TftStudioSettings) {
    edit().putString("style", settings.style.name).putString("base", settings.base.name)
        .putBoolean("custom", settings.custom).putBoolean("day", settings.day)
        .putInt("accent", settings.accent).putInt("digits", settings.digits)
        .putInt("readout", settings.readout.ordinal).putInt("photo_strength", settings.strength)
        .putInt("fit", settings.fit).putInt("position", settings.position)
        .putString("photo", settings.photoUri)
        .putBoolean("has_photo_crop", settings.crop != null)
        .apply {
            settings.crop?.let { crop ->
                putInt("photo_crop_x", (crop.x * 10000).roundToInt())
                putInt("photo_crop_y", (crop.y * 10000).roundToInt())
                putInt("photo_crop_zoom", (crop.zoom * 100).roundToInt())
            }
        }.apply()
}
