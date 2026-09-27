package ai.kairo.gallery.settings

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings

/** Thin wrapper over the Settings.System APIs we can write with WRITE_SETTINGS. */
class SystemSettingsController(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun canWrite(): Boolean = Settings.System.canWrite(context)

    fun brightness(): Int? =
        runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()

    fun brightnessMode(): Int? =
        runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrNull()

    /** Switches to manual mode first, otherwise auto-brightness overrides the value immediately. */
    fun setBrightness(value: Int) {
        setBrightnessMode(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS,
            value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
        )
    }

    fun setBrightnessMode(mode: Int) {
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
    }

    fun brightnessPercent(): Int? = brightness()?.let { it * 100 / MAX_BRIGHTNESS }

    fun fontScale(): Float = Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, 1f)

    fun setFontScale(scale: Float) {
        Settings.System.putFloat(resolver, Settings.System.FONT_SCALE, scale)
    }

    fun screenTimeoutMs(): Int? =
        runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()

    fun setScreenTimeoutMs(ms: Int) {
        Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
    }

    fun isAutoRotateOn(): Boolean = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    fun setAutoRotate(on: Boolean) {
        Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
    }

    fun isAutoBrightnessOn(): Boolean = brightnessMode() == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    fun isTouchSoundsOn(): Boolean = Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1

    fun setTouchSounds(on: Boolean) {
        Settings.System.putInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, if (on) 1 else 0)
    }

    fun isHapticOn(): Boolean = Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1

    fun setHaptic(on: Boolean) {
        Settings.System.putInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, if (on) 1 else 0)
    }

    fun isDarkModeOn(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    companion object {
        // Verified on the iQOO (OriginOS 6): SCREEN_BRIGHTNESS is 0..255.
        const val MAX_BRIGHTNESS = 255
        const val MIN_BRIGHTNESS = 10
    }
}
