package ai.kairo.gallery.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureSwitchesTest {

    private fun isOn(id: String, vararg values: Pair<String, String?>): Boolean {
        val map = values.toMap()
        return SecureSwitches.isOn(id) { map[it.name] }
    }

    @Test
    fun neverSetMeansOff() {
        assertFalse(isOn(SettingIds.COLOR_INVERSION))
        assertTrue(isOn(SettingIds.COLOR_INVERSION, "accessibility_display_inversion_enabled" to "1"))
    }

    @Test
    fun grayscaleNeedsColorCorrectionInTheGrayscaleMode() {
        val enabled = "accessibility_display_daltonizer_enabled"
        val mode = "accessibility_display_daltonizer"
        assertTrue(isOn(SettingIds.GRAYSCALE, enabled to "1", mode to "0"))
        assertFalse(isOn(SettingIds.GRAYSCALE, enabled to "1", mode to "12")) // red-green correction, not grayscale
        assertFalse(isOn(SettingIds.GRAYSCALE, enabled to "0", mode to "0"))
    }

    @Test
    fun animationsAreOffOnlyAtZero() {
        val keys = listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")
        fun all(v: String?) = keys.map { it to v }.toTypedArray()
        assertTrue(isOn(SettingIds.ANIMATIONS)) // never set = normal speed
        assertTrue(isOn(SettingIds.ANIMATIONS, *all("0.5")))
        assertFalse(isOn(SettingIds.ANIMATIONS, *all("0")))
        assertFalse(isOn(SettingIds.ANIMATIONS, *all("0.0")))
    }

    @Test
    fun switchingOffLeavesTheColorCorrectionModeAlone() {
        val mode = SecureSwitches.keys(SettingIds.GRAYSCALE).single { it.name == "accessibility_display_daltonizer" }
        assertEquals(null, mode.off)
        assertFalse(isOn("not_a_switch"))
    }
}
