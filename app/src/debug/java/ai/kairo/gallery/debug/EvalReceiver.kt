package ai.kairo.gallery.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import ai.kairo.gallery.data.IndexDb
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
                    // A/B switches for Gemma (see LlmTuning); the engine reloads with the new settings.
                    ACTION_CONFIG -> emit(ctx, id, runCatching {
                        if (intent.hasExtra("spec")) LlmTuning.speculativeDecoding = intent.getBooleanExtra("spec", false)
                        if (intent.hasExtra("greedy")) LlmTuning.greedy = intent.getBooleanExtra("greedy", false)
                        if (intent.hasExtra("compact")) LlmTuning.compactIndexPrompts = intent.getBooleanExtra("compact", false)
                        if (intent.hasExtra("vtb")) LlmTuning.visualTokenBudget = intent.getIntExtra("vtb", 0).takeIf { it > 0 }
                        if (intent.getBooleanExtra("reset", false)) { LlmTuning.clearOverrides(ctx) }  // back to the built-in defaults
                        else LlmTuning.saveOverrides(ctx)
                        // No in-process reload: closing and re-creating the engine right after a load hung LiteRT-LM
                        // in testing. The harness force-stops the app instead; the next process loads these settings.
                        JSONObject().put("id", id).put("tuning", LlmTuning.toString()).put("saved", true)
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
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
