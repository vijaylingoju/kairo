package ai.kairo.gallery.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import ai.kairo.gallery.data.IndexDb
import ai.kairo.gallery.data.Prefs
import ai.kairo.gallery.index.GalleryScanner
import ai.kairo.gallery.embed.Clip
import ai.kairo.gallery.llm.Llm
import ai.kairo.gallery.llm.LlmTuning
import ai.kairo.gallery.index.IndexScheduler
import ai.kairo.gallery.index.Indexer
import ai.kairo.gallery.search.SearchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DEBUG BUILDS ONLY: drives the real search/index code from adb for repeatable evaluation.
 *
 *   adb shell "am broadcast -p ai.kairo.gallery -a ai.kairo.gallery.EVAL_SEARCH --es id v01 --es q 'golden retriever'"
 *   adb shell "am broadcast -p ai.kairo.gallery -a ai.kairo.gallery.EVAL_STATUS --es id s1"
 *
 * Each result is written to <app files>/eval/<id>.json and announced as "DONE <id>" under logcat tag KairoEval.
 * Pass the query as --es q64 <base64 UTF-8> to keep non-ASCII queries intact through adb.
 */
class EvalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val id = intent.getStringExtra("id") ?: "-"
        Log.i(TAG, "RECEIVED ${intent.action} $id")
        // goAsync keeps the process awake until we finish; without it the cached-app freezer
        // suspends a background app as soon as onReceive returns and the work never completes.
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_SEARCH -> {
                        val q = intent.getStringExtra("q64")
                            ?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
                            ?: intent.getStringExtra("q").orEmpty()
                        val useLlm = !intent.getBooleanExtra("nollm", false)
                        emit(ctx, id, runCatching { search(ctx, id, q, useLlm) })
                    }
                    ACTION_STATUS -> emit(ctx, id, runCatching { status(ctx, id) })
                    ACTION_CLIP_BENCH -> emit(ctx, id, runCatching { clipBench(ctx, id, intent.getIntExtra("rounds", 3)) })
                    // A/B switches for Gemma (see LlmTuning); the engine reloads with the new settings.
                    ACTION_CONFIG -> emit(ctx, id, runCatching {
                        if (intent.hasExtra("spec")) LlmTuning.speculativeDecoding = intent.getBooleanExtra("spec", false)
                        if (intent.hasExtra("greedy")) LlmTuning.greedy = intent.getBooleanExtra("greedy", false)
                        if (intent.hasExtra("compact")) LlmTuning.compactIndexPrompts = intent.getBooleanExtra("compact", false)
                        if (intent.hasExtra("vtb")) LlmTuning.visualTokenBudget = intent.getIntExtra("vtb", 0).takeIf { it > 0 }
                        if (intent.hasExtra("clip")) Clip.saveOverride(ctx, intent.getStringExtra("clip"))  // auto | npu | gpu | cpu
                        if (intent.getBooleanExtra("reset", false)) { LlmTuning.clearOverrides(ctx); Clip.saveOverride(ctx, null) }  // back to the built-in defaults
                        else LlmTuning.saveOverrides(ctx)
                        // No in-process reload: closing and re-creating the engine right after a load hung LiteRT-LM
                        // in testing. The harness force-stops the app instead; the next process loads these settings.
                        JSONObject().put("id", id).put("tuning", LlmTuning.toString()).put("clip", Clip.preferred).put("saved", true)
                    })
                    // Same steps as the "Clear all data & start fresh" button.
                    ACTION_CLEAR -> emit(ctx, id, runCatching {
                        IndexScheduler.cancelAll(ctx)
                        Indexer.clearAll(ctx)
                        IndexScheduler.scheduleWatch(ctx)
                        IndexScheduler.runNow(ctx)
                        JSONObject().put("id", id).put("cleared", true)
                    })
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun search(ctx: Context, id: String, q: String, useLlm: Boolean): JSONObject {
        val r = SearchEngine.search(ctx, q, useLlm)
        val f = r.filter
        return JSONObject()
            .put("id", id)
            .put("q", q)
            .put("ms", r.tookMs)
            .put("cats", JSONArray(f.categories.toList()))
            .put("kw", JSONArray(f.keywords))
            .put("field", f.wantedField ?: JSONObject.NULL)
            .put("visual", f.visual ?: JSONObject.NULL)
            .put("llm", f.usedLlm)
            .put("date", if (f.dateFromMs != null || f.dateToMs != null) "${f.dateFromMs}..${f.dateToMs}" else JSONObject.NULL)
            .put("answer", r.answer ?: JSONObject.NULL)
            .put("topSim", r.topSim?.toDouble() ?: JSONObject.NULL)
            .put("allSims", JSONObject().apply {
                val names = IndexDb.get(ctx).all().associate { it.mediaId to it.name }
                r.sims.forEach { (id, s) -> put(names[id] ?: id.toString(), s.toDouble()) }
            })
            .put("allAdj", JSONObject().apply {
                val names = IndexDb.get(ctx).all().associate { it.mediaId to it.name }
                r.adjusted.forEach { (id, s) -> put(names[id] ?: id.toString(), s.toDouble()) }
            })
            .put("results", JSONArray(r.items.map { img ->
                JSONObject()
                    .put("name", img.name)
                    .put("cat", img.category)
                    .put("score", (r.scores[img.mediaId] ?: 0f).toDouble())
                    .put("sim", r.sims[img.mediaId]?.toDouble() ?: JSONObject.NULL)
            }))
    }

    private fun status(ctx: Context, id: String): JSONObject {
        val s = Indexer.status.value
        val db = IndexDb.get(ctx)
        return JSONObject()
            .put("id", id)
            .put("running", s.running)
            .put("done", s.done)
            .put("total", s.total)
            .put("message", s.message)
            .put("indexed", db.count())
            .put("embedded", db.embeddingIds().size)
            .put("llmReady", Llm.isReady())
            .put("tuning", LlmTuning.toString())
            .put("llm", Llm.state.value)
            .put("clip", Clip.state.value)
    }

    /**
     * CLIP speed on the real indexing path: every gallery photo is decoded + cropped exactly like the visual pass,
     * then embedded [rounds] times. Also times query-text embedding and returns the image vectors so two runs
     * (e.g. GPU vs NPU) can be compared for quality.
     */
    private suspend fun clipBench(ctx: Context, id: String, rounds: Int): JSONObject {
        // Load both sessions (images: NPU compile on first launch; text: GPU+CPU) outside the timing.
        Clip.embedTexts(ctx, listOf("warm-up"))
        Clip.embedImage(ctx, android.graphics.Bitmap.createBitmap(224, 224, android.graphics.Bitmap.Config.ARGB_8888))
        val items = GalleryScanner.scan(ctx, Prefs.screenshotsSince(ctx))
        val decodeMs = mutableListOf<Long>()
        val preMs = mutableListOf<Long>()
        val runMs = mutableListOf<Long>()
        val totalMs = mutableListOf<Long>()
        val vectors = JSONObject()
        repeat(rounds) { r ->
            for (item in items) {
                val t0 = SystemClock.elapsedRealtime()
                val bmp = Indexer.clipInput(ctx, item)
                val t1 = SystemClock.elapsedRealtime()
                val vec = Clip.embedImage(ctx, bmp)
                val t2 = SystemClock.elapsedRealtime()
                decodeMs += t1 - t0
                preMs += Clip.lastPreprocessMs
                runMs += Clip.lastRunMs
                totalMs += t2 - t0
                if (r == rounds - 1) vectors.put(item.name, JSONArray(vec.map { it.toDouble() }))
            }
        }
        val queries = listOf("a dog on the beach", "movie ticket", "food on a plate", "a red car", "sunset")
        val textMs = (1..10).map {
            val t0 = SystemClock.elapsedRealtime()
            Clip.embedTexts(ctx, queries)
            SystemClock.elapsedRealtime() - t0
        }
        // Text vectors too, so accelerators can be compared on the query side as well.
        val probe = listOf(
            "sunset at the beach", "a man with makeup", "golden retriever", "a red car", "movie ticket",
            "ice cream", "a cat", "wedding photos", "snowy mountains", "food on a plate",
        )
        val textVectors = JSONObject()
        probe.chunked(Clip.TEXT_SLOTS).forEach { chunk ->
            Clip.embedTexts(ctx, chunk).forEachIndexed { i, v -> textVectors.put(chunk[i], JSONArray(v.map { it.toDouble() })) }
        }
        val oneQueryMs = (1..10).map {
            val t0 = SystemClock.elapsedRealtime()
            Clip.embedTexts(ctx, listOf("golden retriever"))
            SystemClock.elapsedRealtime() - t0
        }
        fun stats(v: List<Long>) = JSONObject().apply {
            val s = v.sorted()
            put("avg", if (s.isEmpty()) 0 else s.average())
            put("p50", s.getOrElse(s.size / 2) { 0 })
            put("p90", s.getOrElse((s.size * 9) / 10) { 0 })
            put("min", s.firstOrNull() ?: 0)
            put("max", s.lastOrNull() ?: 0)
        }
        return JSONObject()
            .put("id", id)
            .put("accelerator", Clip.accelerator)
            .put("textAccelerator", Clip.textAccelerator)
            .put("preferred", Clip.preferred)
            .put("loadMs", Clip.loadMs)
            .put("photos", items.size)
            .put("rounds", rounds)
            .put("decodeMs", stats(decodeMs))
            .put("preprocessMs", stats(preMs))
            .put("runMs", stats(runMs))
            .put("perPhotoMs", stats(totalMs))
            .put("text5Ms", stats(textMs))
            .put("text1Ms", stats(oneQueryMs))
            .put("vectors", vectors)
            .put("textVectors", textVectors)
    }

    /** Writes the result to files/eval/<id>.json (logcat truncates long lines) and logs DONE. */
    private fun emit(ctx: Context, id: String, r: Result<JSONObject>) {
        val json = r.getOrElse { t ->
            JSONObject().put("id", id).put("error", t.toString())
        }
        val dir = File(ctx.getExternalFilesDir(null), "eval").apply { mkdirs() }
        // Write-then-rename so the runner never reads a half-written file.
        val tmp = File(dir, "$id.json.tmp").apply { writeText(json.toString()) }
        tmp.renameTo(File(dir, "$id.json"))
        Log.i(TAG, "DONE $id")
    }

    companion object {
        private const val TAG = "KairoEval"
        const val ACTION_SEARCH = "ai.kairo.gallery.EVAL_SEARCH"
        const val ACTION_STATUS = "ai.kairo.gallery.EVAL_STATUS"
        const val ACTION_CLEAR = "ai.kairo.gallery.EVAL_CLEAR"
        const val ACTION_CONFIG = "ai.kairo.gallery.EVAL_CONFIG"
        const val ACTION_CLIP_BENCH = "ai.kairo.gallery.EVAL_CLIP_BENCH"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
