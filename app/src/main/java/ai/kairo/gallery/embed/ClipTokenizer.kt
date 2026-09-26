package ai.kairo.gallery.embed

import android.content.Context
import java.io.InputStream
import java.util.regex.Pattern

/**
 * Port of OpenAI CLIP's simple_tokenizer.py (byte-level BPE, 49408 tokens, context 77).
 * Vocab file: assets/clip_bpe_vocab.txt (= bpe_simple_vocab_16e6.txt.gz from openai/CLIP, un-gzipped).
 */
class ClipTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
) {
    private val byteEncoder: Array<String> = bytesToUnicode()
    private val cache = HashMap<String, String>()

    /** [SOT] + tokens + [EOT], zero-padded (or truncated) to [CONTEXT_LENGTH]. */
    fun tokenize(text: String): IntArray {
        val ids = ArrayList<Int>()
        ids += SOT
        ids += encode(text)
        ids += EOT
        val out = IntArray(CONTEXT_LENGTH)
        val n = minOf(ids.size, CONTEXT_LENGTH)
        for (i in 0 until n) out[i] = ids[i]
        if (ids.size > CONTEXT_LENGTH) out[CONTEXT_LENGTH - 1] = EOT
        return out
    }

    fun encode(text: String): List<Int> {
        val clean = text.trim().replace(Regex("\\s+"), " ").lowercase()
        val out = ArrayList<Int>()
        val m = PAT.matcher(clean)
        while (m.find()) {
            val token = m.group().toByteArray(Charsets.UTF_8)
                .joinToString("") { byteEncoder[it.toInt() and 0xFF] }
            for (piece in bpe(token).split(' ')) encoder[piece]?.let { out += it }
        }
        return out
    }

    private fun bpe(token: String): String {
        cache[token]?.let { return it }
        val chars = token.codePoints().toArray().map { String(Character.toChars(it)) }
        if (chars.isEmpty()) return token
        var word: List<String> = chars.dropLast(1) + (chars.last() + "</w>")
        if (word.size == 1) return (token + "</w>").also { cache[token] = it }

        while (true) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val p = word[i] to word[i + 1]
                val r = bpeRanks[p] ?: continue
                if (r < bestRank) { bestRank = r; best = p }
            }
            if (best == null) break
            val (first, second) = best
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged += first + second
                    i += 2
                } else {
                    merged += word[i]
                    i += 1
                }
            }
            word = merged
            if (word.size == 1) break
        }
        return word.joinToString(" ").also { cache[token] = it }
    }

    companion object {
        const val CONTEXT_LENGTH = 77
        const val SOT = 49406
        const val EOT = 49407
        // Plain text on purpose: the Android build silently un-gzips *.gz assets and renames them.
        private const val ASSET = "clip_bpe_vocab.txt"

        private val PAT: Pattern = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )

        @Volatile private var instance: ClipTokenizer? = null

        fun get(ctx: Context): ClipTokenizer =
            instance ?: synchronized(this) {
                instance ?: fromStream(ctx.assets.open(ASSET)).also { instance = it }
            }

        fun fromStream(stream: InputStream): ClipTokenizer {
            val lines = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
            // Same slice as the reference: skip the header, keep 49152 - 256 - 2 merges.
            val merges = lines.subList(1, 49152 - 256 - 2 + 1).map { line ->
                val parts = line.split(' ')
                parts[0] to parts[1]
            }
            // Base vocab is in the reference dict's insertion order (printable bytes first), not byte order.
            val base = bytePairs().map { it.second }
            val vocab = ArrayList<String>(49408)
            vocab += base
            vocab += base.map { "$it</w>" }
            merges.forEach { (a, b) -> vocab += a + b }
            vocab += "<|startoftext|>"
            vocab += "<|endoftext|>"
            val encoder = HashMap<String, Int>(vocab.size * 2)
            vocab.forEachIndexed { i, v -> encoder[v] = i }
            val ranks = HashMap<Pair<String, String>, Int>(merges.size * 2)
            merges.forEachIndexed { i, p -> ranks[p] = i }
            return ClipTokenizer(encoder, ranks)
        }

        /** (byte, printable unicode char) pairs used by GPT-2/CLIP BPE, in reference order. */
        private fun bytePairs(): List<Pair<Int, String>> {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code)
            bs += ('¡'.code..'¬'.code)
            bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs += b
                    cs += 256 + n
                    n++
                }
            }
            return bs.indices.map { bs[it] to String(Character.toChars(cs[it])) }
        }

        /** Indexed by byte value. */
        private fun bytesToUnicode(): Array<String> {
            val out = Array(256) { "" }
            for ((b, s) in bytePairs()) out[b] = s
            return out
        }
    }
}
