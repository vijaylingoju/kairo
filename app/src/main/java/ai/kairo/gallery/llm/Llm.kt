package ai.kairo.gallery.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Single shared Gemma engine. Loading takes several seconds, so it's done once and reused.
 * Inference is serialized with a mutex (one request at a time on the GPU).
 */
object Llm {
    private const val TAG = "KairoLlm"
    const val MODEL_FILE_NAME = "model.litertlm"

    private val _state = MutableStateFlow("Model not loaded")
    val state: StateFlow<String> = _state

    @Volatile private var engine: Engine? = null
    private val loadLock = Mutex()
    private val runLock = Mutex()

    fun modelFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), MODEL_FILE_NAME)

    fun isReady(): Boolean = engine != null

    /** Loads the model if needed. Tries GPU first, falls back to CPU. */
    suspend fun ensure(ctx: Context): Engine = withContext(Dispatchers.IO) {
        engine ?: loadLock.withLock { engine ?: load(ctx.applicationContext) }
    }

    private fun load(ctx: Context): Engine {
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
                _state.value = "Loading Gemma on $name…"
                val t0 = System.currentTimeMillis()
                val e = Engine(config())
                e.initialize()
                engine = e
                _state.value = "Gemma ready on $name (loaded in ${System.currentTimeMillis() - t0} ms)"
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
    suspend fun generate(ctx: Context, vararg contents: Content): String {
        val e = ensure(ctx)
        return withContext(Dispatchers.IO) {
            runLock.withLock {
                e.createConversation().use { convo ->
                    convo.sendMessage(Contents.of(*contents)).toString()
                }
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
