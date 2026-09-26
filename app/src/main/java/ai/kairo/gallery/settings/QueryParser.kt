package ai.kairo.gallery.settings

/** Keyword rules for the non-LLM agent. Pure Kotlin so it can be unit-tested against the real KB. */
object QueryParser {

    /** Picks the action the query asks for, falling back to the setting's default if it's not supported. */
    fun detectAction(query: String, setting: KbSetting): String {
        val q = SettingsKb.normalize(query)
        fun any(words: List<String>) = words.any { " $it " in q }
        val detected = when {
            setting.id == SettingIds.MEDIA_VOLUME && any(listOf("mute", "silence")) -> Actions.OFF
            any(TOO_HIGH) -> Actions.DECREASE
            any(TOO_LOW) -> Actions.INCREASE
            any(DECREASE_WORDS) -> Actions.DECREASE
            any(INCREASE_WORDS) -> Actions.INCREASE
            any(OFF_WORDS) -> Actions.OFF
            any(ON_WORDS) -> Actions.ON
            else -> null
        }
        return detected?.takeIf { it in setting.actions } ?: setting.actions.first()
    }

    fun isEyeStrain(query: String): Boolean {
        val q = SettingsKb.normalize(query)
        return EYE_STRAIN_WORDS.any { " $it " in q }
    }

    private val TOO_HIGH = listOf("too bright", "too big", "too large", "too loud", "too long")
    private val TOO_LOW = listOf(
        "too dark", "too dim", "too small", "too quiet", "too soft", "too short", "too fast", "too quickly",
    )
    private val DECREASE_WORDS = listOf(
        "decrease", "smaller", "lower", "less", "down", "dim", "dimmer", "reduce", "darker",
        "quieter", "softer", "shorter", "minimum", "min",
    )
    private val INCREASE_WORDS = listOf(
        "increase", "bigger", "larger", "higher", "more", "up", "raise", "brighter",
        "louder", "longer", "maximum", "max", "boost",
    )
    private val OFF_WORDS = listOf("off", "disable", "deactivate", "unmute")
    private val ON_WORDS = listOf("on", "enable", "activate", "start", "use")
    private val EYE_STRAIN_WORDS = listOf("eye", "eyes", "burn", "burning", "strain", "headache", "squint", "sore")
}
