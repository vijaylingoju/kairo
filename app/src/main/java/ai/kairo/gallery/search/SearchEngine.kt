package ai.kairo.gallery.search

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ai.kairo.gallery.data.IndexDb
import ai.kairo.gallery.data.IndexedImage
import ai.kairo.gallery.embed.Clip
import ai.kairo.gallery.llm.Llm
import ai.kairo.gallery.llm.Prompts
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Filter(
    val categories: Set<String>,
    val keywords: List<String>,
    val wantedField: String?,
    val dateFromMs: Long?,
    val dateToMs: Long?,
    val usedLlm: Boolean,
    val visual: String? = null,   // what the photo should look like, for CLIP
)

data class SearchResult(
    val query: String,
    val filter: Filter,
    val items: List<IndexedImage>,
    val answer: String?,
    val tookMs: Long,
    val topSim: Float? = null,    // best CLIP similarity, null when visual search didn't run
)

/**
 * Query flow: question -> filter (rules + Gemma) -> two signals -> merged ranking (+ answer).
 *  - text:   category / FTS keywords / wanted field over OCR + Gemma's description (exact, good for documents)
 *  - visual: CLIP similarity between the question and every photo (meaning, good for "dog on the beach")
 * The LLM never sees the whole gallery; it only writes the filter.
 */
object SearchEngine {
    private const val TAG = "KairoSearch"
    private val DAY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // CLIP ViT-B/16 cosine: a real match is usually ~0.26-0.35, unrelated photos ~0.12-0.20.
    private const val MIN_SIM = 0.20f
    private const val REL_GAP = 0.04f      // keep photos within this distance of the best match
    private const val VISUAL_WEIGHT = 100f // (sim - MIN_SIM) * weight -> 0.30 ≈ 10 points

    private val DOC_CATEGORIES = setOf(
        "pan_card", "aadhaar_card", "driving_license", "passport", "train_ticket", "flight_ticket",
        "movie_event_ticket", "bill_invoice", "payment_receipt", "chat_screenshot", "document",
    )

    suspend fun search(ctx: Context, query: String): SearchResult = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val rules = RuleParser.parse(query)
        val llm = if (Llm.isReady()) parseWithLlm(ctx, query) else null
        val filter = merge(rules, llm)

        val db = IndexDb.get(ctx)
        val all = db.all()
        val keywordHits: Map<String, Set<Long>> = filter.keywords.associateWith { db.ftsIds(it) }
        val sims = visualScores(ctx, db, query, rules, filter)
        val topSim = sims?.values?.maxOrNull()
        val simCut = topSim?.let { maxOf(MIN_SIM, it - REL_GAP) }

        // Document/field questions trust exact text; CLIP only nudges. Otherwise CLIP leads.
        val docQuery = filter.wantedField != null || filter.categories.any { it in DOC_CATEGORIES }
        val visualWeight = if (docQuery) VISUAL_WEIGHT * 0.3f else VISUAL_WEIGHT
        val hasCriteria = filter.categories.isNotEmpty() || filter.keywords.isNotEmpty()

        val scored = all.mapNotNull { img ->
            if (filter.dateFromMs != null && img.dateTaken < filter.dateFromMs) return@mapNotNull null
            if (filter.dateToMs != null && img.dateTaken > filter.dateToMs) return@mapNotNull null
            var text = 0f
            if (img.category in filter.categories) text += 5
            for ((_, ids) in keywordHits) if (img.mediaId in ids) text += 2
            if (filter.wantedField != null && img.fields[filter.wantedField] != null) text += 1

            val sim = sims?.get(img.mediaId)
            val visualHit = sim != null && simCut != null && sim >= simCut
            val visual = if (visualHit) (sim!! - MIN_SIM) * visualWeight else 0f

            val keep = when {
                sims != null -> text > 0 || visualHit
                hasCriteria -> text > 0
                else -> true
            }
            if (keep) img to text + visual else null
        }
        val ranked = scored
            .sortedWith(compareByDescending<Pair<IndexedImage, Float>> { it.second }.thenByDescending { it.first.dateTaken })
            .map { it.first }

        if (sims != null) {
            val top = sims.entries.sortedByDescending { it.value }.take(5)
                .joinToString { e -> "%.3f".format(e.value) + "(" + all.firstOrNull { it.mediaId == e.key }?.name + ")" }
            Log.i(TAG, "\"$query\" visual cut=${"%.3f".format(simCut)} top: $top")
        }
        Log.i(
            TAG,
            "\"$query\" cats=${filter.categories} kw=${filter.keywords} field=${filter.wantedField} " +
                "visual=\"${filter.visual}\" doc=$docQuery -> ${ranked.take(5).joinToString { it.name }}"
        )

        val answer = filter.wantedField?.let { field ->
            ranked.firstOrNull { it.fields[field] != null }?.let { top -> formatAnswer(field, top) }
        }

        SearchResult(query, filter, ranked, answer, SystemClock.elapsedRealtime() - t0, topSim)
    }

    /**
     * CLIP similarity per photo, or null when there's nothing visual to look for
     * (e.g. "bills this month" only has a date + category handled by text), or CLIP isn't available.
     */
    private suspend fun visualScores(
        ctx: Context, db: IndexDb, query: String, rules: Filter, filter: Filter,
    ): Map<Long, Float>? {
        if (!Clip.isAvailable(ctx)) return null
        val phrase = RuleParser.visualPhrase(query)
        val meaningful = rules.keywords.isNotEmpty() || rules.categories.isNotEmpty() || filter.visual != null
        if (!meaningful || phrase.isBlank()) return null
        val embeddings = db.embeddings()
        if (embeddings.isEmpty()) return null

        // Prompt ensemble: raw phrase + CLIP's "a photo of" template + Gemma's visual rewrite; averaged.
        val prompts = linkedSetOf(phrase, "a photo of $phrase")
        filter.visual?.takeIf { it.isNotBlank() }?.let { prompts += it; prompts += "a photo of $it" }
        return try {
            val vecs = Clip.embedTexts(ctx, prompts.take(Clip.TEXT_SLOTS))
            val q = FloatArray(Clip.DIM)
            for (v in vecs) for (i in q.indices) q[i] += v[i]
            val qn = Clip.normalize(q)
            embeddings.mapValues { (_, v) -> Clip.dot(qn, v) }
        } catch (t: Throwable) {
            Log.w(TAG, "Visual search unavailable", t)
            null
        }
    }

    private suspend fun parseWithLlm(ctx: Context, query: String): Filter? = try {
        val today = DAY_FMT.format(Date())
        val json = Llm.extractJson(Llm.generate(ctx, Content.Text(Prompts.query(today, query))))
        json?.let { j ->
            // "other" matches every unclassified photo, so it's never a useful filter.
            val cats = j.optJSONArray("categories")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it in Prompts.CATEGORIES && it != "other" }.toSet()
            } ?: emptySet()
            val kws = j.optJSONArray("keywords")?.let { a ->
                (0 until a.length()).flatMap { RuleParser.tokens(a.optString(it)) }
            } ?: emptyList()
            val wanted = if (j.isNull("wanted_field")) null
            else j.optString("wanted_field").takeIf { it in Prompts.FIELD_KEYS }
            Filter(
                categories = cats,
                keywords = kws.filter { it !in RuleParser.STOPWORDS && it.length >= 3 },
                wantedField = wanted,
                dateFromMs = parseDay(j, "date_from", endOfDay = false),
                dateToMs = parseDay(j, "date_to", endOfDay = true),
                usedLlm = true,
                visual = if (j.isNull("visual")) null else j.optString("visual").trim().takeIf { it.isNotEmpty() },
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "LLM query parse failed", t)
        null
    }

    private fun parseDay(j: org.json.JSONObject, key: String, endOfDay: Boolean): Long? {
        if (j.isNull(key)) return null
        val s = j.optString(key)
        return try {
            val d = DAY_FMT.parse(s) ?: return null
            if (endOfDay) d.time + 24L * 3600 * 1000 - 1 else d.time
        } catch (_: Exception) {
            null
        }
    }

    /** Rules are precise, the LLM adds understanding: rules win when they matched, LLM fills the gaps. */
    private fun merge(rules: Filter, llm: Filter?): Filter {
        if (llm == null) return rules
        return Filter(
            categories = rules.categories.ifEmpty { llm.categories },
            keywords = (rules.keywords + llm.keywords).distinct(),
            wantedField = rules.wantedField ?: llm.wantedField,
            dateFromMs = rules.dateFromMs ?: llm.dateFromMs,
            dateToMs = rules.dateToMs ?: llm.dateToMs,
            usedLlm = true,
            visual = llm.visual,
        )
    }

    private fun formatAnswer(field: String, img: IndexedImage): String {
        val v = img.fields[field] ?: return ""
        val label = when (field) {
            "id_number" -> when (img.category) {
                "pan_card" -> "PAN number"
                "aadhaar_card" -> "Aadhaar number"
                "driving_license" -> "Licence number"
                "passport" -> "Passport number"
                else -> "ID number"
            }
            "booking_id" -> if (img.category == "train_ticket") "PNR" else "Booking ID"
            "seats" -> "Seats"
            "amount" -> "Amount"
            "venue" -> "Venue"
            "title" -> "Title"
            "name" -> "Name"
            "time" -> "Time"
            "date" -> "Date"
            else -> field
        }
        val extra = if (field == "date") img.fields["time"]?.let { " • $it" } ?: "" else ""
        return "$label: $v$extra"
    }
}

/** Fast, deterministic understanding of common queries (works even before the model loads). */
object RuleParser {
    val STOPWORDS = setOf(
        "my", "me", "show", "find", "get", "give", "the", "a", "an", "of", "in", "on", "for", "and", "or",
        "to", "is", "what", "whats", "where", "when", "which", "photo", "photos", "image", "images",
        "picture", "pictures", "pic", "pics", "please", "all", "from", "with", "that", "this", "number",
        "card", "last", "month", "week", "today", "yesterday", "how", "much", "did", "i", "pay", "paid",
        "can", "you", "open", "latest", "recent", "screenshot", "screenshots", "some", "any", "was", "it",
    )

    private val CATEGORY_WORDS: Map<String, Set<String>> = mapOf(
        "pan" to setOf("pan_card"),
        "aadhaar" to setOf("aadhaar_card"), "aadhar" to setOf("aadhaar_card"), "adhaar" to setOf("aadhaar_card"),
        "license" to setOf("driving_license"), "licence" to setOf("driving_license"), "dl" to setOf("driving_license"),
        "passport" to setOf("passport"),
        "train" to setOf("train_ticket"), "irctc" to setOf("train_ticket"), "pnr" to setOf("train_ticket"),
        "railway" to setOf("train_ticket"),
        "flight" to setOf("flight_ticket"), "boarding" to setOf("flight_ticket"), "airline" to setOf("flight_ticket"),
        "movie" to setOf("movie_event_ticket"), "movies" to setOf("movie_event_ticket"),
        "film" to setOf("movie_event_ticket"), "cinema" to setOf("movie_event_ticket"),
        "bookmyshow" to setOf("movie_event_ticket"), "event" to setOf("movie_event_ticket"),
        "concert" to setOf("movie_event_ticket"), "theatre" to setOf("movie_event_ticket"),
        "theater" to setOf("movie_event_ticket"),
        "ticket" to setOf("train_ticket", "flight_ticket", "movie_event_ticket"),
        "tickets" to setOf("train_ticket", "flight_ticket", "movie_event_ticket"),
        "bill" to setOf("bill_invoice"), "bills" to setOf("bill_invoice"), "invoice" to setOf("bill_invoice"),
        "electricity" to setOf("bill_invoice"),
        "upi" to setOf("payment_receipt"), "payment" to setOf("payment_receipt"),
        "receipt" to setOf("payment_receipt"), "gpay" to setOf("payment_receipt"),
        "phonepe" to setOf("payment_receipt"), "paytm" to setOf("payment_receipt"),
        "transaction" to setOf("payment_receipt"),
        "chat" to setOf("chat_screenshot"), "whatsapp" to setOf("chat_screenshot"),
        "food" to setOf("food"), "meal" to setOf("food"), "dish" to setOf("food"),
        "selfie" to setOf("person"), "person" to setOf("person"), "people" to setOf("person"),
        "document" to setOf("document"), "documents" to setOf("document"),
    )

    private val LEADING_ASK = Regex(
        """^(please\s+)?(show|find|get|search|give|open|display)?\s*(me\s+)?(all\s+)?(of\s+)?(my\s+|the\s+)?""" +
            """((photos?|pictures?|pics?|images?|screenshots?)\s+)?((of|with|from|where|showing)\s+)?"""
    )
    private val TRAILING_NOUN = Regex("""\s+(photos?|pictures?|pics?|images?)$""")
    private val TIME_WORDS = Regex("""\b(today|yesterday|this week|this month|last month|last week)\b""")

    /** "show me photos of my dog at the beach today" -> "my dog at the beach" (what CLIP should look for). */
    fun visualPhrase(query: String): String =
        query.lowercase().trim()
            .replace(TIME_WORDS, " ")
            .replace(Regex("\\s+"), " ").trim()
            .replace(LEADING_ASK, "")
            .replace(TRAILING_NOUN, "")
            .trim(' ', '?', '.', '!')

    fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

    fun parse(query: String): Filter {
        val q = query.lowercase()
        val toks = tokens(query)

        // "movie tickets" -> only movie; plain "tickets" -> all ticket types.
        val specific = toks.filter { it != "ticket" && it != "tickets" }
            .flatMap { CATEGORY_WORDS[it] ?: emptySet() }.toSet()
        val cats = specific.ifEmpty { toks.flatMap { CATEGORY_WORDS[it] ?: emptySet() }.toSet() }

        val wanted = when {
            Regex("""\b(pan|aadhaa?r|adhaar|licen[cs]e|passport)\b.*\b(number|no|id)\b""").containsMatchIn(q) -> "id_number"
            "pnr" in toks || "booking" in toks -> "booking_id"
            "seat" in toks || "seats" in toks -> "seats"
            "how much" in q || "amount" in toks || "paid" in toks -> "amount"
            "where" in toks || "venue" in toks -> "venue"
            "when" in toks || "date" in toks || "time" in toks -> "date"
            else -> null
        }

        val (from, to) = dateRange(q)

        val keywords = toks.filter { it !in STOPWORDS && it !in CATEGORY_WORDS.keys && it.length >= 3 }

        return Filter(cats, keywords, wanted, from, to, usedLlm = false)
    }

    private fun dateRange(q: String): Pair<Long?, Long?> {
        val cal = Calendar.getInstance()
        fun startOfDay(c: Calendar) = c.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        return when {
            "today" in q -> startOfDay(cal) to now
            "yesterday" in q -> {
                val end = startOfDay(cal) - 1
                cal.add(Calendar.DAY_OF_YEAR, -1)
                startOfDay(cal) to end
            }
            "this week" in q -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                startOfDay(cal) to now
            }
            "this month" in q -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                startOfDay(cal) to now
            }
            "last month" in q -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val end = startOfDay(cal) - 1
                cal.add(Calendar.MONTH, -1)
                startOfDay(cal) to end
            }
            else -> null to null
        }
    }
}
