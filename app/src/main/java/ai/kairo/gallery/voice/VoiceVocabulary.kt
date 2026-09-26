package ai.kairo.gallery.voice

import ai.kairo.gallery.data.IndexedImage

/**
 * The gallery's own words (movie titles, venues, names, tags), used to make voice search understand them:
 *  1. [phrases] are passed to the recognizer as biasing hints (Android 13+).
 *  2. [correct] fixes sound-alike mistakes afterwards: "hero modi movie ticket" -> "irumudi movie ticket".
 *
 * Pure Kotlin so it can be unit-tested.
 */
class VoiceVocabulary(terms: Collection<String>) {

    /** Distinctive single words from the gallery, e.g. "irumudi", "odyssey", "muralikrishna". */
    private val words: List<String> = terms
        .flatMap { tokens(it) }
        .filter { it.length >= 4 && it !in COMMON && it.any(Char::isLetter) }
        .distinct()

    /** Phrases for recognizer biasing (whole titles/venues first, then single words). */
    val phrases: List<String> = (terms.map { cleanPhrase(it) }.filter { it.length >= 3 } + words)
        .distinct()
        .take(MAX_PHRASES)

    /**
     * Replaces runs of 1-3 transcript words that *sound like* a gallery word. Only uncommon words are touched,
     * and only when their consonant pattern matches closely, so ordinary English stays as spoken.
     */
    fun correct(transcript: String): String {
        if (words.isEmpty()) return transcript
        val parts = transcript.trim().split(Regex("\\s+")).toMutableList()
        var i = 0
        while (i < parts.size) {
            var replaced = false
            for (n in 3 downTo 1) {
                if (i + n > parts.size) continue
                val window = parts.subList(i, i + n)
                val plain = window.map { it.lowercase().filter(Char::isLetterOrDigit) }
                if (plain.any { it.isEmpty() || it in COMMON }) continue      // never rewrite ordinary words
                if (n == 1 && plain[0] in words) break                       // already a gallery word
                val joined = plain.joinToString("")
                val best = bestMatch(joined) ?: continue
                parts.subList(i, i + n).clear()
                parts.add(i, best)
                replaced = true
                break
            }
            i++
            if (replaced) continue
        }
        return parts.joinToString(" ")
    }

    private fun bestMatch(heard: String): String? {
        val key = soundKey(heard)
        if (key.length < 3) return null  // too short to judge safely
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for (w in words) {
            val wk = soundKey(w)
            if (wk.length < 3) continue
            val d = editDistance(key, wk)
            val allowed = if (wk.length >= 6) 1 else 0
            if (d <= allowed && d < bestScore) {
                // Also require the letters overall to be reasonably close (stops "rmd" matching anything with r-m-d).
                val letters = editDistance(heard, w).toFloat() / maxOf(heard.length, w.length)
                if (letters <= 0.6f) {
                    best = w
                    bestScore = d
                }
            }
        }
        return best
    }

    companion object {
        private const val MAX_PHRASES = 100

        fun fromGallery(photos: List<IndexedImage>): VoiceVocabulary {
            val terms = buildList {
                for (p in photos) {
                    listOf("title", "venue", "name").forEach { k -> p.fields[k]?.let(::add) }
                    addAll(p.tags)
                }
            }
            return VoiceVocabulary(terms)
        }

        private fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

        /** "Irumudi (UA16+)" -> "Irumudi", "Sarathi Cinemas(Prasaditya..." -> "Sarathi Cinemas". */
        private fun cleanPhrase(s: String) = s.substringBefore('(').replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        /**
         * How a word sounds, roughly: consonants only, similar sounds merged, repeats collapsed.
         * "heromodi" -> "rmd", "irumudi" -> "rmd", "odyssey" -> "ds", "muralikrishna" -> "mrlkrsn".
         */
        fun soundKey(word: String): String {
            val w = word.lowercase()
                .replace("ph", "f").replace("ck", "k").replace("sh", "s").replace("ch", "c")
                .replace("th", "t").replace("dh", "d").replace("bh", "b").replace("kh", "k").replace("gh", "g")
            val out = StringBuilder()
            for (ch in w) {
                val m = when (ch) {
                    'a', 'e', 'i', 'o', 'u', 'y', 'h', 'w' -> continue
                    'c', 'q', 'k' -> 'k'
                    'z', 's', 'x' -> 's'
                    'v' -> 'b'
                    'j' -> 'g'
                    else -> if (ch.isLetter()) ch else continue
                }
                if (out.isEmpty() || out.last() != m) out.append(m)
            }
            return out.toString()
        }

        fun editDistance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]
                d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }

        /** Everyday words that are never rewritten (and never used as gallery words). */
        val COMMON = setOf(
            "show", "me", "my", "the", "a", "an", "all", "of", "for", "in", "on", "at", "to", "and", "or", "is", "it",
            "what", "whats", "where", "when", "which", "who", "how", "much", "did", "i", "pay", "paid", "find", "get",
            "open", "give", "please", "photo", "photos", "picture", "pictures", "pic", "pics", "image", "images",
            "movie", "movies", "ticket", "tickets", "film", "cinema", "cinemas", "theatre", "theater", "booking",
            "number", "seat", "seats", "flight", "train", "bus", "bill", "bills", "receipt", "payment", "card",
            "pan", "aadhaar", "id", "pnr", "last", "this", "month", "week", "today", "yesterday", "food", "dog",
            "with", "from", "that", "some", "any", "was", "our", "your", "screenshot", "screenshots", "document",
            "documents", "ticket", "event", "travel", "person", "people", "place", "places", "day", "night",
        )
    }
}
