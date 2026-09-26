package ai.kairo.gallery.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import ai.kairo.gallery.settings.SecureSwitches.Table.GLOBAL
import ai.kairo.gallery.settings.SecureSwitches.Table.SECURE
import ai.kairo.gallery.settings.SettingIds.ANIMATIONS
import ai.kairo.gallery.settings.SettingIds.BOLD_TEXT
import ai.kairo.gallery.settings.SettingIds.COLOR_INVERSION
import ai.kairo.gallery.settings.SettingIds.EXTRA_DIM
import ai.kairo.gallery.settings.SettingIds.GRAYSCALE
import ai.kairo.gallery.settings.SettingIds.HIGH_CONTRAST_TEXT

/**
 * Accessibility switches kept in Settings.Secure / Settings.Global. Apps can only write those with
 * WRITE_SECURE_SETTINGS, which users can't grant on the phone (it takes `adb shell pm grant`). With it these switch
 * directly, with Undo; without it the agent shows the guide.
 */
class AccessibilityController(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun canWrite(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isOn(settingId: String): Boolean = SecureSwitches.isOn(settingId, ::read)

    /** The current raw values, to restore on undo. */
    fun snapshot(settingId: String): String =
        SecureSwitches.keys(settingId).joinToString(SEPARATOR) { read(it).orEmpty() }

    fun set(settingId: String, on: Boolean) {
        SecureSwitches.keys(settingId).forEach { key -> (if (on) key.on else key.off)?.let { write(key, it) } }
    }

    fun restore(settingId: String, snapshot: String) {
        SecureSwitches.keys(settingId).zip(snapshot.split(SEPARATOR)).forEach { (key, value) ->
            value.ifEmpty { key.default }?.let { write(key, it) }
        }
    }

    private fun read(key: SecureSwitches.Key): String? = when (key.table) {
        SECURE -> Settings.Secure.getString(resolver, key.name)
        GLOBAL -> Settings.Global.getString(resolver, key.name)
    }

    private fun write(key: SecureSwitches.Key, value: String) {
        when (key.table) {
            SECURE -> Settings.Secure.putString(resolver, key.name, value)
            GLOBAL -> Settings.Global.putString(resolver, key.name, value)
        }
    }

    private companion object {
        const val SEPARATOR = "|"
    }
}

/** Which keys each switch writes, and what counts as "on". Pure, so it's unit-tested on the JVM. */
object SecureSwitches {

    enum class Table { SECURE, GLOBAL }

    /** [off] = null: left alone when switching off (e.g. the color-correction mode). [default]: the value when never set. */
    data class Key(val table: Table, val name: String, val on: String, val off: String?, val default: String?)

    private val SWITCHES: Map<String, List<Key>> = mapOf(
        COLOR_INVERSION to listOf(Key(SECURE, "accessibility_display_inversion_enabled", "1", "0", "0")),
        // Grayscale is color correction in mode 0 (monochromacy).
        GRAYSCALE to listOf(
            Key(SECURE, "accessibility_display_daltonizer_enabled", "1", "0", "0"),
            Key(SECURE, "accessibility_display_daltonizer", "0", null, null),
        ),
        HIGH_CONTRAST_TEXT to listOf(Key(SECURE, "high_text_contrast_enabled", "1", "0", "0")),
        // Bold = 700 vs regular 400.
        BOLD_TEXT to listOf(Key(SECURE, "font_weight_adjustment", "300", "0", "0")),
        EXTRA_DIM to listOf(Key(SECURE, "reduce_bright_colors_activated", "1", "0", "0")),
        // "Remove animations" in Accessibility sets all three scales to 0.
        ANIMATIONS to listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
            .map { Key(GLOBAL, it, "1.0", "0", "1.0") },
    )

    val ids: Set<String> get() = SWITCHES.keys

    fun keys(settingId: String): List<Key> = SWITCHES[settingId].orEmpty()

    /** On when no key is at its off value, and every key without an off value is at its on value. */
    fun isOn(settingId: String, read: (Key) -> String?): Boolean {
        val keys = keys(settingId)
        return keys.isNotEmpty() && keys.all { key ->
            val value = read(key) ?: key.default
            if (key.off == null) value == key.on else !sameNumber(value, key.off)
        }
    }

    /** "0" vs "0.0" are the same scale. */
    private fun sameNumber(a: String?, b: String): Boolean {
        val x = a?.toFloatOrNull()
        val y = b.toFloatOrNull()
        return if (x != null && y != null) x == y else a == b
    }
}
