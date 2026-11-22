package dev.zanderp.opencfmoto

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TftStudioSettingsTest {
    private val saved = TftStudioSettings(
        day = true,
        accent = 0xff64c4ff.toInt(),
        digits = 0xffeeeeee.toInt(),
        readout = TftReadout.TRIP,
        strength = 71,
        fit = 1,
        position = 4,
        photoUri = "content://test/preview",
    ).useCustom(TftStyle.TWIN_DIALS)

    @Test fun browsePreservesSavedSetup() {
        val preview = saved.preview(TftStyle.AMBER_SWEEP)
        assertEquals(TftStyle.AMBER_SWEEP, preview.style)
        assertFalse(preview.custom)
        assertEquals(saved, preview.useCustom())
    }

    @Test fun factoryRoundTripAfterReopen() {
        val prefs = memoryPreferences()
        prefs.writeTftStudioSettings(saved.preview(TftStyle.FACTORY))
        val reopened = prefs.readTftStudioSettings()
        assertEquals(TftStyle.FACTORY, reopened.style)
        assertFalse(reopened.custom)
        assertEquals(saved, reopened.useCustom())
    }

    @Test fun changingBaseIsExplicit() {
        val changed = saved.preview(TftStyle.BLUE_ARC).useCustom(TftStyle.SPLIT_BARS)
        assertEquals(TftStyle.SPLIT_BARS, changed.base)
        assertEquals(TftStyle.SPLIT_BARS, changed.style)
        assertTrue(changed.custom)
        assertEquals(saved.accent, changed.accent)
        assertEquals(saved.photoUri, changed.photoUri)
    }

    @Test fun customizingUsesTheSelectedGalleryCard() {
        val browsing = saved.preview(TftStyle.AMBER_SWEEP)
        assertEquals(saved, browsing.useCustom())
        val editing = browsing.customizeSelected()
        assertEquals(TftStyle.AMBER_SWEEP, editing.style)
        assertEquals(TftStyle.AMBER_SWEEP, editing.base)
        assertTrue(editing.custom)
        assertEquals(saved.photoUri, editing.photoUri)
        assertEquals(saved.accent, editing.accent)

        val factory = editing.preview(TftStyle.FACTORY)
        assertEquals(factory, factory.customizeSelected())
        assertEquals(editing, factory.useCustom())
    }

    @Test fun resetClearsAppearanceOnly() {
        val prefs = memoryPreferences()
        prefs.writeTftStudioSettings(saved)
        prefs.writeTftStudioSettings(prefs.readTftStudioSettings().resetCustom())
        val reset = prefs.readTftStudioSettings()
        assertEquals(TftStudioSettings(day = true, readout = TftReadout.TRIP).useCustom(saved.base), reset)
        assertNull(reset.photoUri)
    }

    @Test fun legacyPreferencesStillLoad() {
        val prefs = memoryPreferences(
            "style" to "MIRROR", "base" to "MIRROR", "custom" to true, "strength" to 16,
            "photo" to saved.photoUri, "digits" to saved.digits,
        )
        val old = prefs.readTftStudioSettings()
        assertEquals(TftStyle.FACTORY, old.style)
        assertEquals(TftStyle.BLUE_ARC, old.base)
        assertFalse(old.custom)
        assertEquals(32, old.strength)
        assertEquals(saved.photoUri, old.photoUri)
        assertEquals(saved.digits, old.digits)
        assertEquals(71, memoryPreferences("strength" to 71).readTftStudioSettings().strength)
    }

    @Test fun photoCropPersistsAndResetClearsIt() {
        val customCrop = TftPhotoCrop(x = .2f, y = .85f, zoom = 1.75f)
        val prefs = memoryPreferences()
        prefs.writeTftStudioSettings(saved.copy(crop = customCrop))
        assertEquals(customCrop, prefs.readTftStudioSettings().photoCrop)

        prefs.writeTftStudioSettings(prefs.readTftStudioSettings().resetCustom())
        assertNull(prefs.readTftStudioSettings().crop)
        assertEquals(TftPhotoCrop(), prefs.readTftStudioSettings().photoCrop)
    }

    @Test fun legacyPhotoAlignmentStillUsesTheSameEdges() {
        val prefs = memoryPreferences("position" to 4, "photo" to saved.photoUri)
        assertEquals(TftPhotoCrop(x = 1f), prefs.readTftStudioSettings().photoCrop)
    }

    @Test fun draggingPhotoKeepsTheDashboardFrameCovered() {
        val dragged = TftPhotoCrop(zoom = 2f).drag(900f, -900f, 1280, 720)
        val frame = dragged.frame(1280, 720, fill = true)
        assertEquals(0f, frame.left, .01f)
        assertEquals(TftPhotoCrop.HEIGHT, frame.top + frame.height, .01f)
        assertTrue(frame.left + frame.width >= TftPhotoCrop.WIDTH)
        assertTrue(frame.top <= 0f)
    }

    @Test fun showingEntirePhotoIgnoresCropZoom() {
        val frame = TftPhotoCrop(zoom = 3f).frame(600, 1200, fill = false)
        assertEquals(TftPhotoCrop.HEIGHT, frame.height, .01f)
        assertEquals(192f, frame.width, .01f)
        assertEquals(224f, frame.left, .01f)
    }

    private fun memoryPreferences(vararg initial: Pair<String, Any?>): SharedPreferences {
        val values = mutableMapOf(*initial)
        return proxy(SharedPreferences::class.java) { method, args ->
            when (method) {
                "getString", "getInt", "getBoolean" -> values[args[0]] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, Any?>()
                    lateinit var editor: SharedPreferences.Editor
                    editor = proxy(SharedPreferences.Editor::class.java) { editMethod, editArgs ->
                        when (editMethod) {
                            "putString", "putInt", "putBoolean" ->
                                editor.also { pending[editArgs[0] as String] = editArgs[1] }
                            "apply" -> null.also {
                                pending.forEach { (key, value) ->
                                    if (value == null) values.remove(key) else values[key] = value
                                }
                            }
                            else -> error(editMethod)
                        }
                    }
                    editor
                }
                else -> error(method)
            }
        }
    }

    private fun <T : Any> proxy(type: Class<T>, invoke: (String, Array<out Any?>) -> Any?): T =
        requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            invoke(method.name, args ?: emptyArray())
        }))
}
