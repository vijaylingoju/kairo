package ai.kairo.gallery.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** INFO = read-only answers about the phone (version, storage, battery...). */
enum class Tier { DIRECT, PANEL, GUIDE, INFO }

data class KbGuide(val intents: List<String>, val steps: List<String>) {
    /** The first intent, with the rest as fallbacks; null when there's nothing to open. */
    fun intentSpec(): IntentSpec? = intents.firstOrNull()?.let { IntentSpec(it, fallbacks = intents.drop(1)) }
}

data class KbSetting(
    val id: String,
    val name: String,
    val tier: Tier,
    val synonyms: List<String>,
    /** The first entry is the default when the query has no direction. */
    val actions: List<String>,
    val panel: String?,
    /** Shown above the steps when we can't (or weren't allowed to) change it ourselves. */
    val note: String?,
    val guide: KbGuide?,
)

/** The curated settings list in assets/settings_kb.json. The agent may only act on ids from here. */
class SettingsKb(val settings: List<KbSetting>) {

    private val byId = settings.associateBy { it.id }
    private val normalizedSynonyms = settings.associateWith { s -> s.synonyms.map(::normalize) }

    operator fun get(id: String): KbSetting? = byId[id]

    /** The setting whose longest synonym appears in the query; ties go to the earlier entry. */
    fun match(query: String): KbSetting? {
        val q = normalize(query)
        var best: KbSetting? = null
        var bestLength = 0
        for (setting in settings) {
            val length = normalizedSynonyms.getValue(setting)
                .filter { it in q }
                .maxOfOrNull { it.length } ?: continue
            if (length > bestLength) {
                best = setting
                bestLength = length
            }
        }
        return best
    }

    companion object {
        private const val ASSET = "settings_kb.json"

        fun load(context: Context): SettingsKb {
            val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            return parse(JSONObject(json))
        }

        fun parse(root: JSONObject): SettingsKb {
            val items = root.getJSONArray("settings")
            return SettingsKb(List(items.length()) { i -> parseSetting(items.getJSONObject(i)) })
        }

        private fun parseSetting(o: JSONObject) = KbSetting(
            id = o.getString("id"),
            name = o.getString("name"),
            tier = Tier.valueOf(o.getString("tier").uppercase()),
            synonyms = o.getJSONArray("synonyms").strings(),
            actions = o.getJSONArray("actions").strings(),
            panel = o.optString("panel").ifEmpty { null },
            note = o.optString("note").ifEmpty { null },
            guide = o.optJSONObject("guide")?.let { g ->
                KbGuide(g.getJSONArray("intents").strings(), g.getJSONArray("steps").strings())
            },
        )

        private fun JSONArray.strings() = List(length()) { getString(it) }

        /** Lowercase words separated by single spaces, padded so " word " matches whole words only. */
        fun normalize(text: String): String =
            " " + text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
    }
}
