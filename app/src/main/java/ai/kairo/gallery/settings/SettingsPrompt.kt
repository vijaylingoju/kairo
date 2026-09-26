package ai.kairo.gallery.settings

import org.json.JSONObject

/** What Gemma decided, already checked against the knowledge base. */
sealed interface LlmDecision {
    /** [value]: extra detail, e.g. the photo to look for when the setting is the wallpaper. */
    data class Change(val settingId: String, val action: String, val value: String? = null) : LlmDecision
    data class Suggest(val message: String, val items: List<Item>) : LlmDecision {
        data class Item(val settingId: String, val action: String, val reason: String)
    }
    data class None(val message: String) : LlmDecision
}

object SettingsPrompt {

    /** Prompt for Gemma. The model may only answer with ids and actions listed here. */
    fun build(kb: SettingsKb, phoneState: String, userQuery: String): String = """
You are the settings assistant on an Android phone. Map the user's message to phone settings.
Only use these settings. Format: id (name): allowed actions
${kb.settings.joinToString("\n") { "${it.id} (${it.name}): ${it.actions.joinToString(", ")}" }}
Phone state now: $phoneState
Reply with exactly one JSON object on a single line, no markdown. Use one of these shapes:
{"type":"change","setting":"<id>","action":"<action>"}
{"type":"change","setting":"wallpaper","action":"<both|home|lock>","photo":"<what the photo shows, in English>"}
{"type":"suggest","message":"<short intro>","items":[{"setting":"<id>","action":"<action>","reason":"<why, under 12 words>"}]}
{"type":"none","message":"<short reply>"}
- "change": the user asks for one specific setting, or for facts about the phone (use action "show").
- "suggest": the user describes a problem or situation. Pick 1 to 3 settings that help. Use the phone state: never suggest something that is already done.
- The phone is slow, lagging, freezing or hot: always "change" with "phone_checkup" and "show". It checks the phone before suggesting anything.
- "none": no setting fits.
Examples:
User: make it louder -> {"type":"change","setting":"media_volume","action":"increase"}
User: I can't see the screen in the sun -> {"type":"change","setting":"brightness","action":"increase"}
User: is my phone up to date -> {"type":"change","setting":"software_update","action":"show"}
User: my phone is so slow -> {"type":"change","setting":"phone_checkup","action":"show"}
User: I think I'm on my phone too much -> {"type":"change","setting":"screen_time","action":"show"}
User: put my dog photo on the lock screen -> {"type":"change","setting":"wallpaper","action":"lock","photo":"dog"}
User: I'm going into a meeting -> {"type":"suggest","message":"For your meeting:","items":[{"setting":"dnd","action":"on","reason":"Blocks calls and notifications"},{"setting":"vibrate_mode","action":"on","reason":"You still feel important calls"}]}
User: my battery drains too fast -> {"type":"suggest","message":"These can save battery:","items":[{"setting":"battery_saver","action":"on","reason":"Limits background activity"},{"setting":"brightness","action":"decrease","reason":"The screen uses the most power"}]}
User: order me a pizza -> {"type":"none","message":"I can only help with phone settings."}
User: $userQuery
"""

    /** Validates the model's JSON against the KB. Returns null if unusable, so the caller can fall back. */
    fun parse(json: JSONObject, kb: SettingsKb): LlmDecision? {
        fun valid(id: String, action: String) = kb[id]?.actions?.contains(action) == true
        return when (json.optString("type")) {
            "change" -> {
                val id = json.optString("setting")
                val action = json.optString("action")
                val photo = json.optString("photo").trim().take(MAX_TEXT).ifEmpty { null }
                if (valid(id, action)) LlmDecision.Change(id, action, photo) else null
            }
            "suggest" -> {
                val array = json.optJSONArray("items") ?: return null
                val items = (0 until array.length())
                    .mapNotNull { array.optJSONObject(it) }
                    .map { LlmDecision.Suggest.Item(it.optString("setting"), it.optString("action"), it.optString("reason")) }
                    .filter { valid(it.settingId, it.action) }
                    .distinctBy { it.settingId }
                    .take(MAX_SUGGESTIONS)
                    .map { it.copy(reason = it.reason.trim().take(MAX_TEXT)) }
                if (items.isEmpty()) null
                else LlmDecision.Suggest(json.optString("message").trim().take(MAX_TEXT).ifEmpty { "These settings can help:" }, items)
            }
            "none" -> LlmDecision.None(
                json.optString("message").trim().take(MAX_TEXT).ifEmpty { "I can only help with phone settings." },
            )
            else -> null
        }
    }

    private const val MAX_SUGGESTIONS = 3
    private const val MAX_TEXT = 120
}
