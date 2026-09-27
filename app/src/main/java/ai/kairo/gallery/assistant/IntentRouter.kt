package ai.kairo.gallery.assistant

import ai.kairo.gallery.search.RuleParser
import ai.kairo.gallery.settings.QueryParser
import ai.kairo.gallery.settings.SettingIds
import ai.kairo.gallery.settings.SettingsKb
import org.json.JSONObject

/**
 * Which feature answers a request. [CHAT]: small talk ("hi", "thanks") that Kairo answers itself.
 * [UNSURE]: the rules can't tell, so Gemma (or the user) decides.
 */
enum class Route { GALLERY, SETTINGS, CHAT, UNSURE }

/**
 * Photos or settings? Rules first (instant); only [Route.UNSURE] needs Gemma ([prompt]).
 * Pure Kotlin so it can be unit-tested against the real settings KB.
 *
 * Settings synonyms also appear in photo requests ("bright sunset photos", "screenshot of the wifi password",
 * "black and white photos"), so a settings match alone isn't enough. When both sides have a signal, a word that
 * changes something ("set", "turn") means settings, otherwise it's a photo search.
 */
object IntentRouter {

    fun route(query: String, kb: SettingsKb): Route {
        if (SmallTalk.kind(query) != null) return Route.CHAT
        val q = SettingsKb.normalize(query)
        val words = q.trim().split(' ').toSet()
        val setting = kb.match(query)

        // Gallery signals, looked for only in the words the setting didn't explain: in "flight mode" the
        // word "flight" belongs to the setting, not to a flight ticket.
        var rest = q
        setting?.synonyms?.map(SettingsKb::normalize)?.sortedByDescending { it.length }?.forEach { rest = rest.replace(it, " ") }
        val restWords = rest.trim().split(' ')
        val gallery = restWords.any { it in PHOTO_WORDS || RuleParser.isCategoryWord(it) }

        // "hot dog" matches the phone checkup ("hot"), but only a request made of the setting's own words, or one
        // about the phone ("my phone is getting hot"), is clearly about settings.
        val phone = words.any { it in PHONE_WORDS }
        val strongSetting = setting != null && (QueryParser.isSimpleRequest(query, setting) || phone)
        val settings = strongSetting || phone || QueryParser.isEyeStrain(query)

        return when {
            gallery && setting == null && !settings -> Route.GALLERY
            gallery -> when {
                // The wallpaper is a setting that uses the gallery: "set my dog photo as wallpaper".
                setting?.id == SettingIds.WALLPAPER ->
                    if (words.any { it in FIND_WORDS } && words.none { it in CHANGE_WORDS }) Route.GALLERY else Route.SETTINGS
                words.any { it in CHANGE_WORDS } -> Route.SETTINGS
                else -> Route.GALLERY
            }
            settings -> Route.SETTINGS
            // Neither, or only a weak settings match: "golden retriever", "hot dog", Telugu, "I'm going into a meeting".
            else -> Route.UNSURE
        }
    }

    /** Gemma prompt for [Route.UNSURE]. The answer is a few tokens, so it adds about a second. */
    fun prompt(userQuery: String): String = """
Decide which helper on this Android phone should answer the user.
gallery: finds the user's photos, screenshots and documents (tickets, ID cards, bills, receipts, pets, food, places, people) and reads values from them (PNR, PAN number, seat, amount paid).
settings: changes phone settings, helps with phone problems (slow, hot, battery, sound, eyes hurt), and answers questions about the phone (storage, battery, updates, screen time).
chat: greetings, thanks, goodbyes and questions about the assistant itself (hi, thank you, who are you).
none: only for requests that are neither, like ordering food or the weather.
A few words naming something that could be in a photo (an animal, object, food, place, person or scene) always mean gallery. A greeting is never gallery.
The user may write in any language. Reply with exactly one JSON object on a single line, no markdown:
{"route":"gallery"} or {"route":"settings"} or {"route":"chat"} or {"route":"none"}
Examples:
User: golden retriever -> {"route":"gallery"}
User: beach sunset -> {"route":"gallery"}
User: సినిమా టికెట్లు -> {"route":"gallery"}
User: hot dog -> {"route":"gallery"}
User: I'm going into a meeting -> {"route":"settings"}
User: I can't hear anything in my earphones -> {"route":"settings"}
User: order me a pizza -> {"route":"none"}
User: నమస్కారం -> {"route":"chat"}
User: tell me about yourself -> {"route":"chat"}
User: $userQuery
"""

    /** Gemma's answer, or null if it's unusable. "none" becomes [Route.UNSURE]: the user picks. */
    fun parse(json: JSONObject): Route? = when (json.optString("route").trim().lowercase()) {
        "gallery" -> Route.GALLERY
        "settings" -> Route.SETTINGS
        "chat" -> Route.CHAT
        "none" -> Route.UNSURE
        else -> null
    }

    private val PHOTO_WORDS = setOf(
        "photo", "photos", "pic", "pics", "picture", "pictures", "image", "images", "screenshot", "screenshots",
        "selfie", "selfies", "gallery", "album", "albums",
    )

    /** Words that make a request about the phone itself. */
    private val PHONE_WORDS = setOf(
        "phone", "mobile", "device", "battery", "charging", "charger", "screen", "display", "setting", "settings",
        "app", "apps", "sound", "sounds", "call", "calls", "keyboard", "signal", "speaker",
    )

    private val CHANGE_WORDS = setOf(
        "set", "change", "make", "put", "use", "apply", "turn", "switch", "increase", "decrease", "reduce", "lower",
        "raise", "enable", "disable", "activate", "deactivate", "mute", "unmute", "boost", "adjust",
    )

    private val FIND_WORDS = setOf("show", "find", "search", "look", "display")
}
