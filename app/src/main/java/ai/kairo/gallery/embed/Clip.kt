package ai.kairo.gallery.embed

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
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
 */
object Clip {
    private const val TAG = "KairoClip"
    const val MODEL_FILE_NAME = "clip.tflite"
    const val DIM = 512
    const val TEXT_SLOTS = 5
    private const val SIZE = 224
    private const val CPU_THREADS = 4

    private class Session(
        val model: CompiledModel,
        val inputs: List<TensorBuffer>,
        val outputs: List<TensorBuffer>,
        var imageIn: Int,
        var textIn: Int,
    )

    private val _state = MutableStateFlow("CLIP not loaded")
    val state: StateFlow<String> = _state

    @Volatile private var session: Session? = null
    private val lock = Mutex()

    fun modelFile(ctx: Context): File = File(ctx.getExternalFilesDir(null), MODEL_FILE_NAME)

    fun isAvailable(ctx: Context): Boolean = session != null || modelFile(ctx).exists()

    fun isLoaded(): Boolean = session != null

    /** Image -> 512-d unit vector. */
    suspend fun embedImage(ctx: Context, bmp: Bitmap): FloatArray = run(ctx) { s ->
        val t0 = System.nanoTime()
        val px = preprocess(bmp)
        val t1 = System.nanoTime()
        writeInputs(s, px, IntArray(TEXT_SLOTS * ClipTokenizer.CONTEXT_LENGTH))
        s.model.run(s.inputs, s.outputs)
        val t2 = System.nanoTime()
        Log.d(TAG, "image: preprocess ${(t1 - t0) / 1_000_000} ms, run ${(t2 - t1) / 1_000_000} ms")
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
        return run(ctx) { s ->
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

    private suspend fun <T> run(ctx: Context, block: (Session) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { block(session ?: load(ctx.applicationContext)) }
    }

    private fun load(ctx: Context): Session {
        val file = modelFile(ctx)
        if (!file.exists()) {
            _state.value = "CLIP missing → adb push it to ${file.absolutePath}"
            throw IllegalStateException("CLIP model not found: ${file.absolutePath}")
        }
        // GPU+CPU lets ops the GPU can't compile (the text tower's int ops) run on CPU instead of failing.
        val attempts = listOf(
            "GPU+CPU" to setOf(Accelerator.GPU, Accelerator.CPU),
            "CPU" to setOf(Accelerator.CPU),
        )
        var lastError: Throwable? = null
        for ((acc, set) in attempts) {
            try {
                _state.value = "Loading CLIP on $acc…"
                val t0 = System.currentTimeMillis()
                val options = CompiledModel.Options(set).apply {
                    cpuOptions = CompiledModel.CpuOptions(CPU_THREADS, null, null)
                }
                val model = CompiledModel.create(file.absolutePath, options)
                val s = Session(model, model.createInputBuffers(), model.createOutputBuffers(), 0, 1)
                // Smoke-test run; also fixes input order if the export lists text first.
                val img = FloatArray(SIZE * SIZE * 3)
                val txt = IntArray(TEXT_SLOTS * ClipTokenizer.CONTEXT_LENGTH)
                writeInputs(s, img, txt)
                model.run(s.inputs, s.outputs)
                session = s
                _state.value = "CLIP ready on $acc (loaded in ${System.currentTimeMillis() - t0} ms)"
                Log.i(TAG, _state.value)
                return s
            } catch (t: Throwable) {
                Log.w(TAG, "CLIP on $acc failed", t)
                lastError = t
            }
        }
        _state.value = "CLIP failed to load: ${lastError?.message}"
        throw lastError ?: IllegalStateException("CLIP failed to load")
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

    // Outputs are told apart by size: image = 512 floats, text = 5 * 512.
    private fun imageOut(s: Session): FloatArray =
        s.outputs.map { it.readFloat() }.first { it.size == DIM }

    private fun textOut(s: Session): FloatArray =
        s.outputs.map { it.readFloat() }.first { it.size == DIM * TEXT_SLOTS }

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
