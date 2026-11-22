package dev.zanderp.opencfmoto

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDashboardSettingsTest {
    @Test fun liveStyleAndEmptyFieldSelectionSurviveReopen() {
        val prefs = memoryPreferences()
        val saved = LiveDashboardSettings(LiveDashboardDesign.TOURING, true, 200, 12_000, emptySet())
        prefs.writeLiveDashboardSettings(saved)
        assertEquals(saved, prefs.readLiveDashboardSettings())
        val changed = saved.copy(design = LiveDashboardDesign.RACING, visibleFields = setOf("speed", "rpm"))
        prefs.writeLiveDashboardSettings(changed)
        assertEquals(changed, prefs.readLiveDashboardSettings())
    }

    @Test fun unknownPreferencesFallBackAndFieldsAreAllowlisted() {
        val prefs = memoryPreferences(
            "live_design" to "unknown", "live_speed_max" to 999,
            "live_rpm_max" to -1, "live_fields" to setOf("speed", "unexpected"),
        )
        assertEquals(LiveDashboardSettings(visibleFields = setOf("speed")), prefs.readLiveDashboardSettings())
        assertEquals(LiveDashboardSettings(), memoryPreferences().readLiveDashboardSettings())
    }

    @Test fun allChosenDesignsPersistWithoutChangingExistingIds() {
        val designs = LiveDashboardDesign.entries
        assertEquals(listOf("sport", "racing", "touring",
            "c01", "c02", "c03", "c04", "c06", "c07", "c08", "c09", "c11", "c19", "horizon"),
            designs.map { it.id })
        val prefs = memoryPreferences()
        for (design in designs) {
            val settings = LiveDashboardSettings(design = design, light = true,
                visibleFields = setOf("speed", "rpm", "volt"))
            prefs.writeLiveDashboardSettings(settings)
            assertEquals(settings, prefs.readLiveDashboardSettings())
            assertEquals(design, LiveDashboardDesign.byId(design.id))
        }
    }

    private fun memoryPreferences(vararg initial: Pair<String, Any?>): SharedPreferences {
        val values = mutableMapOf(*initial)
        return proxy(SharedPreferences::class.java) { method, args ->
            when (method) {
                "getString", "getInt", "getBoolean", "getStringSet" -> values[args[0]] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, Any?>()
                    lateinit var editor: SharedPreferences.Editor
                    editor = proxy(SharedPreferences.Editor::class.java) { editMethod, editArgs ->
                        when (editMethod) {
                            "putString", "putInt", "putBoolean", "putStringSet" ->
                                editor.also { pending[editArgs[0] as String] = editArgs[1] }
                            "apply" -> null.also { values.putAll(pending) }
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
