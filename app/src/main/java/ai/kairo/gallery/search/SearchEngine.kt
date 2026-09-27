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
import kotlin.math.ln
import kotlin.math.sqrt

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
    val scores: Map<Long, Float> = emptyMap(),  // final score per returned photo
    val sims: Map<Long, Float> = emptyMap(),    // raw CLIP similarity per photo (all photos)
    val adjusted: Map<Long, Float> = emptyMap(), // CLIP similarity minus the photo's own baseline
    val answerImage: IndexedImage? = null,     // the photo the answer was read from
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

    // CLIP scores are compared to each photo's OWN baseline (its mean similarity to a bank of generic
    // prompts). Some photos - dark UI screenshots, plain cards - are "hubs" that score ~0.25 against
    // every query; relative to their baseline they only stand out for queries that really match.
    //
    // Calibration (tools/eval, 2026-09-26, 18 photos, CLIP ViT-B/16): a correct photo beat the best wrong
    // photo by only 0.003-0.022 adjusted, and a non-existent "snowy mountains" still scored 0.069 on a
    // movie still. So CLIP alone can't decide what to include; agreement with Gemma's tags/text can.
    // The bar for a CLIP-only match is THIS query's own score spread, not a fixed number: adjusted scores
    // shift scale per query (max 0.036-0.093 over a 6-query sample on 579 photos), so one absolute cut
    // either admits everything or nothing. The old absolute 0.08 admitted nothing in 5 of those 6, which
    // silently disabled every CLIP-only match and capped recall at whatever words Gemma happened to write.
    // With NO text agreement the concept may simply be absent, and CLIP cannot tell: "snowy mountains"
    // scores the highest raw (0.275) and adjusted (0.088) of any query tried, on a library with none.
    // So branch 3 keeps the old conservative absolute bar as well as the relative one.
    private const val ADJ_ABSENT_BAR = 0.08f
    private const val Z_STRICT = 2.5f       // CLIP-only result must be this many SDs above the query mean
    private const val ADJ_FLOOR = 0.015f    // ...and still clearly positive, so a flat score field matches nothing
    private const val VERIFY_MAX = 8        // fallback only: text-matched photos Gemma looks at, ~2 s each
    private const val EVENT_MIN_ANCHORS = 2                  // confirmed matches needed to call it an event
    private const val EVENT_GAP_MS = 6 * 3600_000L           // matches further apart than this are separate events
    private const val EVENT_PAD_MS = 3 * 3600_000L           // photos this close to an event's matches belong to it
    private const val MAX_BROWSE = 60       // a bare category ("person") is a browse, not a search: cap it
    private const val ADJ_GAP = 0.03f      // ...and within this distance of the best match
    private const val VISUAL_WEIGHT = 150f // adjusted 0.06 ≈ 9 points (a category hit is 5)
    private val BASELINE_PROMPTS = listOf(
        "a photo", "a screenshot of a phone app", "a document with text", "a person", "an animal",
        "food", "a place", "an object", "a colorful image", "a dark image",
    )
    @Volatile private var baselineVecs: List<FloatArray>? = null

    private val DOC_CATEGORIES = setOf(
        "pan_card", "aadhaar_card", "driving_license", "passport", "train_ticket", "bus_ticket", "flight_ticket",
        "movie_event_ticket", "bill_invoice", "payment_receipt", "chat_screenshot", "document",
    )
    // Broad visual categories: a hint, not a filter, when the query says more ("fast food", "man with makeup").
    private val BROAD_CATEGORIES = setOf("food", "person", "place")

    /**
     * Three tiers, cheapest first; each answers only if the one before it found nothing.
     *
     * 1. Object phrases (~200 ms). Gemma already looked at every photo while indexing and wrote what is in
     *    it with the attributes attached ("red road bicycle"). Matching the query against those is a
     *    lookup, and binding the colour to its object makes it precise - see ObjectMatch.
     * 2. Gemma's query rewrite (~2.5 s), for requests the rules can't read: other languages, slang.
     * 3. Gemma looking at the few text-matched candidates (~2 s each). Accurate but slow, so it is the
     *    fallback, not the path: asking per photo at search time cost 25-75 s a query.
     *
     * Documents, fields, category browses and date questions skip all this: text and regex are exact there.
     */
    suspend fun search(ctx: Context, query: String, useLlm: Boolean = true): SearchResult {
        if (RuleParser.isShowAll(query)) return everything(ctx, query)
        val fast = searchOnce(ctx, query, useLlm = false)
        if (isContentQuery(fast)) phraseSearch(ctx, fast, query)?.let { return it }

        val base = if (fast.items.isNotEmpty() || !useLlm || !Llm.isReady()) fast
        else searchOnce(ctx, query, useLlm = true)
        if (base !== fast && isContentQuery(base)) {
            phraseSearch(ctx, base, base.filter.visual ?: base.filter.keywords.joinToString(" "))?.let { return it }
        }
        return if (useLlm && Llm.isReady() && isContentQuery(base)) verified(ctx, base) else base
    }

    /**
     * "show all images": every word is a stopword, so the filter came out empty and matched nothing. There is
     * nothing to filter or rank, just the whole gallery, newest first.
     */
    private suspend fun everything(ctx: Context, query: String): SearchResult = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val items = IndexDb.get(ctx).all().sortedByDescending { it.dateTaken }
        val none = Filter(emptySet(), emptyList(), wantedField = null, dateFromMs = null, dateToMs = null, usedLlm = false)
        SearchResult(query, none, items, answer = null, tookMs = SystemClock.elapsedRealtime() - t0)
    }

    /** "red bicycle", "a hippo", "iqoo hackathon" - not a document, a field, a bare category or a date. */
    private fun isContentQuery(r: SearchResult): Boolean {
        val f = r.filter
        if (f.wantedField != null) return false
        if (f.categories.any { it in DOC_CATEGORIES }) return false
        if (f.keywords.isEmpty() && f.categories.isNotEmpty()) return false
        return f.keywords.isNotEmpty() || f.visual != null
    }

    /**
     * Tier 1: photos whose object phrases hold the query, best CLIP match first, plus photos from the same
     * event. Null when nothing matches, so the caller falls through to the slower tiers.
     */
    private suspend fun phraseSearch(ctx: Context, r: SearchResult, text: String): SearchResult? = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val groups = ObjectMatch.groups(text)
        if (groups.isEmpty()) return@withContext null
        val db = IndexDb.get(ctx)
        val all = db.all()
        val inDate = all.filter { img ->
            (r.filter.dateFromMs == null || img.dateTaken >= r.filter.dateFromMs) &&
                (r.filter.dateToMs == null || img.dateTaken <= r.filter.dateToMs)
        }
        val hits = inDate.filter { ObjectMatch.matches(it, groups) }
        if (hits.isEmpty()) return@withContext null
        val ranked = hits.sortedWith(
            compareByDescending<IndexedImage> { r.adjusted[it.mediaId] ?: Float.NEGATIVE_INFINITY }.thenByDescending { it.dateTaken }
        )
        val mates = eventMates(db, all, ranked, r.filter.keywords)
        Log.i(TAG, "\"${r.query}\" phrases $groups -> ${ranked.size} + ${mates.size} from the same event")
        r.copy(items = ranked + mates, tookMs = r.tookMs + (SystemClock.elapsedRealtime() - t0))
    }

    /**
     * Tier 3, the fallback: Gemma looks at the best few text-matched photos and keeps what it confirms,
     * then photos from the same event are added. Reached only when no object phrase matched - text found
     * something (in OCR or tags, say) that the phrases don't say.
     *
     * Only text candidates, and only a handful: each look costs ~2 s. CLIP's top picks used to be added too,
     * but on this library they were mostly wrong (sheep, a cow and a teddy bear for "a dog"), and for a
     * query with no match at all they only made "nothing found" take 30 s.
     *
     * A photo whose own OCR text holds every word of a multi-word query is accepted without asking:
     * printed text is ground truth, and Gemma only judges what is visible.
     */
    private suspend fun verified(ctx: Context, r: SearchResult): SearchResult = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val db = IndexDb.get(ctx)
        val all = db.all()
        val kw = r.filter.keywords
        // Whole words, and only for 2+ words. ML Kit turns noise into short words: a protest photo's OCR
        // read "E CaT Armos", so a lone "cat" in OCR "proved" it was a cat. Two query words appearing
        // together by accident is far less likely; one word goes to Gemma like any other photo.
        val kwRegex = kw.map { Regex("""\b""" + Regex.escape(it) + """\b""", RegexOption.IGNORE_CASE) }
        fun ocrProves(img: IndexedImage) = kwRegex.size >= 2 && kwRegex.all { it.containsMatchIn(img.ocrText) }

        val candidates = r.items.take(VERIFY_MAX)

        val proven = candidates.filter(::ocrProves).map { it.mediaId }.toSet()
        val confirmed = Verifier.confirm(ctx, r.query, candidates.filter { it.mediaId !in proven })
            .map { it.mediaId }.toSet()
        val kept = candidates.filter { it.mediaId in proven || it.mediaId in confirmed }
        val mates = eventMates(db, all, kept, kw)
        Log.i(
            TAG,
            "\"${r.query}\" verify: ${candidates.size} candidates (${proven.size} proven by OCR) -> ${kept.size}" +
                " + ${mates.size} from the same event"
        )
        r.copy(items = kept + mates, tookMs = r.tookMs + (SystemClock.elapsedRealtime() - t0))
    }

    /**
     * Photos from the same event as the confirmed matches.
     *
     * "iqoo hackathon" has five posters and cards showing the logo, and five photos of people coding at
     * desks. Gemma rightly answers "People working at a hackathon event. no" for the second five - the
     * logo is not in them - yet they ARE pictures of that hackathon. The pixels can't say so; the capture
     * time can. So when 2+ confirmed matches were shot close together, photos from that stretch of time
     * that share at least one query word are included as well.
     *
     * Needs both signals: the time window alone would sweep in everything shot that day, and a shared
     * word alone is the OR-matching that returned 53 photos for "red bicycle".
     */
    private fun eventMates(db: IndexDb, all: List<IndexedImage>, kept: List<IndexedImage>, kw: List<String>): List<IndexedImage> {
        if (kw.isEmpty()) return emptyList()
        // Only photos with a real capture time. With no EXIF date, GalleryScanner falls back to the file's
        // modified time - so 560 of 566 copied-in stock photos all looked "taken" on the same afternoon,
        // formed one giant event, and "red bicycle" pulled in a Ferrari, strawberries and a red mask.
        fun realTime(img: IndexedImage) = img.dateTaken > 0 && img.dateTaken != img.dateModified * 1000
        val times = kept.filter(::realTime).map { it.dateTaken }.sorted()
        if (times.size < EVENT_MIN_ANCHORS) return emptyList()

        // Split the confirmed capture times into runs with no gap longer than EVENT_GAP_MS; each run of
        // 2+ is an event. A match that stands alone in time proves nothing about its neighbours.
        val windows = ArrayList<LongRange>()
        var start = times.first()
        var prev = start
        var count = 1
        for (t in times.drop(1)) {
            if (t - prev > EVENT_GAP_MS) {
                if (count >= EVENT_MIN_ANCHORS) windows += (start - EVENT_PAD_MS)..(prev + EVENT_PAD_MS)
                start = t
                count = 0
            }
            prev = t
            count++
        }
        if (count >= EVENT_MIN_ANCHORS) windows += (start - EVENT_PAD_MS)..(prev + EVENT_PAD_MS)
        if (windows.isEmpty()) return emptyList()

        val keptIds = kept.map { it.mediaId }.toSet()
        val sharesWord = kw.flatMap { db.ftsIds(it) }.toSet()
        return all.filter { img ->
            img.mediaId !in keptIds && img.mediaId in sharesWord && realTime(img) && windows.any { img.dateTaken in it }
        }.sortedBy { it.dateTaken }
    }

    private suspend fun searchOnce(ctx: Context, query: String, useLlm: Boolean): SearchResult = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val rules = RuleParser.parse(query)
        // Fast path (~2 s saved): rules already fully understood it ("my PAN number", "movie tickets",
        // "seat for my flight"). Gemma is only needed for free-form, misspelled or non-English requests.
        val rulesConfident = rules.categories.isNotEmpty() && (rules.wantedField != null || rules.keywords.isEmpty())
        val llm = if (useLlm && Llm.isReady() && !rulesConfident) parseWithLlm(ctx, query) else null
        val filter = merge(rules, llm)

        val db = IndexDb.get(ctx)
        val all = db.all()
        var keywordHits: Map<String, Set<Long>> = filter.keywords.associateWith { db.ftsIds(it) }
        // Slang/typos Gemma kept as-is ("doggo") find nothing; its English visual phrase ("a dog") can.
        if (keywordHits.values.all { it.isEmpty() } && filter.visual != null) {
            val fromVisual = RuleParser.tokens(filter.visual)
                .filter { it !in RuleParser.STOPWORDS && it.length >= 3 && it !in filter.keywords }
            keywordHits = keywordHits + fromVisual.associateWith { db.ftsIds(it) }
        }
        val visual = visualScores(ctx, db, query, rules, filter)
        val sims = visual?.raw
        val adj = visual?.adjusted
        // This query own CLIP bar (see Z_STRICT); unreachable when there is no visual signal at all.
        val adjStrict = visual?.strict ?: Float.MAX_VALUE

        // Document/field questions trust exact text; CLIP only reorders. Otherwise CLIP leads.
        val docQuery = filter.wantedField != null || filter.categories.any { it in DOC_CATEGORIES }
        val visualWeight = if (docQuery) VISUAL_WEIGHT * 0.3f else VISUAL_WEIGHT
        val hasDate = filter.dateFromMs != null || filter.dateToMs != null
        val inDate = all.filter { img ->
            (filter.dateFromMs == null || img.dateTaken >= filter.dateFromMs) &&
                (filter.dateToMs == null || img.dateTaken <= filter.dateToMs)
        }

        // Text evidence: category hit (+5) and keyword hits weighted by rarity (IDF), so "booking" on five
        // tickets counts far less than "odyssey" on one. Having the wanted field is only a tie-breaker.
        val softCategory = filter.categories.isNotEmpty() && filter.categories.all { it in BROAD_CATEGORIES } &&
            rules.keywords.isNotEmpty()
        val idf = keywordHits.mapValues { (_, ids) -> if (ids.isEmpty()) 0f else ln((all.size + 1f) / ids.size) }
        fun catHit(img: IndexedImage) = img.category in filter.categories && !softCategory
        fun textScore(img: IndexedImage): Float {
            var t = if (catHit(img)) 5f else 0f
            for ((kw, ids) in keywordHits) if (img.mediaId in ids) t += 2f * idf.getValue(kw)
            return t
        }
        // A multi-word request is a CONJUNCTION: "red bicycle" means both words, not either. keywordHits
        // is one id-set per word, and taking every photo with textScore > 0 unioned them - "red bicycle"
        // returned 53 photos (39 "red" + 14 "bicycle") when only 4 photos actually had both. Keep the
        // photos covering the most query words, and fall back only when nothing covers more.
        val matchedKw = keywordHits.filterValues { it.isNotEmpty() }
        fun coverage(img: IndexedImage) = matchedKw.count { (_, ids) -> img.mediaId in ids }
        val requiredCoverage = when {
            matchedKw.size >= 2 -> inDate.maxOfOrNull { coverage(it) } ?: 1
            matchedKw.size == 1 -> 1   // one real word matched: a bare category hit is not evidence
            else -> 0                  // nothing matched by word: category-only queries still work
        }
        val textHits = inDate.filter { textScore(it) > 0f && coverage(it) >= requiredCoverage }

        val keep: List<IndexedImage> = when {
            // 1) Documents found by text: stay inside the category, then narrow by a rare name if one matched
            //    ("which theatre is Irumudi in" -> the Irumudi ticket, not all four movie tickets).
            docQuery && textHits.isNotEmpty() -> {
                var pool = textHits.filter { catHit(it) }.ifEmpty { textHits }
                val rare = keywordHits.values.filter { it.isNotEmpty() && it.size <= 2 }.minByOrNull { it.size }
                if (rare != null) pool.filter { it.mediaId in rare }.takeIf { it.isNotEmpty() }?.let { pool = it }
                pool
            }
            // 2) Content query where Gemma's tags/description agree.
            //
            //    When every word of a multi-word request was found together on real photos ("red bicycle"
            //    -> 4 photos carrying BOTH words), that IS the answer: adding anything CLIP merely likes
            //    put a red motorcycle in the results. So a full conjunction returns the text matches alone.
            //
            //    Otherwise CLIP may add a photo it likes MORE than the best confirmed match. Tested the
            //    looser per-query 2.5-sigma bar instead: "a dog" went 5 -> 13 and the 8 additions were
            //    sheep, a cow, a fox, a teddy bear, a goose, a rabbit, another cow and a cat. On this
            //    library CLIP ranks "animal" above "dog", so this strict bar stays.
            textHits.isNotEmpty() -> {
                val fullConjunction = matchedKw.size >= 2 && requiredCoverage >= matchedKw.size
                if (fullConjunction) textHits else {
                    val bar = maxOf(adjStrict, textHits.maxOf { adj?.get(it.mediaId) ?: Float.NEGATIVE_INFINITY })
                    textHits + inDate.filter {
                        val a = adj?.get(it.mediaId) ?: Float.NEGATIVE_INFINITY
                        it !in textHits && a > bar
                    }
                }
            }
            // 3) Nothing in the text agrees: only a clearly strong CLIP match counts. Otherwise say "no match"
            //    instead of showing the closest-but-wrong photos (e.g. "sunset at the beach" with no sunsets).
            adj != null -> {
                val top = inDate.maxOfOrNull { adj[it.mediaId] ?: Float.NEGATIVE_INFINITY } ?: Float.NEGATIVE_INFINITY
                val bar = maxOf(adjStrict, ADJ_ABSENT_BAR)
                inDate.filter { (adj[it.mediaId] ?: Float.NEGATIVE_INFINITY).let { a -> a >= bar && a >= top - ADJ_GAP } }
            }
            // 4) Pure date question ("photos from this week"): everything in range. Else nothing understood.
            hasDate && filter.keywords.isEmpty() && filter.categories.isEmpty() -> inDate
            else -> emptyList()
        }

        val scored = keep.map { img ->
            val a = adj?.get(img.mediaId)
            var score = textScore(img) + (if (a != null && a > 0f) a * visualWeight else 0f)
            if (filter.wantedField != null && img.fields[filter.wantedField] != null) score += 1
            img to score
        }
        // A bare category with no keywords ("person") is a browse, not a search: returning all 257 photos
        // buries the good ones. Keep the best by score, which is CLIP-led once the text signal is flat.
        val isBrowse = filter.keywords.isEmpty() && filter.wantedField == null && filter.categories.isNotEmpty()
        val ranked = scored
            .sortedWith(compareByDescending<Pair<IndexedImage, Float>> { it.second }.thenByDescending { it.first.dateTaken })
            .map { it.first }
            .let { if (isBrowse && it.size > MAX_BROWSE) it.take(MAX_BROWSE) else it }

        if (adj != null) {
            val top = adj.entries.sortedByDescending { it.value }.take(5)
                .joinToString { e -> "%.3f/%.3f".format(e.value, sims?.get(e.key) ?: 0f) + "(" + all.firstOrNull { it.mediaId == e.key }?.name + ")" }
            Log.i(TAG, "\"$query\" visual adj/raw strict=%.3f".format(adjStrict) + " top: $top")
        }
        Log.i(
            TAG,
            "\"$query\" cats=${filter.categories} kw=${filter.keywords} field=${filter.wantedField} " +
                "visual=\"${filter.visual}\" doc=$docQuery -> ${ranked.take(5).joinToString { it.name }}"
        )

        // Answer only from the best match: never read "seats" off a train ticket when asked about a flight.
        val answerImage = filter.wantedField?.let { field ->
            val top = scored.maxOfOrNull { it.second } ?: return@let null
            scored.filter { it.second >= top - 1f }
                .sortedByDescending { it.second }
                .firstOrNull { it.first.fields[field] != null }
                ?.first
        }
        val answer = answerImage?.let { formatAnswer(filter.wantedField!!, it) }

        SearchResult(
            query, filter, ranked, answer, SystemClock.elapsedRealtime() - t0, sims?.values?.maxOrNull(),
            scores = scored.associate { it.first.mediaId to it.second },
            sims = sims.orEmpty(),
            adjusted = adj.orEmpty(),
            answerImage = answerImage,
        )
    }

    private class Visual(val raw: Map<Long, Float>, val adjusted: Map<Long, Float>, val strict: Float)

    /** This query own bar for a CLIP-only match: Z_STRICT SDs above its mean, never below ADJ_FLOOR. */
    private fun strictCut(values: Collection<Float>): Float {
        if (values.isEmpty()) return ADJ_FLOOR
        val mean = values.map { it.toDouble() }.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        return maxOf(ADJ_FLOOR, (mean + Z_STRICT * sd).toFloat())
    }

    /**
     * CLIP similarity per photo (raw, and relative to the photo's own baseline), or null when there's
     * nothing visual to look for (e.g. "bills this month"), or CLIP isn't available.
     */
    private suspend fun visualScores(
        ctx: Context, db: IndexDb, query: String, rules: Filter, filter: Filter,
    ): Visual? {
        if (!Clip.isAvailable(ctx)) return null
        // CLIP only reads English: for Telugu/Hindi queries use Gemma's English rewrite only.
        val latin = query.none { it.isLetter() && it.code > 0x24F }
        val phrase = if (latin) RuleParser.visualPhrase(query) else ""
        val gemmaVisual = filter.visual?.takeIf { it.isNotBlank() }
        val meaningful = (latin && (rules.keywords.isNotEmpty() || rules.categories.isNotEmpty())) || gemmaVisual != null ||
            (!latin && filter.keywords.isNotEmpty())
        if (!meaningful) return null
        val embeddings = db.embeddings()
        if (embeddings.isEmpty()) return null

        // Prompt ensemble: raw phrase + CLIP's "a photo of" template + Gemma's visual rewrite; averaged.
        val prompts = linkedSetOf<String>()
        if (phrase.isNotBlank()) { prompts += phrase; prompts += "a photo of $phrase" }
        gemmaVisual?.let { prompts += it; prompts += "a photo of $it" }
        if (prompts.isEmpty()) filter.keywords.joinToString(" ").let { prompts += it; prompts += "a photo of $it" }
        return try {
            val vecs = Clip.embedTexts(ctx, prompts.take(Clip.TEXT_SLOTS))
            val q = FloatArray(Clip.DIM)
            for (v in vecs) for (i in q.indices) q[i] += v[i]
            val qn = Clip.normalize(q)
            val bank = baselineVecs ?: BASELINE_PROMPTS.chunked(Clip.TEXT_SLOTS)
                .flatMap { Clip.embedTexts(ctx, it) }.also { baselineVecs = it }
            val raw = embeddings.mapValues { (_, v) -> Clip.dot(qn, v) }
            val adjusted = embeddings.mapValues { (id, v) ->
                raw.getValue(id) - bank.map { b -> Clip.dot(b, v) }.average().toFloat()
            }
            Visual(raw, adjusted, strictCut(adjusted.values))
        } catch (t: Throwable) {
            Log.w(TAG, "Visual search unavailable", t)
            null
        }
    }

    private suspend fun parseWithLlm(ctx: Context, query: String): Filter? = try {
        val today = DAY_FMT.format(Date())
        val json = Llm.extractJson(Llm.generate(ctx, Content.Text(Prompts.query(today, query)), maxTokens = 160, timeoutMs = 12_000))
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
            // From Gemma only document categories: "food"/"person" for "ice cream" would match every food photo.
            categories = rules.categories.ifEmpty { llm.categories.filter { it in DOC_CATEGORIES }.toSet() },
            // Category words are handled as categories; as keywords "food" would match every food photo.
            keywords = (rules.keywords + llm.keywords).distinct().filter { !RuleParser.isCategoryWord(it) },
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
            "booking_id" -> when (img.category) {
                "train_ticket", "bus_ticket", "flight_ticket" -> "PNR"
                "payment_receipt" -> "Transaction ID"
                "bill_invoice" -> "Bill no."
                else -> "Booking ID"
            }
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
        "bus" to setOf("bus_ticket"), "redbus" to setOf("bus_ticket"), "abhibus" to setOf("bus_ticket"),
        "apsrtc" to setOf("bus_ticket"), "tsrtc" to setOf("bus_ticket"), "intrcity" to setOf("bus_ticket"),
        "flight" to setOf("flight_ticket"), "boarding" to setOf("flight_ticket"), "airline" to setOf("flight_ticket"),
        "movie" to setOf("movie_event_ticket"), "movies" to setOf("movie_event_ticket"),
        "film" to setOf("movie_event_ticket"), "cinema" to setOf("movie_event_ticket"),
        "bookmyshow" to setOf("movie_event_ticket"), "event" to setOf("movie_event_ticket"),
        "concert" to setOf("movie_event_ticket"), "theatre" to setOf("movie_event_ticket"),
        "theater" to setOf("movie_event_ticket"),
        "ticket" to setOf("train_ticket", "bus_ticket", "flight_ticket", "movie_event_ticket"),
        "tickets" to setOf("train_ticket", "bus_ticket", "flight_ticket", "movie_event_ticket"),
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

    private val ALL_NOUNS = setOf(
        "photo", "photos", "image", "images", "picture", "pictures", "pic", "pics", "gallery",
    )
    private val ALL_FILLER = setOf(
        "show", "me", "all", "my", "the", "every", "everything", "entire", "whole", "please", "see", "view", "open",
        "display", "list", "find", "get", "give", "can", "could", "you", "i", "want", "to", "let", "of", "in", "your",
        "kairo", "saved",
    )

    /**
     * "show all images", "all my photos", "open my gallery": the whole gallery. Needs a photo noun and nothing
     * else but filler, so "all images from today" or "all screenshots" still filter as usual.
     */
    fun isShowAll(query: String): Boolean {
        val toks = tokens(query)
        return toks.any { it in ALL_NOUNS } && toks.all { it in ALL_NOUNS || it in ALL_FILLER }
    }

    fun isCategoryWord(word: String): Boolean = word in CATEGORY_WORDS

    /** Categories a query word stands for ("food" -> food, "selfie" -> person), empty if none. */
    fun categoriesFor(word: String): Set<String> = CATEGORY_WORDS[word].orEmpty()

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
