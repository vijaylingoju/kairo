package ai.kairo.gallery.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Routes real phrases through the real assets/settings_kb.json to catch synonym clashes. */
class SettingsKbRoutingTest {

    private val kb = SettingsKb.parse(JSONObject(File("src/main/assets/settings_kb.json").readText()))

    private fun route(query: String): Pair<String, String>? {
        val setting = kb.match(query) ?: return null
        return setting.id to QueryParser.detectAction(query, setting)
    }

    @Test
    fun kbIsWellFormed() {
        val ids = kb.settings.map { it.id }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        kb.settings.forEach { s ->
            assertTrue("${s.id} has no actions", s.actions.isNotEmpty())
            assertTrue("${s.id} has no guide", s.guide != null)
            if (s.tier == Tier.PANEL) assertTrue("${s.id} has no panel", s.panel != null)
        }
    }

    @Test
    fun routesPhrases() {
        val cases = mapOf(
            "reduce brightness" to ("brightness" to "decrease"),
            "screen is too bright" to ("brightness" to "decrease"),
            "make it brighter" to ("brightness" to "increase"),
            "turn off auto brightness" to ("auto_brightness" to "off"),
            "make the text bigger" to ("font_size" to "increase"),
            "font smaller" to ("font_size" to "decrease"),
            "screen turns off too fast" to ("screen_timeout" to "increase"),
            "shorter screen timeout" to ("screen_timeout" to "decrease"),
            "turn off auto rotate" to ("auto_rotate" to "off"),
            "volume up" to ("media_volume" to "increase"),
            "turn the volume down" to ("media_volume" to "decrease"),
            "mute the volume" to ("media_volume" to "off"),
            "ringtone louder" to ("ring_volume" to "increase"),
            "lower the alarm volume" to ("alarm_volume" to "decrease"),
            "put my phone on silent" to ("silent_mode" to "on"),
            "mute my phone" to ("silent_mode" to "on"),
            "turn off silent mode" to ("silent_mode" to "off"),
            "unmute" to ("silent_mode" to "off"),
            "vibrate only" to ("vibrate_mode" to "on"),
            "do not disturb on" to ("dnd" to "on"),
            "turn off dnd" to ("dnd" to "off"),
            "turn on the flashlight" to ("flashlight" to "on"),
            "torch off" to ("flashlight" to "off"),
            "disable touch sounds" to ("touch_sounds" to "off"),
            "turn on wifi" to ("wifi" to "on"),
            "wi-fi" to ("wifi" to "open"),
            "no internet" to ("mobile_data" to "open"),
            "turn on nfc" to ("nfc" to "on"),
            "connect bluetooth earbuds" to ("bluetooth" to "on"),
            "how do I turn on dark mode?" to ("dark_mode" to "on"),
            "turn on eye protection" to ("eye_protection" to "on"),
            "flight mode on" to ("airplane_mode" to "on"),
            "turn off location" to ("location" to "off"),
            "save battery" to ("battery_saver" to "on"),
            "too many notifications" to ("app_notifications" to "open"),
            "share my internet" to ("hotspot" to "on"),
            // Info tier
            "what android version do I have" to ("device_info" to "show"),
            "about phone" to ("device_info" to "show"),
            "how much ram does my phone have" to ("device_info" to "show"),
            "which processor is in this phone" to ("device_info" to "show"),
            "is my phone up to date" to ("software_update" to "show"),
            "check for updates" to ("software_update" to "show"),
            "when was my last security patch" to ("software_update" to "show"),
            "update my android version" to ("software_update" to "show"),
            "how much storage is left" to ("storage_info" to "show"),
            "my memory is full" to ("storage_info" to "show"),
            "clear cache" to ("clean_up" to "open"),
            "free up space" to ("clean_up" to "open"),
            "battery health" to ("battery_info" to "show"),
            "how much battery is left" to ("battery_info" to "show"),
            "turn on battery saver" to ("battery_saver" to "on"),
            // Wallpaper: the action is which screen gets the photo
            "set my beach photo as wallpaper" to ("wallpaper" to "both"),
            "put the puppy on my lock screen wallpaper" to ("wallpaper" to "lock"),
            "change the home screen background" to ("wallpaper" to "home"),
            "use the ice cream picture as my lock screen" to ("wallpaper" to "lock"),
            // Slow phone
            "my phone is so slow" to ("phone_checkup" to "show"),
            "why is my phone lagging" to ("phone_checkup" to "show"),
            "my phone is getting hot" to ("phone_checkup" to "show"),
            "my phone is burning hot" to ("phone_checkup" to "show"),
            "phone keeps hanging" to ("phone_checkup" to "show"),
            "close background apps" to ("close_apps" to "open"),
            "clear ram" to ("close_apps" to "open"),
            "restart my phone" to ("restart_phone" to "open"),
            // Wellbeing
            "how much screen time today" to ("screen_time" to "show"),
            "which apps do I use the most" to ("screen_time" to "show"),
            "I'm addicted to my phone" to ("screen_time" to "show"),
            "how many times did I unlock my phone" to ("screen_time" to "show"),
            "set an app timer" to ("app_timer" to "open"),
            "turn on bedtime mode" to ("bedtime_mode" to "open"),
            "pause distracting apps" to ("focus_mode" to "open"),
            "uninstall an app" to ("app_info" to "open"),
            "close all apps" to ("close_apps" to "open"),
            "increase screen timeout" to ("screen_timeout" to "increase"),
            // Accessibility
            "invert colors" to ("color_inversion" to "on"),
            "turn off color inversion" to ("color_inversion" to "off"),
            "make my screen black and white" to ("grayscale" to "on"),
            "I'm color blind" to ("color_correction" to "on"),
            "turn on high contrast text" to ("high_contrast_text" to "on"),
            "make the font bold" to ("bold_text" to "on"),
            "remove animations" to ("animations" to "off"),
            "turn animations back on" to ("animations" to "on"),
            "make it even darker" to ("extra_dim" to "on"),
            "make icons bigger" to ("display_size" to "increase"),
            "zoom the screen" to ("magnification" to "on"),
            "turn on talkback" to ("talkback" to "on"),
            "turn on captions" to ("captions" to "on"),
            "connect my hearing aid" to ("hearing_aids" to "open"),
            "turn off haptic feedback" to ("touch_vibration" to "off"),
            "grey screen at night" to ("bedtime_mode" to "open"),
            // ...without stealing these
            "wifi is slow" to ("wifi" to "open"),
            "internet is slow" to ("mobile_data" to "open"),
            "turn on hotspot" to ("hotspot" to "on"),
            "boost the volume" to ("media_volume" to "increase"),
            "make the screen warm" to ("eye_protection" to "on"),
        )
        val failures = cases.mapNotNull { (query, expected) ->
            val actual = route(query)
            if (actual != expected) "\"$query\" → $actual, expected $expected" else null
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test
    fun wallpaperKeepsOnlyThePhotoDescription() {
        val cases = mapOf(
            "set my beach photo as wallpaper" to "beach",
            "change my wallpaper to the golden retriever" to "golden retriever",
            "put the puppy on my lock screen wallpaper" to "puppy",
            "use the ice-cream picture as my lock screen" to "ice cream",
            "change wallpaper" to "",
        )
        cases.forEach { (query, photo) -> assertEquals(query, photo, QueryParser.wallpaperPhoto(query)) }
    }

    @Test
    fun onlyFullyUnderstoodRequestsSkipGemma() {
        fun simple(query: String) = kb.match(query)?.let { QueryParser.isSimpleRequest(query, it) } ?: false
        val simple = listOf(
            "turn off haptic feedback", "reduce brightness", "make the text bigger", "is my phone up to date",
            "how much storage is left", "put my phone on silent", "please turn on the flashlight",
            "how do I turn on dark mode?", "volume up", "wifi", "connect bluetooth earbuds", "invert colors",
            "turn animations back on", "what android version do I have", "make the screen brighter",
            "it's really dark in here, make the screen brighter",
            // The example chips
            "Turn on Wi-Fi", "Do not disturb on", "Turn on the flashlight", "How much screen time today?",
        )
        val notSimple = listOf(
            "don't turn off wifi",                               // negation
            "make the screen brighter only in the evening",      // extra detail
            "set my beach photo as wallpaper",                   // a photo to find
            "turn on bluetooth and lower the brightness",        // two settings
            "stop notifications from instagram",
            "the volume buttons don't work",
            "brightnes up",                                      // typo: no match at all
        )
        simple.forEach { assertTrue("should skip Gemma: $it", simple(it)) }
        notSimple.forEach { assertFalse("should ask Gemma: $it", simple(it)) }
    }

    @Test
    fun lowVisionIsAboutReadingNotSunlight() {
        assertTrue(QueryParser.isLowVision("My grandma can't read the screen"))
        assertTrue(QueryParser.isLowVision("my eyesight is weak"))
        assertFalse(QueryParser.isLowVision("I can't see the screen in the sun"))
        assertFalse(QueryParser.isLowVision("my eyes hurt at night"))
    }

    @Test
    fun eyeStrainIsAProblemNotASetting() {
        assertNull(kb.match("my eyes hurt at night"))
        assertTrue(QueryParser.isEyeStrain("my eyes hurt at night"))
    }
}
