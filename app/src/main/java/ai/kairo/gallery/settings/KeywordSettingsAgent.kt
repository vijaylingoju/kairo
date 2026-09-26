package ai.kairo.gallery.settings

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.media.AudioManager
import android.provider.Settings
import ai.kairo.gallery.settings.SettingIds.ALARM_VOLUME
import ai.kairo.gallery.settings.SettingIds.APP_INFO
import ai.kairo.gallery.settings.SettingIds.APP_TIMER
import ai.kairo.gallery.settings.SettingIds.AUTO_BRIGHTNESS
import ai.kairo.gallery.settings.SettingIds.AUTO_ROTATE
import ai.kairo.gallery.settings.SettingIds.BRIGHTNESS
import ai.kairo.gallery.settings.SettingIds.DARK_MODE
import ai.kairo.gallery.settings.SettingIds.DND
import ai.kairo.gallery.settings.SettingIds.EYE_PROTECTION
import ai.kairo.gallery.settings.SettingIds.FLASHLIGHT
import ai.kairo.gallery.settings.SettingIds.FONT_SIZE
import ai.kairo.gallery.settings.SettingIds.MEDIA_VOLUME
import ai.kairo.gallery.settings.SettingIds.PHONE_CHECKUP
import ai.kairo.gallery.settings.SettingIds.RING_VOLUME
import ai.kairo.gallery.settings.SettingIds.SCREEN_TIMEOUT
import ai.kairo.gallery.settings.SettingIds.SILENT_MODE
import ai.kairo.gallery.settings.SettingIds.TOUCH_SOUNDS
import ai.kairo.gallery.settings.SettingIds.VIBRATE_MODE
import ai.kairo.gallery.settings.SettingIds.WALLPAPER
import ai.kairo.gallery.settings.QueryParser.detectAction
import ai.kairo.gallery.settings.QueryParser.isEyeStrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

/**
 * Rule-based agent driven by assets/settings_kb.json, so the UI works end-to-end before any model is wired in.
 * The LLM version will replace [handle]; [apply] and [undo] stay as the executor.
 */
class KeywordSettingsAgent(context: Context) : SettingsAgent {

    private val app = context.applicationContext
    private val settings = SystemSettingsController(app)
    private val device = DeviceController(app)
    private val phoneInfo = PhoneInfo(app)
    private val wallpapers = Wallpapers(app)
    val kb: SettingsKb by lazy { SettingsKb.load(app) }

    /** The extra detail a setting needs from the query, e.g. which photo for the wallpaper. */
    fun valueFor(settingId: String, query: String): String? =
        if (settingId == WALLPAPER) QueryParser.wallpaperPhoto(query).ifEmpty { null } else null

    /** One-line snapshot for the LLM prompt, so suggestions fit the current situation. */
    fun phoneState(): String {
        fun pct(stream: Int) = device.volume(stream) * 100 / device.maxVolume(stream)
        val ringer = when (device.ringerMode()) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        return listOf(
            "time ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}",
            "brightness ${settings.brightnessPercent()?.let { "$it%" } ?: "unknown"}" +
                if (settings.isAutoBrightnessOn()) " (auto)" else "",
            "dark mode ${onOff(settings.isDarkModeOn())}",
            "media volume ${pct(AudioManager.STREAM_MUSIC)}%",
            "ringer $ringer",
            "do not disturb ${onOff(device.isDndOn())}",
            "flashlight ${onOff(device.isTorchOn)}",
            "auto-rotate ${onOff(settings.isAutoRotateOn())}",
        ).joinToString(", ")
    }

    override suspend fun handle(query: String): SettingsResponse = withContext(Dispatchers.IO) {
        val setting = kb.match(query)
        when {
            // "Eye protection" / "dark mode" are explicit requests, not the eye-strain problem.
            setting != null && setting.id in setOf(EYE_PROTECTION, DARK_MODE) -> apply(setting.id, detectAction(query, setting))
            // "My phone is burning hot" is about the phone, not the user's eyes.
            isEyeStrain(query) && setting?.id != PHONE_CHECKUP -> eyeStrainSuggestions()
            setting != null -> apply(setting.id, detectAction(query, setting), valueFor(setting.id, query))
            else -> SettingsResponse.Info(
                "I know ${kb.settings.size} settings, like brightness, volume, Do Not Disturb, flashlight, " +
                    "Wi-Fi and dark mode. You can also describe a problem, like \"my eyes hurt at night\" or " +
                    "\"my phone is slow\", or ask about your phone, like \"is my phone up to date?\".",
            )
        }
    }

    override suspend fun apply(settingId: String, action: String, value: String?): SettingsResponse =
        withContext(Dispatchers.IO) {
            val setting = kb[settingId] ?: return@withContext SettingsResponse.Info("I don't know that setting yet.")
            if (value != null && settingId in APP_PAGES && isInstalled(value)) return@withContext appPage(setting, value)
            try {
                when (setting.tier) {
                    Tier.DIRECT -> direct(setting, action, value)
                    Tier.PANEL -> setting.panel?.let { panel ->
                        SettingsResponse.OpenPanel(
                            setting.note ?: "Opening ${setting.name} controls…",
                            IntentSpec(panel, fallbacks = setting.guide?.intents.orEmpty()),
                        )
                    } ?: guide(setting)
                    Tier.GUIDE -> guide(setting)
                    Tier.INFO -> phoneInfo.answer(setting) ?: guide(setting)
                }
            } catch (e: SecurityException) {
                if (settingId in RINGER_SETTINGS && !device.hasPolicyAccess()) needsPolicyAccess(settingId, action)
                else guide(setting, CANT_CHANGE)
            } catch (e: IllegalArgumentException) {
                guide(setting, CANT_CHANGE)
            }
        }

    override suspend fun setWallpaper(photoUri: String, screen: String): SettingsResponse = withContext(Dispatchers.IO) {
        try {
            wallpapers.set(photoUri, screen)
        } catch (e: Exception) {
            SettingsResponse.Info("I couldn't set that photo as the wallpaper: ${e.message}")
        }
    }

    override suspend fun undo(token: UndoToken): SettingsResponse = withContext(Dispatchers.IO) {
        val setting = kb[token.settingId] ?: return@withContext SettingsResponse.Info("Nothing to undo.")
        val previous = token.previousValue
        try {
            when (token.settingId) {
                BRIGHTNESS -> {
                    val (value, mode) = previous.split(",").map { it.toInt() }
                    settings.setBrightness(value)
                    settings.setBrightnessMode(mode)
                }
                AUTO_BRIGHTNESS -> settings.setBrightnessMode(brightnessMode(previous.toBoolean()))
                FONT_SIZE -> settings.setFontScale(previous.toFloat())
                SCREEN_TIMEOUT -> settings.setScreenTimeoutMs(previous.toInt())
                AUTO_ROTATE -> settings.setAutoRotate(previous.toBoolean())
                MEDIA_VOLUME, RING_VOLUME, ALARM_VOLUME -> device.setVolume(streamFor(token.settingId), previous.toInt())
                SILENT_MODE, VIBRATE_MODE -> device.setRingerMode(previous.toInt())
                DND -> device.setInterruptionFilter(previous.toInt())
                FLASHLIGHT -> device.setTorch(previous.toBoolean())
                TOUCH_SOUNDS -> setTouchSounds(previous.toBoolean())
                else -> return@withContext SettingsResponse.Info("Nothing to undo.")
            }
            SettingsResponse.Done("${setting.name} restored.", undo = null)
        } catch (e: Exception) {
            SettingsResponse.Info("I couldn't undo that: ${e.message}")
        }
    }

    override fun close() = device.close()

    // ---- Direct changes ----

    private suspend fun direct(setting: KbSetting, action: String, value: String?): SettingsResponse {
        if (setting.id in WRITE_SETTINGS && !settings.canWrite()) return needsWritePermission(setting.id, action)
        if (setting.id in POLICY_REQUIRED && !device.hasPolicyAccess()) return needsPolicyAccess(setting.id, action)

        return when (setting.id) {
            BRIGHTNESS -> changeBrightness(action)
            AUTO_BRIGHTNESS -> toggle(setting, settings.isAutoBrightnessOn(), action) {
                settings.setBrightnessMode(brightnessMode(it))
            }
            FONT_SIZE -> changeFontSize(action)
            SCREEN_TIMEOUT -> changeTimeout(setting, action)
            AUTO_ROTATE -> toggle(setting, settings.isAutoRotateOn(), action, settings::setAutoRotate)
            MEDIA_VOLUME, RING_VOLUME, ALARM_VOLUME -> changeVolume(setting, streamFor(setting.id), action)
            SILENT_MODE -> toggleRinger(setting, AudioManager.RINGER_MODE_SILENT, action)
            VIBRATE_MODE -> toggleRinger(setting, AudioManager.RINGER_MODE_VIBRATE, action)
            DND -> toggleDnd(setting, action)
            FLASHLIGHT -> toggleFlashlight(setting, action)
            TOUCH_SOUNDS -> toggle(setting, settings.isTouchSoundsOn(), action, ::setTouchSounds)
            WALLPAPER -> wallpapers.choose(setting, action, value)
            else -> guide(setting)
        }
    }

    /** On/off settings whose previous state fits in a boolean. */
    private fun toggle(setting: KbSetting, isOn: Boolean, action: String, set: (Boolean) -> Unit): SettingsResponse {
        val want = action != Actions.OFF
        if (isOn == want) return SettingsResponse.Info("${setting.name} is already ${onOff(want)}.")
        set(want)
        return SettingsResponse.Done("${setting.name} turned ${onOff(want)}.", UndoToken(setting.id, isOn.toString()))
    }

    private fun changeBrightness(action: String): SettingsResponse {
        val previous = settings.brightness() ?: return SettingsResponse.Info("I couldn't read the brightness.")
        val previousMode = settings.brightnessMode() ?: Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL

        val step = SystemSettingsController.MAX_BRIGHTNESS / 5
        val target = (if (action == Actions.DECREASE) previous - step else previous + step)
            .coerceIn(SystemSettingsController.MIN_BRIGHTNESS, SystemSettingsController.MAX_BRIGHTNESS)
        if (target == previous) {
            val edge = if (action == Actions.DECREASE) "lowest" else "highest"
            return SettingsResponse.Info("Brightness is already at the $edge level.")
        }
        settings.setBrightness(target)
        return SettingsResponse.Done(
            "Brightness ${percent(previous)}% → ${percent(target)}%",
            UndoToken(BRIGHTNESS, "$previous,$previousMode"),
        )
    }

    private fun changeFontSize(action: String): SettingsResponse {
        val previous = settings.fontScale()
        val current = FONT_SCALES.indices.minBy { abs(FONT_SCALES[it] - previous) }
        val next = if (action == Actions.DECREASE) current - 1 else current + 1
        if (next !in FONT_SCALES.indices) {
            val edge = if (action == Actions.DECREASE) "smallest" else "largest"
            return SettingsResponse.Info("Text is already at the $edge size.")
        }
        settings.setFontScale(FONT_SCALES[next])
        return SettingsResponse.Done(
            "Text size ${(previous * 100).toInt()}% → ${(FONT_SCALES[next] * 100).toInt()}%",
            UndoToken(FONT_SIZE, previous.toString()),
        )
    }

    private suspend fun changeTimeout(setting: KbSetting, action: String): SettingsResponse {
        val previous = settings.screenTimeoutMs() ?: return SettingsResponse.Info("I couldn't read the screen timeout.")
        val current = TIMEOUTS_MS.indices.minBy { abs(TIMEOUTS_MS[it].toLong() - previous) }
        val next = if (action == Actions.DECREASE) current - 1 else current + 1
        if (next !in TIMEOUTS_MS.indices) {
            val edge = if (action == Actions.DECREASE) "shortest" else "longest"
            return SettingsResponse.Info("${setting.name} is already at the $edge option.")
        }
        settings.setScreenTimeoutMs(TIMEOUTS_MS[next])
        // OriginOS accepts the write but resets values above 5 min shortly after, so read it back.
        delay(WRITE_SETTLE_MS)
        val actual = settings.screenTimeoutMs()
        if (actual != TIMEOUTS_MS[next]) {
            return SettingsResponse.Info(
                "This phone doesn't allow a ${duration(TIMEOUTS_MS[next])} screen timeout. " +
                    "It's set to ${actual?.let(::duration) ?: "its previous value"}.",
            )
        }
        return SettingsResponse.Done(
            "${setting.name} ${duration(previous)} → ${duration(TIMEOUTS_MS[next])}",
            UndoToken(setting.id, previous.toString()),
        )
    }

    private fun changeVolume(setting: KbSetting, stream: Int, action: String): SettingsResponse {
        val previous = device.volume(stream)
        val min = device.minVolume(stream)
        val maxVolume = device.maxVolume(stream)
        val step = max(1, (maxVolume - min + 4) / 5)
        val target = when (action) {
            Actions.OFF -> min
            Actions.DECREASE -> previous - step
            else -> previous + step
        }.coerceIn(min, maxVolume)
        if (target == previous) {
            val edge = if (action == Actions.INCREASE) "highest" else "lowest"
            return SettingsResponse.Info("${setting.name} is already at the $edge level.")
        }
        device.setVolume(stream, target)
        fun pct(v: Int) = v * 100 / maxVolume
        val text = if (action == Actions.OFF) "${setting.name} muted."
        else "${setting.name} ${pct(previous)}% → ${pct(target)}%"
        return SettingsResponse.Done(text, UndoToken(setting.id, previous.toString()))
    }

    /** Silent / vibrate. The undo token keeps the exact previous ringer mode, not just on/off. */
    private fun toggleRinger(setting: KbSetting, mode: Int, action: String): SettingsResponse {
        val current = device.ringerMode()
        val want = action != Actions.OFF
        if ((current == mode) == want) return SettingsResponse.Info("${setting.name} is already ${onOff(want)}.")
        device.setRingerMode(if (want) mode else AudioManager.RINGER_MODE_NORMAL)
        return SettingsResponse.Done("${setting.name} turned ${onOff(want)}.", UndoToken(setting.id, current.toString()))
    }

    private fun toggleDnd(setting: KbSetting, action: String): SettingsResponse {
        val previous = device.interruptionFilter()
        val want = action != Actions.OFF
        if (device.isDndOn() == want) return SettingsResponse.Info("${setting.name} is already ${onOff(want)}.")
        device.setInterruptionFilter(
            if (want) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return SettingsResponse.Done("${setting.name} turned ${onOff(want)}.", UndoToken(setting.id, previous.toString()))
    }

    private fun toggleFlashlight(setting: KbSetting, action: String): SettingsResponse {
        if (!device.hasFlash()) return SettingsResponse.Info("I can't find a flashlight on this phone.")
        return try {
            toggle(setting, device.isTorchOn, action, device::setTorch)
        } catch (e: CameraAccessException) {
            SettingsResponse.Info("The camera is in use, so I can't control the flashlight right now.")
        }
    }

    private fun setTouchSounds(on: Boolean) {
        settings.setTouchSounds(on)
        device.applyTouchSounds(on)
    }

    // ---- Problem → suggestions ----

    private fun eyeStrainSuggestions(): SettingsResponse {
        val now = LocalTime.now()
        val isNight = now.hour >= 19 || now.hour < 6
        val brightness = settings.brightnessPercent()

        val items = buildList {
            if (brightness == null || brightness > 40) {
                add(
                    Suggestion(
                        BRIGHTNESS, Actions.DECREASE,
                        title = "Lower brightness",
                        reason = brightness?.let { "Your screen is at $it% brightness." }
                            ?: "A dimmer screen is easier on the eyes.",
                        buttonLabel = "Apply",
                    ),
                )
            }
            add(
                Suggestion(
                    EYE_PROTECTION, Actions.ON,
                    title = "Turn on Eye Protection",
                    reason = "Makes the screen warmer and cuts blue light, which helps most at night.",
                    buttonLabel = "Guide me",
                ),
            )
            if (!settings.isDarkModeOn()) {
                add(
                    Suggestion(
                        DARK_MODE, Actions.ON,
                        title = "Turn on dark mode",
                        reason = "Replaces bright white backgrounds with dark ones.",
                        buttonLabel = "Guide me",
                    ),
                )
            }
        }
        val time = now.format(DateTimeFormatter.ofPattern("h:mm a"))
        val intro = if (isNight) "It's $time. These settings can reduce eye strain at night:"
        else "These settings can reduce eye strain:"
        return SettingsResponse.Suggestions(intro, items)
    }

    // ---- Permission / guide responses ----

    private fun needsWritePermission(settingId: String, action: String) = SettingsResponse.NeedsPermission(
        "I need permission to change system settings. Tap Grant, turn it on, then come back and tap Try again.",
        IntentSpec(Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackageUri = true),
        settingId,
        action,
    )

    private fun needsPolicyAccess(settingId: String, action: String) = SettingsResponse.NeedsPermission(
        "I need Do Not Disturb access to change this. Tap Grant, allow Kairo Gallery, then come back and tap Try again.",
        IntentSpec(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        settingId,
        action,
    )

    private fun isInstalled(pkg: String) = runCatching { app.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    /** One app's own page, e.g. from "Set a timer for Instagram" or "Clear WhatsApp's cache". [pkg] is its package. */
    private fun appPage(setting: KbSetting, pkg: String): SettingsResponse {
        val name = app.appLabel(pkg)
        return if (setting.id == APP_TIMER) {
            SettingsResponse.OpenPanel(
                "Opening the timer for $name…",
                IntentSpec(Settings.ACTION_APP_USAGE_SETTINGS, fallbacks = listOfNotNull(setting.panel), targetPackage = pkg),
            )
        } else {
            SettingsResponse.OpenPanel(
                "Opening $name's app info. Tap Storage, then Clear cache.",
                IntentSpec(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackageUri = true, targetPackage = pkg),
            )
        }
    }

    private fun guide(setting: KbSetting, intro: String? = null): SettingsResponse {
        val g = setting.guide ?: return SettingsResponse.Info("I don't have a guide for ${setting.name} yet.")
        return SettingsResponse.Guide(
            intro ?: setting.note ?: "Here's how to change ${setting.name}:",
            g.steps,
            g.intentSpec(),
        )
    }

    // ---- Helpers ----

    private fun streamFor(settingId: String) = when (settingId) {
        RING_VOLUME -> AudioManager.STREAM_RING
        ALARM_VOLUME -> AudioManager.STREAM_ALARM
        else -> AudioManager.STREAM_MUSIC
    }

    private fun brightnessMode(auto: Boolean) =
        if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL

    private fun percent(raw: Int) = raw * 100 / SystemSettingsController.MAX_BRIGHTNESS

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun duration(ms: Int) = if (ms < 60_000) "${ms / 1000} sec" else "${ms / 60_000} min"

    private companion object {
        const val WRITE_SETTLE_MS = 800L
        const val CANT_CHANGE = "The phone didn't let me change that directly. Here's how to do it:"

        val FONT_SCALES = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.8f, 2.0f)
        val TIMEOUTS_MS = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)

        /** Need the "Modify system settings" grant. */
        val WRITE_SETTINGS = setOf(BRIGHTNESS, AUTO_BRIGHTNESS, FONT_SIZE, SCREEN_TIMEOUT, AUTO_ROTATE, TOUCH_SOUNDS)

        /** Open a specific app's page when given its package. */
        val APP_PAGES = setOf(APP_TIMER, APP_INFO)

        /** Always need Do Not Disturb access. */
        val POLICY_REQUIRED = setOf(SILENT_MODE, DND)

        /** May need Do Not Disturb access depending on the current ringer state. */
        val RINGER_SETTINGS = setOf(SILENT_MODE, VIBRATE_MODE, DND, RING_VOLUME)
    }
}
