package ai.kairo.gallery.settings

/**
 * IDs the Kotlin code refers to directly. Must match assets/settings_kb.json,
 * which also holds the panel/guide-only settings that need no code.
 */
object SettingIds {
    const val BRIGHTNESS = "brightness"
    const val AUTO_BRIGHTNESS = "auto_brightness"
    const val FONT_SIZE = "font_size"
    const val SCREEN_TIMEOUT = "screen_timeout"
    const val AUTO_ROTATE = "auto_rotate"
    const val MEDIA_VOLUME = "media_volume"
    const val RING_VOLUME = "ring_volume"
    const val ALARM_VOLUME = "alarm_volume"
    const val SILENT_MODE = "silent_mode"
    const val VIBRATE_MODE = "vibrate_mode"
    const val DND = "dnd"
    const val FLASHLIGHT = "flashlight"
    const val TOUCH_SOUNDS = "touch_sounds"
    const val DARK_MODE = "dark_mode"
    const val EYE_PROTECTION = "eye_protection"
}

object Actions {
    const val INCREASE = "increase"
    const val DECREASE = "decrease"
    const val ON = "on"
    const val OFF = "off"
    const val OPEN = "open"
}

/** An intent the UI should launch. Kept as data so the agent never needs an Activity. */
data class IntentSpec(
    val action: String,
    /** Adds `package:<our package>` as data — required by e.g. ACTION_MANAGE_WRITE_SETTINGS. */
    val withPackageUri: Boolean = false,
    /** Tried in order if [action] doesn't resolve on this phone. */
    val fallbacks: List<String> = emptyList(),
)

data class UndoToken(val settingId: String, val previousValue: String)

data class Suggestion(
    val settingId: String,
    val action: String,
    val title: String,
    val reason: String,
    val buttonLabel: String,
)

sealed interface SettingsResponse {
    val text: String

    /** Plain answer / help text. */
    data class Info(override val text: String) : SettingsResponse

    /** A setting was changed directly. */
    data class Done(override val text: String, val undo: UndoToken?) : SettingsResponse

    /** Problem → ranked settings the user can apply with one tap. */
    data class Suggestions(override val text: String, val items: List<Suggestion>) : SettingsResponse

    /** We can't change it ourselves: show steps + deep link to the right screen. */
    data class Guide(
        override val text: String,
        val steps: List<String>,
        val open: IntentSpec?,
    ) : SettingsResponse

    /** System panel / dialog (Wi-Fi, internet, NFC...). The UI auto-launches it. */
    data class OpenPanel(override val text: String, val open: IntentSpec) : SettingsResponse

    /** A one-time permission grant is needed before the action can run. */
    data class NeedsPermission(
        override val text: String,
        val grant: IntentSpec,
        val retrySettingId: String,
        val retryAction: String,
    ) : SettingsResponse
}

/** Single entry point used by the in-app chat, the floating bubble, the QS tile, etc. */
interface SettingsAgent {
    suspend fun handle(query: String): SettingsResponse
    suspend fun apply(settingId: String, action: String): SettingsResponse
    suspend fun undo(token: UndoToken): SettingsResponse
    fun close() {}
}
