package ai.kairo.gallery.settings

/** Keyword rules for the non-LLM agent. Pure Kotlin so it can be unit-tested against the real KB. */
object QueryParser {

    /** Picks the action the query asks for, falling back to the setting's default if it's not supported. */
    fun detectAction(query: String, setting: KbSetting): String {
        val q = SettingsKb.normalize(query)
        fun any(words: List<String>) = words.any { " $it " in q }
        val detected = when {
            setting.id == SettingIds.WALLPAPER -> wallpaperScreen(q)
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

    /** "set my beach photo as wallpaper" → "beach": what to look for in the gallery. Empty if no photo was described. */
    fun wallpaperPhoto(query: String): String =
        SettingsKb.normalize(query).trim().split(' ').filter { it !in WALLPAPER_FILLER }.joinToString(" ")

    /** Only one screen when the query names just that one; otherwise both. */
    private fun wallpaperScreen(q: String): String {
        val lock = " lock " in q || " lockscreen " in q
        val home = " home " in q || " homescreen " in q
        return when {
            lock && !home -> Actions.LOCK
            home && !lock -> Actions.HOME
            else -> Actions.BOTH
        }
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

    /** Words about the wallpaper itself, not about the photo. */
    private val WALLPAPER_FILLER = setOf(
        "set", "change", "make", "use", "put", "apply", "update", "as", "my", "the", "a", "an", "to", "it", "this",
        "that", "please", "can", "could", "you", "me", "for", "on", "of", "with", "and", "i", "want", "would", "like",
        "in", "from", "into", "one", "new", "phone", "gallery", "wallpaper", "wallpapers", "wall", "paper",
        "background", "backdrop", "lock", "lockscreen", "home", "homescreen", "screen", "screens", "both",
        "photo", "photos", "picture", "pictures", "pic", "pics", "image", "images",
    )
}
