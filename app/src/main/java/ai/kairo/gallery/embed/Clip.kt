package ai.kairo.gallery.embed

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device CLIP (OpenAI ViT-B/16, Qualcomm AI Hub TFLite export) for semantic image search.
 *
 * The export is ONE graph with both towers:
 *   inputs  image [1,224,224,3] float RGB in [0,1] (CLIP mean/std is applied inside), text [1,5,77] int32
 *   outputs image_features [1,512], text_features [5,512], both L2-normalized
 * So an image run feeds dummy text and a text run feeds a dummy image; cosine = dot product.
 *
 * Image embeddings (the indexing workload) run on the Snapdragon NPU (Hexagon HTP) when they can: LiteRT compiles
 * the graph for the NPU on the phone the first time (then caches it). Falls back to GPU+CPU, then CPU.
 *
 * Text embeddings never use the NPU: the text tower finds the end-of-text token with ARG_MAX over token ids, and
 * in the NPU's fp16 the end (49407) and start (49406) ids both round to 49408, so it picks the wrong token (cosine
 * ~0.6 vs GPU in testing). Text uses GPU+CPU; when images also end up on GPU+CPU, one session serves both.
 */
object Clip {
    private const val TAG = "KairoClip"
    const val MODEL_FILE_NAME = "clip.tflite"
    const val DIM = 512
    const val TEXT_SLOTS = 5
    private const val SIZE = 224
    private const val CPU_THREADS = 4

    /** Which accelerator to use: "auto" (NPU, then GPU+CPU, then CPU) or force "npu" / "gpu" / "cpu" (debug A/B). */
    @Volatile var preferred: String = "auto"
        private set
    private const val PREFS = "kairo_clip_tuning"

    /** Accelerator actually used for images (indexing), e.g. "NPU", once loaded. */
    @Volatile var accelerator: String? = null
        private set
    /** Accelerator used for search text, e.g. "GPU+CPU", once loaded. */
    @Volatile var textAccelerator: String? = null
        private set
    /** How long the image model's load took (includes the one-time NPU compile when nothing is cached). */
    @Volatile var loadMs: Long = 0
        private set
    /** Split timing of the last image embedding, for benchmarks. */
    @Volatile var lastPreprocessMs: Long = 0
        private set
    @Volatile var lastRunMs: Long = 0
        private set

    fun loadOverrides(ctx: Context) {
        preferred = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("accel", "auto") ?: "auto"
    }

    /** Debug A/B only; takes effect on the next load (the harness restarts the app). */
    fun saveOverride(ctx: Context, accel: String?) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (accel == null || accel == "auto") p.clear() else p.putString("accel", accel)
        p.commit()
        loadOverrides(ctx)
    }

    private class Session(
        val acc: String,
        val model: CompiledModel,
        val inputs: List<TensorBuffer>,
        val outputs: List<TensorBuffer>,
        var imageIn: Int,
        var textIn: Int,
    ) {
        // Outputs are told apart by size: image = 512 floats, text = 5 * 512. The NPU-patched model also exposes
        // 196 unused vision-token outputs (768 floats each, see tools/patch_clip_npu.ps1), so find ours once.
        val imageOut: Int
        val textOut: Int
        init {
            val sizes = outputs.map { it.readFloat().size }
            imageOut = sizes.indexOf(DIM)
            textOut = sizes.indexOf(DIM * TEXT_SLOTS)
            check(imageOut >= 0 && textOut >= 0) { "Unexpected CLIP outputs: $sizes" }
        }
    }

    private val _state = MutableStateFlow("CLIP not loaded")
    val state: StateFlow<String> = _state

    @Volatile private var imageSession: Session? = null
    @Volatile private var textSession: Session? = null
    private val lock = Mutex()

    fun modelFile(ctx: Context): File = File(ctx.getExternalFilesDir(null), MODEL_FILE_NAME)

    fun isAvailable(ctx: Context): Boolean = imageSession != null || modelFile(ctx).exists()

    /** True once the image (indexing) model is loaded. */
    fun isLoaded(): Boolean = imageSession != null

    /** Image -> 512-d unit vector. */
    suspend fun embedImage(ctx: Context, bmp: Bitmap): FloatArray = run(ctx, forText = false) { s ->
        val t0 = System.nanoTime()
        val px = preprocess(bmp)
        val t1 = System.nanoTime()
        writeInputs(s, px, IntArray(TEXT_SLOTS * ClipTokenizer.CONTEXT_LENGTH))
        s.model.run(s.inputs, s.outputs)
        val t2 = System.nanoTime()
        lastPreprocessMs = (t1 - t0) / 1_000_000
        lastRunMs = (t2 - t1) / 1_000_000
        Log.d(TAG, "image: preprocess $lastPreprocessMs ms, run $lastRunMs ms")
        imageOut(s)
    }

    /** Up to 5 texts -> one 512-d unit vector each. */
    suspend fun embedTexts(ctx: Context, texts: List<String>): List<FloatArray> {
        require(texts.isNotEmpty() && texts.size <= TEXT_SLOTS)
        val tok = ClipTokenizer.get(ctx)
        val ids = IntArray(TEXT_SLOTS * ClipTokenizer.CONTEXT_LENGTH)
        texts.forEachIndexed { i, t ->
            tok.tokenize(t).copyInto(ids, i * ClipTokenizer.CONTEXT_LENGTH)
        }
        return run(ctx, forText = true) { s ->
            writeInputs(s, FloatArray(SIZE * SIZE * 3), ids)
            s.model.run(s.inputs, s.outputs)
            val all = textOut(s)
            texts.indices.map { i -> all.copyOfRange(i * DIM, (i + 1) * DIM) }
        }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until DIM) s += a[i] * b[i]
        return s
    }

    fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val inv = 1f / max(sqrt(n), 1e-12f)
        return FloatArray(v.size) { v[it] * inv }
    }

    private suspend fun <T> run(ctx: Context, forText: Boolean, block: (Session) -> T): T =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val s = (if (forText) textSession else imageSession) ?: load(ctx.applicationContext, forText)
                block(s)
            }
        }

    private fun load(ctx: Context, forText: Boolean): Session {
        val file = modelFile(ctx)
        if (!file.exists()) {
            _state.value = "CLIP missing → adb push it to ${file.absolutePath}"
            throw IllegalStateException("CLIP model not found: ${file.absolutePath}")
        }
        loadOverrides(ctx)
        // NPU+CPU / GPU+CPU: ops the accelerator can't take (the text tower's int ops) run on CPU instead of failing.
        val all = listOf(
            "NPU" to setOf(Accelerator.NPU, Accelerator.CPU),
            "GPU+CPU" to setOf(Accelerator.GPU, Accelerator.CPU),
            "CPU" to setOf(Accelerator.CPU),
        )
        val forced = when (preferred) {
            "npu" -> all.subList(0, 1)
            "gpu" -> all.subList(1, 2)
            "cpu" -> all.subList(2, 3)
            else -> all
        }
        // Text is wrong on the NPU (see class doc), so it always takes the GPU+CPU / CPU route.
        val attempts = if (forText) forced.filter { it.first != "NPU" }.ifEmpty { all.subList(1, 3) } else forced
        val kind = if (forText) "text" else "images"
        var lastError: Throwable? = null
        for ((acc, set) in attempts) {
            // Both roles on the same accelerator: share one session instead of loading 600 MB twice.
            val other = if (forText) imageSession else textSession
            if (other != null && other.acc == acc) return assign(other, forText, kind, 0)
            try {
                _state.value = if (acc == "NPU") "Preparing CLIP for the NPU…" else "Loading CLIP on $acc…"
                val t0 = System.currentTimeMillis()
                val options = CompiledModel.Options(set).apply {
                    cpuOptions = CompiledModel.CpuOptions(CPU_THREADS, null, null)
                    if (acc == "NPU") {
                        qualcommOptions = CompiledModel.QualcommOptions(
                            logLevel = CompiledModel.QualcommOptions.LogLevel.WARN,
                            // Indexing runs in bursts: finish them fast rather than sipping power slowly.
                            htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST,
                        )
                    }
                }
                val model = if (acc == "NPU") {
                    val npu = BuiltinNpuAcceleratorProvider(ctx)
                    check(npu.isDeviceSupported()) { "NPU not supported on this SoC" }
                    // Caches the compiled NPU graph in cacheDir, so only the first launch pays for compiling.
                    CompiledModel.create(file.absolutePath, options, Environment.create(ctx, npu))
                } else {
                    CompiledModel.create(file.absolutePath, options)
                }
                val s = Session(acc, model, model.createInputBuffers(), model.createOutputBuffers(), 0, 1)
                // Smoke-test run; also fixes input order if the export lists text first.
                val img = FloatArray(SIZE * SIZE * 3)
                val txt = IntArray(TEXT_SLOTS * ClipTokenizer.CONTEXT_LENGTH)
                writeInputs(s, img, txt)
                model.run(s.inputs, s.outputs)
                return assign(s, forText, kind, System.currentTimeMillis() - t0)
            } catch (t: Throwable) {
                Log.w(TAG, "CLIP ($kind) on $acc failed", t)
                lastError = t
            }
        }
        _state.value = "CLIP failed to load: ${lastError?.message}"
        throw lastError ?: IllegalStateException("CLIP failed to load")
    }

    private fun assign(s: Session, forText: Boolean, kind: String, ms: Long): Session {
        if (forText) {
            textSession = s
            textAccelerator = s.acc
        } else {
            imageSession = s
            accelerator = s.acc
            loadMs = ms
        }
        _state.value = "CLIP ready: images on ${accelerator ?: "–"}, text on ${textAccelerator ?: "–"}"
        Log.i(TAG, "CLIP ($kind) ready on ${s.acc}" + if (ms > 0) " (loaded in $ms ms)" else " (shared session)")
        return s
    }

    private fun writeInputs(s: Session, image: FloatArray, text: IntArray) {
        try {
            s.inputs[s.imageIn].writeFloat(image)
            s.inputs[s.textIn].writeInt(text)
        } catch (t: Throwable) {
            // Wrong guess about input order: a size/type mismatch throws. Swap once and retry.
            val tmp = s.imageIn; s.imageIn = s.textIn; s.textIn = tmp
            Log.i(TAG, "Swapped CLIP input order (image=${s.imageIn}, text=${s.textIn})")
            s.inputs[s.imageIn].writeFloat(image)
            s.inputs[s.textIn].writeInt(text)
        }
    }

    private fun imageOut(s: Session): FloatArray = s.outputs[s.imageOut].readFloat()

    private fun textOut(s: Session): FloatArray = s.outputs[s.textOut].readFloat()

    /** Resize shortest side to 224, center-crop 224x224, RGB floats in [0,1], NHWC. */
    private fun preprocess(src: Bitmap): FloatArray {
        val scale = SIZE.toFloat() / minOf(src.width, src.height)
        val w = max(SIZE, (src.width * scale).roundToInt())
        val h = max(SIZE, (src.height * scale).roundToInt())
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val scaled = Bitmap.createScaledBitmap(soft, w, h, true)
        val x0 = (w - SIZE) / 2
        val y0 = (h - SIZE) / 2
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, x0, y0, SIZE, SIZE)
        val out = FloatArray(SIZE * SIZE * 3)
        for (i in px.indices) {
            val p = px[i]
            out[i * 3] = ((p shr 16) and 0xFF) / 255f
            out[i * 3 + 1] = ((p shr 8) and 0xFF) / 255f
            out[i * 3 + 2] = (p and 0xFF) / 255f
        }
        return out
    }
}
