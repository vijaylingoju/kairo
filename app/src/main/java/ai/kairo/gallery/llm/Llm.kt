package ai.kairo.gallery.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemma knobs. Defaults are the measured best (see docs/reports); the debug eval harness can flip them
 * at runtime (EVAL_CONFIG) to A/B test speed against quality without rebuilding.
 */
object LlmTuning {
    /**
     * A small draft model proposes tokens, Gemma verifies them: same output, faster writing.
     * Measured on the iQOO 15 (2026-09-26, 19 photos): identical categories/fields/tags, writing 17 -> 28-38
     * tokens/s, Gemma time per document 9.7 -> 5.7 s, whole gallery 149 -> 96 s.
     */
    @Volatile var speculativeDecoding = true
    /** Always pick the most likely token: the same photo gets the same tags on every re-index. */
    @Volatile var greedy = false
    /** Short photo prompt for photos without text; documents omit empty fields instead of writing null. */
    @Volatile var compactIndexPrompts = false
    /** Image tokens per photo (null = model default). Fewer = faster look/read, possibly less detail. */
    @Volatile var visualTokenBudget: Int? = null

    override fun toString() = "spec=$speculativeDecoding greedy=$greedy compact=$compactIndexPrompts vtb=${visualTokenBudget ?: "default"}"

    private const val PREFS = "kairo_llm_tuning"  // only ever written by the debug eval harness

    /** Applies overrides saved by the debug harness, so an A/B setting survives a process restart. */
    fun loadOverrides(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains("spec")) speculativeDecoding = p.getBoolean("spec", speculativeDecoding)
        if (p.contains("greedy")) greedy = p.getBoolean("greedy", greedy)
        if (p.contains("compact")) compactIndexPrompts = p.getBoolean("compact", compactIndexPrompts)
        if (p.contains("vtb")) visualTokenBudget = p.getInt("vtb", 0).takeIf { it > 0 }
    }

    fun saveOverrides(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("spec", speculativeDecoding)
            .putBoolean("greedy", greedy)
            .putBoolean("compact", compactIndexPrompts)
            .putInt("vtb", visualTokenBudget ?: 0)
            .commit()
    }

    fun clearOverrides(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        speculativeDecoding = true
        greedy = false
        compactIndexPrompts = false
        visualTokenBudget = null
    }
}

/**
 * Single shared Gemma engine. Loading takes several seconds, so it's done once and reused.
 * Inference is serialized with a mutex (one request at a time on the GPU).
 */
object Llm {
    private const val TAG = "KairoLlm"
    const val MODEL_FILE_NAME = "model.litertlm"
    private val GREEDY = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)

    private val _state = MutableStateFlow("Model not loaded")
    val state: StateFlow<String> = _state

    /** Timing of the most recent call (prefill = reading the input, decode = writing the answer). */
    @Volatile var lastBench: BenchmarkInfo? = null
        private set

    /** [SystemClock.elapsedRealtime] when the model was last loaded or used; for unloading it when idle. */
    @Volatile var lastUsedAt = 0L
        private set

    @Volatile private var engine: Engine? = null
    private val loadLock = Mutex()
    private val runLock = Mutex()

    /**
     * Picks the strongest Gemma pushed to the app folder (E4B > E2B > anything else),
     * falling back to the generic model.litertlm name.
     */
    fun modelFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null)
        val candidates = dir?.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }.orEmpty()
        fun rank(f: File) = when {
            "e4b" in f.name.lowercase() -> 0
            "e2b" in f.name.lowercase() -> 1
            else -> 2
        }
        return candidates.minByOrNull(::rank) ?: File(dir, MODEL_FILE_NAME)
    }

    fun isReady(): Boolean = engine != null

    /** Loads the model if needed. Tries GPU first, falls back to CPU. */
    suspend fun ensure(ctx: Context): Engine = withContext(Dispatchers.IO) {
        engine ?: loadLock.withLock { engine ?: load(ctx.applicationContext) }
    }

    /**
     * Frees the model's memory (the floating ball does this after a few idle minutes). The next call loads it
     * again, with the current [LlmTuning]. Waits for a running generation to finish first.
     */
    suspend fun unload() {
        loadLock.withLock {
            runLock.withLock {
                engine?.close()
                engine = null
                _state.value = "Model not loaded"
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun load(ctx: Context): Engine {
        LlmTuning.loadOverrides(ctx)
        // Engine-level flags must be set before the engine is created.
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = LlmTuning.speculativeDecoding
        ExperimentalFlags.visualTokenBudget = LlmTuning.visualTokenBudget
        val file = modelFile(ctx)
        if (!file.exists()) {
            _state.value = "Model missing → adb push it to ${file.absolutePath}"
            throw IllegalStateException("Model file not found: ${file.absolutePath}")
        }
        val attempts: List<Pair<String, () -> EngineConfig>> = listOf(
            "GPU" to {
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    cacheDir = ctx.cacheDir.absolutePath,
                )
            },
            "CPU" to {
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    cacheDir = ctx.cacheDir.absolutePath,
                )
            },
        )
        var lastError: Throwable? = null
        for ((name, config) in attempts) {
            try {
                _state.value = "Loading ${file.name} on $name…"
                val t0 = System.currentTimeMillis()
                val e = Engine(config())
                e.initialize()
                engine = e
                lastUsedAt = SystemClock.elapsedRealtime()
                _state.value = "${file.nameWithoutExtension} ready on $name (loaded in ${System.currentTimeMillis() - t0} ms)"
                Log.i(TAG, "${_state.value} [$LlmTuning]")
                return e
            } catch (t: Throwable) {
                Log.w(TAG, "Backend $name failed", t)
                lastError = t
            }
        }
        _state.value = "Model failed to load: ${lastError?.message}"
        throw lastError ?: IllegalStateException("Model failed to load")
    }

    /** One fresh conversation per call, so earlier images never leak into the next answer. */
    /**
     * Every call is bounded: at most [maxTokens] output tokens, and a watchdog cancels the native
     * generation after [timeoutMs]. Without this one runaway answer (seen in testing: a query that never
     * finished) holds [runLock] forever and blocks every later search and indexing step.
     */
    @OptIn(ExperimentalApi::class)
    suspend fun generate(
        ctx: Context,
        vararg contents: Content,
        maxTokens: Int = 384,
        timeoutMs: Long = 30_000,
    ): String {
        val e = ensure(ctx)
        return withContext(Dispatchers.IO) {
            runLock.withLock {
                val config = if (LlmTuning.greedy) ConversationConfig(samplerConfig = GREEDY, maxOutputToken = maxTokens)
                else ConversationConfig(maxOutputToken = maxTokens)
                val tStart = SystemClock.elapsedRealtime()
                var tCreated = 0L
                var tSent = 0L
                val result = e.createConversation(config).use { convo ->
                    tCreated = SystemClock.elapsedRealtime()
                    val timedOut = AtomicBoolean(false)
                    val watchdog = launch {
                        delay(timeoutMs)
                        timedOut.set(true)
                        Log.w(TAG, "Generation exceeded $timeoutMs ms, cancelling")
                        convo.cancelProcess()
                    }
                    try {
                        val reply = convo.sendMessage(Contents.of(*contents), maxOutputToken = maxTokens).toString()
                        tSent = SystemClock.elapsedRealtime()
                        if (timedOut.get()) throw IllegalStateException("Gemma timed out after $timeoutMs ms")
                        runCatching { convo.getBenchmarkInfo() }.getOrNull()?.let { b ->
                            lastBench = b
                            Log.i(
                                TAG,
                                "bench ttft=%.2fs prefill=%d tok @ %.0f/s decode=%d tok @ %.1f/s [%s]".format(
                                    b.timeToFirstTokenInSecond, b.lastPrefillTokenCount, b.lastPrefillTokensPerSecond,
                                    b.lastDecodeTokenCount, b.lastDecodeTokensPerSecond, LlmTuning,
                                )
                            )
                        }
                        reply
                    } finally {
                        watchdog.cancel()
                    }
                }
                val tEnd = SystemClock.elapsedRealtime()
                lastUsedAt = tEnd
                // Where the fixed cost per call goes: conversation set-up, generation, tear-down.
                Log.i(TAG, "timing create=${tCreated - tStart}ms send=${tSent - tCreated}ms close=${tEnd - tSent}ms")
                result
            }
        }
    }

    /** Pulls the first {...} block out of a model reply and parses it. Returns null if it isn't valid JSON. */
    fun extractJson(reply: String): JSONObject? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        // Raw newlines inside strings break JSON; whitespace between tokens is harmless.
        val candidate = reply.substring(start, end + 1).replace('\n', ' ').replace('\r', ' ')
        return try {
            JSONObject(candidate)
        } catch (_: Exception) {
            null
        }
    }
}
