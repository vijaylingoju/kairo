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

    /**
     * True when every word is one of [setting]'s synonyms, an action word or filler ("please turn off haptic
     * feedback"). The keyword answer is then as good as Gemma's, and 2–5 s faster. Anything else (extra detail,
     * another setting, "don't", typos, other languages) is left to Gemma.
     */
    fun isSimpleRequest(query: String, setting: KbSetting): Boolean {
        var q = SettingsKb.normalize(query)
        // Longest first, so "vibrate mode" is removed before "vibrate".
        setting.synonyms.map(SettingsKb::normalize).sortedByDescending { it.length }.forEach { q = q.replace(it, " ") }
        return q.split(' ').filter { it.isNotEmpty() }.all { it in FILLER || it in ACTION_TOKENS }
    }

    fun isEyeStrain(query: String): Boolean {
        val q = SettingsKb.normalize(query)
        return EYE_STRAIN_WORDS.any { " $it " in q }
    }

    /** "My grandma can't read the screen": reading is hard for the user, not one specific setting. */
    fun isLowVision(query: String): Boolean {
        val q = SettingsKb.normalize(query)
        return LOW_VISION_PHRASES.any { it in q }
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

    private val ACTION_TOKENS: Set<String> =
        (TOO_HIGH + TOO_LOW).flatMap { it.split(' ') }.toSet() +
            DECREASE_WORDS + INCREASE_WORDS + OFF_WORDS + ON_WORDS + listOf("mute", "silence")

    /**
     * Words that don't change what a request means. Deliberately no negations ("don", "not", "never", "stop"):
     * "don't turn off wifi" must go to Gemma. "t", "s"... are what's left of "can't", "it's" after normalizing.
     */
    private val FILLER = setOf(
        "i", "me", "my", "mine", "the", "a", "an", "to", "of", "for", "in", "at", "by", "from", "with", "and",
        "is", "are", "am", "be", "was", "it", "this", "that", "please", "pls", "plz", "can", "could", "would",
        "will", "you", "u", "do", "does", "did", "have", "has", "got", "how", "what", "whats", "which", "when",
        "where", "why", "want", "wanna", "need", "like", "just", "now", "right", "turn", "switch", "set", "make",
        "put", "get", "show", "tell", "check", "see", "open", "go", "change", "phone", "mobile", "device", "screen",
        "so", "very", "really", "bit", "little", "lot", "much", "many", "some", "any", "all", "again", "back",
        "there", "here", "today", "left", "connect", "help", "let", "hey", "hi", "hello", "thanks", "thank",
        "kairo", "t", "s", "m", "ll", "re", "ve", "d",
    )

    /** Not "can't see": "I can't see the screen in the sun" is about brightness. */
    private val LOW_VISION_PHRASES = listOf(
        "can't read", "cant read", "cannot read", "hard to read", "difficult to read", "trouble reading",
        "eyesight", "poor vision", "low vision", "weak eyes", "old eyes", "blurry",
        "grandma", "grandpa", "grandmother", "grandfather", "elderly",
    ).map(SettingsKb::normalize)

    /** Words about the wallpaper itself, not about the photo. */
    private val WALLPAPER_FILLER = setOf(
        "set", "change", "make", "use", "put", "apply", "update", "as", "my", "the", "a", "an", "to", "it", "this",
        "that", "please", "can", "could", "you", "me", "for", "on", "of", "with", "and", "i", "want", "would", "like",
        "in", "from", "into", "one", "new", "phone", "gallery", "wallpaper", "wallpapers", "wall", "paper",
        "background", "backdrop", "lock", "lockscreen", "home", "homescreen", "screen", "screens", "both",
        "photo", "photos", "picture", "pictures", "pic", "pics", "image", "images",
    )
}
