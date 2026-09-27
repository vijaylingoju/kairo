package ai.kairo.gallery.search

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import ai.kairo.gallery.data.IndexedImage
import ai.kairo.gallery.index.Indexer
import ai.kairo.gallery.llm.Llm
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Collections

/**
 * Precision stage: Gemma looks at each candidate photo and says whether it shows what was asked.
 *
 * The two cheap signals cannot decide inclusion on their own (measured on 579 photos, iQOO 15):
 *  - CLIP ranks "animal" above "dog": letting it add photos turned "a dog" into sheep, a cow, a fox,
 *    a teddy bear, a goose, a rabbit and a cat.
 *  - Words don't bind attributes: "red bicycle" matched "a blue bicycle with a red handlebar wrap".
 *  - CLIP cannot say "none": "snowy mountains" scored higher than any other query tried.
 * A vision model reading the actual pixels answers all three. So text + CLIP now only have to get the
 * right photos into the candidate list (recall), and this stage decides what is shown (precision).
 */
object Verifier {
    private const val TAG = "KairoVerify"
    private const val VERIFY_SIDE = 768    // px; enough to judge objects and colours, cheap to encode
    private const val CACHE_MAX = 2_000

    // (normalised query, media id) -> verdict. A repeated search costs nothing.
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > CACHE_MAX
        }
    )

    /**
     * The candidates Gemma confirmed, in the order given.
     *
     * Gemma runs one photo at a time, and decoding a full-size camera original took about as long as
     * Gemma's own look (~1.1 s of each ~2.3 s check). So every photo is decoded up front in parallel
     * on the CPU while Gemma works through them on the GPU.
     */
    suspend fun confirm(ctx: Context, query: String, candidates: List<IndexedImage>): List<IndexedImage> = coroutineScope {
        val q = query.trim().lowercase()
        val t0 = SystemClock.elapsedRealtime()
        val jpegs = candidates.map { img ->
            if (cache.containsKey(key(q, img))) null
            else async(Dispatchers.Default) { runCatching { Indexer.toJpeg(Indexer.decode(ctx, Uri.parse(img.uri), VERIFY_SIDE)) }.getOrNull() }
        }
        val kept = candidates.filterIndexed { i, img -> verdict(ctx, q, img, jpegs[i]) }
        Log.i(TAG, "\"$q\" verified ${candidates.size} -> kept ${kept.size} in ${SystemClock.elapsedRealtime() - t0} ms")
        kept
    }

    private fun key(q: String, img: IndexedImage) = "$q\u0000${img.mediaId}"

    private suspend fun verdict(ctx: Context, q: String, img: IndexedImage, jpeg: Deferred<ByteArray?>?): Boolean {
        cache[key(q, img)]?.let { return it }
        val bytes = jpeg?.await() ?: return false
        val reply = try {
            Llm.generate(ctx, Content.ImageBytes(bytes), Content.Text(prompt(q)), maxTokens = 24, timeoutMs = 15_000)
        } catch (t: Throwable) {
            Log.w(TAG, "verify failed for ${img.name}", t)
            return false  // not cached: a transient failure should be retried next search
        }
        // The verdict is the LAST yes/no word. Gemma ignores the "subject:/match:" labels and writes
        // "A yellow card for an iQOO Hackathon event. Yes" - requiring "match: yes" rejected every photo.
        val yes = YES_NO.findAll(reply).lastOrNull()?.value.equals("yes", ignoreCase = true)
        cache[key(q, img)] = yes
        Log.d(TAG, "\"$q\" ${img.name} -> ${if (yes) "yes" else "no"} :: ${reply.replace('\n', ' ').take(80)}")
        return yes
    }

    private val YES_NO = Regex("""\b(yes|no)\b""", RegexOption.IGNORE_CASE)

    /**
     * Name the subject first, then judge. A bare "does it show X? yes/no" drew yes for a teddy bear and a
     * cow's head on "a dog": small vision models lean toward agreeing. Committing to what the photo shows
     * before answering stops that, for ~10 extra output tokens.
     */
    private fun prompt(q: String) = """
Look at this photo and answer in exactly two lines:
subject: what the photo mainly shows, in at most 8 words
match: yes or no - does the photo show "$q"?
For match, every part of the request must be visibly true: the object or animal species, its colour, the place. Use the everyday meaning a person searching their photos would: a tiger is not "a cat", a toy or statue is not the real animal. If unsure, answer no.
""".trim()
}
