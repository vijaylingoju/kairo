package ai.kairo.gallery.assistant

import ai.kairo.gallery.settings.SettingsKb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Photos or settings? Real phrases through the real assets/settings_kb.json, whose synonyms overlap photo words. */
class IntentRouterTest {

    private val kb = SettingsKb.parse(JSONObject(File("src/main/assets/settings_kb.json").readText()))

    private fun check(expected: Route, vararg queries: String) {
        val wrong = queries.filter { IntentRouter.route(it, kb) != expected }
            .map { "\"$it\" → ${IntentRouter.route(it, kb)}" }
        assertEquals("expected $expected", emptyList<String>(), wrong)
    }

    @Test
    fun galleryRequests() = check(
        Route.GALLERY,
        "my PAN number",
        "what's my PNR",
        "movie tickets",
        "seat number for my flight",
        "bills this month",
        "food photos",
        "dog photos",
        "where was this photo taken",
        "movie tickets on my phone",
    )

    @Test
    fun photoRequestsThatContainSettingWords() = check(
        Route.GALLERY,
        "bright sunset photos",           // brightness: "bright"
        "screenshot of the wifi password", // wifi
        "black and white photos",          // grayscale
        "landscape photos",                // auto rotate
        "photos of my headphones",         // bluetooth
        "hot dog photos",                  // phone checkup: "hot"
        "photo of my eyes",                // eye strain
        "bluetooth headphones bill",       // bluetooth, but a bill
        "show my wallpaper photos",        // wallpaper, but only looking
    )

    @Test
    fun settingsRequests() = check(
        Route.SETTINGS,
        "reduce brightness",
        "volume up",
        "turn on wifi",
        "turn on the flashlight",
        "flight mode",                     // "flight" is also a ticket word
        "turn on flight mode",
        "my eyes hurt at night",
        "my phone is slow",
        "my phone is getting hot",
        "is my phone up to date?",
        "how much storage is left?",
        "how much screen time today?",
        "my grandma can't read the screen",
        "my battery drains too fast",
        "I can't hear my calls",
    )

    @Test
    fun settingsRequestsThatMentionPhotos() = check(
        Route.SETTINGS,
        "set a dog photo as my wallpaper",
        "put my beach photo on the lock screen",
        "lock screen photo",
        "turn on flash for photos",
    )

    /** Only Gemma (or the user) can tell. */
    @Test
    fun unsure() = check(
        Route.UNSURE,
        "golden retriever",
        "ice cream",
        "సినిమా టికెట్లు",
        "hot dog",
        "I'm going into a meeting",
        "order me a pizza",
    )

    @Test
    fun parsesGemmaAnswer() {
        assertEquals(Route.GALLERY, IntentRouter.parse(JSONObject("""{"route":"gallery"}""")))
        assertEquals(Route.SETTINGS, IntentRouter.parse(JSONObject("""{"route":" Settings "}""")))
        assertEquals(Route.UNSURE, IntentRouter.parse(JSONObject("""{"route":"none"}""")))
        assertNull(IntentRouter.parse(JSONObject("""{"route":"weather"}""")))
        assertNull(IntentRouter.parse(JSONObject("""{}""")))
    }
}
