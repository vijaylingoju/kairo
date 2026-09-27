package ai.kairo.gallery.search

import ai.kairo.gallery.data.IndexedImage

/**
 * Fast, binding-aware matching against the object phrases Gemma wrote at index time.
 *
 * The query is split into groups. An attribute word (a colour, "snowy", "wooden"...) binds to the word
 * after it, so "red bicycle" is one group {red, bicycle} and "people at the beach" is two groups
 * {people} and {beach}. A photo matches when every group is found:
 *  - a group with an attribute must sit inside ONE object phrase: "red road bicycle" matches, but
 *    "blue bicycle" + "red handlebar wrap" does not - the photo plain word search got wrong;
 *  - a lone word may be anywhere in the photo's object phrases or its description.
 *
 * Tags and OCR text are left out on purpose. Tags are loose ("big cat" on tigers, "animal" on nearly
 * everything) and OCR is noisy (a protest sign read as "E CaT Armos"). Both still feed the slower
 * fallback path in SearchEngine, which only runs when nothing matches here.
 */
object ObjectMatch {

    private val ATTRIBUTES = setOf(
        "red", "orange", "yellow", "green", "blue", "purple", "violet", "pink", "brown", "black", "white",
        "grey", "gray", "silver", "gold", "golden", "beige", "maroon", "teal", "navy", "cream",
        "snowy", "wooden", "vintage", "striped", "spotted", "frozen", "sandy", "rusty", "shiny",
    )

    // Joining words the rule parser's stopword list doesn't drop ("people AT the beach").
    private val FILLER = setOf(
        "at", "near", "by", "under", "over", "during", "around", "inside", "outside", "next", "front",
        "behind", "into", "onto", "up", "down", "about", "has", "have", "having", "there", "their", "his",
        "her", "its", "are", "be", "being", "very", "like", "just", "only", "who", "whose", "wearing", "one",
    )

    // A search for "people" or "person" should find "a man", "two women", "a cyclist".
    private val PEOPLE = setOf(
        "people", "person", "man", "men", "woman", "women", "boy", "girl", "child", "children", "kid",
        "guy", "lady", "cyclist", "crowd", "group", "family", "friend", "couple", "player", "student",
    )

    // Imitations of a thing. "white and black dog plush toy" names a dog, but a search for "a dog" wants a
    // dog. Such phrases only count when the query asks for one ("dog toy", "horse statue"). Posters, prints
    // and logos are NOT on this list: "iqoo hackathon poster" is exactly what "iqoo hackathon" looks for.
    private val IMITATIONS = setOf(
        "toy", "plush", "stuffed", "statue", "figurine", "sculpture", "doll", "cartoon", "costume", "puppet",
    )

    /** Word groups for [query]; empty when nothing is left to match on (a pure date or field question). */
    fun groups(query: String): List<List<String>> {
        val words = RuleParser.tokens(query)
            .filter { it !in RuleParser.STOPWORDS && it !in FILLER && it.length >= 2 && !it.all(Char::isDigit) }
        val out = ArrayList<List<String>>()
        val pending = ArrayList<String>()
        for (w in words) {
            if (w in ATTRIBUTES) pending += w
            else { out += pending + w; pending.clear() }
        }
        if (pending.isNotEmpty()) out += pending.toList()  // "red" on its own
        return out
    }

    fun matches(img: IndexedImage, groups: List<List<String>>): Boolean {
        if (groups.isEmpty()) return false
        val wantsImitation = groups.flatten().any { stem(it) in IMITATION_STEMS }
        fun real(words: List<String>) = wantsImitation || words.none { it in IMITATION_STEMS }
        // Before a photo has object phrases (not re-indexed yet, or Gemma skipped them) its description
        // stands in as a single phrase: looser, but better than leaving the photo unsearchable.
        val phrases = img.objects.ifEmpty { listOf(img.description) }.map { stems(it) }.filter(::real)
        val description = stems(img.description).takeIf(::real).orEmpty()
        val anywhere = (phrases.flatten() + description).toSet()
        return groups.all { g ->
            if (g.size == 1 && g[0] !in ATTRIBUTES) lone(g[0], anywhere, img)
            else phrases.any { p -> g.all { w -> has(p, w) } }
        }
    }

    private fun lone(w: String, anywhere: Set<String>, img: IndexedImage): Boolean {
        if (img.category in RuleParser.categoriesFor(w)) return true
        if (stem(w) in PEOPLE_STEMS) return anywhere.any { it in PEOPLE_STEMS }
        return has(anywhere, w)
    }

    private val PEOPLE_STEMS = PEOPLE.map(::stem).toSet()
    private val IMITATION_STEMS = IMITATIONS.map(::stem).toSet()

    /** Does a phrase's word set contain [w]? Plurals fold together, and a long word may be a prefix. */
    private fun has(words: Collection<String>, w: String): Boolean {
        val s = stem(w)
        // "hippo" finds "hippopotamus"; short words must match whole, or "car" would find "carrot".
        return words.any { it == s || (s.length >= 5 && it.startsWith(s)) }
    }

    private fun stems(text: String): List<String> = RuleParser.tokens(text).map(::stem)

    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("sses")) -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
        else -> w
    }
}
