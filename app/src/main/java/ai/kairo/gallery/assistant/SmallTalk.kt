package ai.kairo.gallery.assistant

/**
 * Greetings, thanks and "who are you": Kairo answers these itself. They used to reach the photo search
 * (Gemma called "hi" a gallery request), which answered "No photos match “hi”".
 *
 * Only a message made entirely of small talk counts: in "hi, show my PAN card" the greeting is dropped
 * ([withoutGreeting]) and the rest is a photo request. Pure Kotlin so it can be unit-tested; other languages
 * are left to Gemma's "chat" route.
 */
object SmallTalk {

    enum class Kind { GREETING, HOW_ARE_YOU, THANKS, BYE, ABOUT, OK }

    fun kind(query: String): Kind? {
        val all = words(query).map { it.text }
        if (all.isEmpty()) return null
        val words = all.filter { it !in FILLER }
        // Just the name ("Kairo?") is someone getting its attention.
        if (words.isEmpty()) return if ("kairo" in all) Kind.GREETING else null

        // Every word must belong to a phrase.
        val found = HashSet<Kind>()
        var i = 0
        while (i < words.size) {
            val (phrase, kind) = phraseAt(words, i) ?: return null
            found += kind
            i += phrase.size
        }
        // "hi, how are you" asks how Kairo is; "ok thanks" is thanks.
        return PRIORITY.first { it in found }
    }

    /** "hi Kairo, show my PAN card" -> "show my PAN card". Unchanged when nothing follows the greeting. */
    fun withoutGreeting(query: String): String {
        val words = words(query)
        var i = 0
        while (i < words.size && (words[i].text in HELLO || words[i].text in FILLER)) i++
        val greeted = words.take(i).any { it.text in HELLO }
        return if (greeted && i < words.size) query.substring(words[i].start) else query
    }

    /** The answer. Gemma may also route small talk the rules don't know here ("tell me about yourself"). */
    fun reply(query: String): String = when (kind(query)) {
        Kind.GREETING -> "Hi! $INTRO"
        Kind.HOW_ARE_YOU -> "I'm doing well, thanks for asking! $INTRO"
        Kind.THANKS -> "You're welcome!"
        Kind.BYE -> "Bye! I'm here whenever you need me."
        Kind.OK -> "Anything else I can help with?"
        Kind.ABOUT, null ->
            "I'm Kairo, your assistant on this phone. I can find anything in your photos (tickets, IDs, bills, pets, " +
                "food) and read things off them, like a PNR or a PAN number. I can also change settings and help with " +
                "phone problems. Everything stays on this phone."
    }

    private const val INTRO = "I can find things in your photos or change your phone's settings. " +
        "Try “Movie tickets” or “Turn on Wi-Fi”."

    private val PRIORITY = listOf(Kind.ABOUT, Kind.HOW_ARE_YOU, Kind.THANKS, Kind.BYE, Kind.GREETING, Kind.OK)

    /** Words that add nothing to small talk: "thank you so much Kairo" is "thank you". */
    private val FILLER = setOf(
        "kairo", "there", "buddy", "bro", "dear", "friend", "everyone", "again", "so", "very", "much", "a", "lot",
        "please", "pls", "plz", "too", "then",
    )

    /** Greetings safe to drop in front of a request. Not "good morning": "good morning images" is a search. */
    private val HELLO = setOf(
        "hi", "hii", "hai", "hello", "helo", "hlo", "hey", "heya", "hola", "howdy", "namaste", "namaskar",
        "namaskaram", "vanakkam",
    )

    // Longest first, so "good night" is a goodbye and not "good" + "night".
    private val PHRASES: List<Pair<List<String>, Kind>> = mapOf(
        Kind.GREETING to HELLO + listOf(
            "greetings", "good morning", "good afternoon", "good evening", "good day", "morning", "yo", "sup",
            "whats up", "wassup",
        ),
        Kind.HOW_ARE_YOU to listOf(
            "how are you", "how r u", "how are u", "how r you", "how are you doing", "how you doing", "hows it going",
            "how is it going", "how do you do", "are you there", "you there",
        ),
        Kind.THANKS to listOf(
            "thanks", "thank you", "thank u", "thankyou", "thanku", "thx", "thnx", "ty", "tq", "many thanks",
            "dhanyavad", "dhanyavaad", "shukriya",
        ),
        Kind.BYE to listOf(
            "bye", "bye bye", "goodbye", "good bye", "good night", "see you", "see ya", "see you later", "later",
            "take care", "tata",
        ),
        Kind.ABOUT to listOf(
            "who are you", "what are you", "what is", "what can you do", "what do you do", "what all can you do",
            "help", "help me", "how can you help", "how can you help me", "what can you help with",
            "what can you help me with", "what can i ask", "what can i ask you", "what should i ask",
            "how do i use you", "how does this work", "introduce yourself", "your name", "whats your name",
            "what is your name", "who made you", "who built you",
        ),
        Kind.OK to listOf(
            "ok", "okay", "k", "cool", "nice", "great", "awesome", "good", "fine", "alright", "all right",
            "got it", "super", "perfect", "wow", "hmm", "hm",
        ),
    ).flatMap { (kind, phrases) -> phrases.map { it.split(' ') to kind } }
        .sortedByDescending { it.first.size }

    private fun phraseAt(words: List<String>, i: Int): Pair<List<String>, Kind>? =
        PHRASES.firstOrNull { (phrase, _) -> words.drop(i).take(phrase.size) == phrase }

    private class Word(val text: String, val start: Int)

    private val WORD = Regex("""[\p{L}\p{M}\p{N}'’]+""")
    private val STRETCHED = Regex("""(.)\1{2,}""")

    /** Lowercase words with where each starts. "What's" -> "whats", "hiiii" -> "hi", "okkk" -> "ok". */
    private fun words(text: String): List<Word> = WORD.findAll(text)
        .map { m -> Word(m.value.lowercase().replace("'", "").replace("’", "").replace(STRETCHED, "$1"), m.range.first) }
        .filter { it.text.isNotEmpty() }
        .toList()
}
