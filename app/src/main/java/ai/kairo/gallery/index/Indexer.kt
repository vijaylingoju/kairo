package ai.kairo.gallery.index

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import ai.kairo.gallery.data.IndexDb
import ai.kairo.gallery.data.IndexedImage
import ai.kairo.gallery.data.Prefs
import ai.kairo.gallery.embed.Clip
import ai.kairo.gallery.llm.Llm
import ai.kairo.gallery.llm.Prompts
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Two passes per run:
 * 1) CLIP image embedding for every new photo (fast; makes it visually searchable).
 * 2) metadata -> OCR -> Gemma (image + OCR) -> regex/grounding checks -> SQLite.
 */
object Indexer {
    private const val TAG = "KairoIndexer"

    data class Status(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val current: String = "",
        val lastMs: Long = 0,
        val message: String = "Idle",
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val lock = Mutex()

    fun hasGalleryPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    suspend fun run(ctx: Context, force: Boolean) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!hasGalleryPermission(ctx)) {
                _status.value = Status(message = "Grant photo access (Allow all) to start indexing")
                return@withContext
            }
            val db = IndexDb.get(ctx)
            val items = GalleryScanner.scan(ctx, Prefs.screenshotsSince(ctx))
            db.deleteMissing(items.map { it.id }.toSet())

            // Pass 1 (fast, ~tens of ms/image): CLIP vectors, so every photo is visually searchable right away.
            embedPass(ctx, db, items, force)

            val known = db.known()
            val todo = items.filter { item ->
                val k = known[item.id]
                force || k == null || k.first != item.dateModified || k.second != "done"
            }
            if (todo.isEmpty()) {
                _status.value = Status(message = "Up to date • ${db.count()} images indexed")
                return@withContext
            }

            // Pass 2 (slow, seconds/image): OCR + Gemma for categories, text and exact fields.
            _status.value = Status(running = true, total = todo.size, message = "Indexing…")
            todo.forEachIndexed { i, item ->
                _status.value = _status.value.copy(done = i, current = item.name)
                val img = indexOne(ctx, item)
                db.upsert(img)
                _status.value = _status.value.copy(done = i + 1, lastMs = img.indexMs)
                Log.i(TAG, "Indexed ${item.name} as ${img.category} in ${img.indexMs} ms (${img.status})")
            }
            _status.value = _status.value.copy(
                running = false,
                current = "",
                message = "Indexed ${todo.size} new • ${db.count()} total",
            )
        }
    }

    private suspend fun embedPass(ctx: Context, db: IndexDb, items: List<MediaItem>, force: Boolean) {
        if (!Clip.isAvailable(ctx)) {
            Log.w(TAG, "CLIP model missing, skipping visual index")
            return
        }
        val have = db.embeddingIds()
        val known = db.known()
        val todo = items.filter { force || it.id !in have || known[it.id]?.first != it.dateModified }
        if (todo.isEmpty()) return

        _status.value = Status(running = true, total = todo.size, message = "Visual index")
        for ((i, item) in todo.withIndex()) {
            _status.value = _status.value.copy(done = i, current = item.name)
            val t0 = SystemClock.elapsedRealtime()
            try {
                val bmp = decode(ctx, item.uri, 512)
                val tDecode = SystemClock.elapsedRealtime() - t0
                db.setEmbedding(item.id, Clip.embedImage(ctx, bmp))
                Log.d(TAG, "decode ${item.name} $tDecode ms")
                // New photo: add a placeholder row so it shows up in search before Gemma reads it.
                if (item.id !in known) db.upsert(placeholder(item))
            } catch (t: Throwable) {
                Log.w(TAG, "CLIP failed for ${item.name}", t)
                if (!Clip.isLoaded()) return  // model can't load; don't retry per image
            }
            val ms = SystemClock.elapsedRealtime() - t0
            _status.value = _status.value.copy(done = i + 1, lastMs = ms)
            Log.i(TAG, "Embedded ${item.name} in $ms ms")
        }
    }

    private fun placeholder(item: MediaItem) = IndexedImage(
        mediaId = item.id, uri = item.uri.toString(), name = item.name, folder = item.folder,
        dateTaken = item.dateTaken, dateModified = item.dateModified, lat = null, lng = null,
        category = "other", description = "", tags = emptyList(), fields = emptyMap(), ocrText = "",
        status = "pending", error = null, indexMs = 0,
    )

    private suspend fun indexOne(ctx: Context, item: MediaItem): IndexedImage {
        val t0 = SystemClock.elapsedRealtime()
        var ocr = ""
        var lat: Double? = null
        var lng: Double? = null
        try {
            // 1) Decode once at OCR resolution (ImageDecoder also applies EXIF rotation).
            val big = decode(ctx, item.uri, 2048)

            // 2) OCR — exact text.
            ocr = Ocr.read(big)

            // 3) Metadata (GPS).
            readLatLng(ctx, item.uri)?.let { lat = it.first; lng = it.second }

            // 4) Gemma: smaller JPEG + OCR text.
            val jpeg = toJpeg(scaleDown(big, 1024))
            val prompt = Prompts.index(ocr.take(1500))
            var json: JSONObject? = null
            var llmError: String? = null
            try {
                json = Llm.extractJson(
                    Llm.generate(ctx, Content.ImageBytes(jpeg), Content.Text(prompt))
                ) ?: Llm.extractJson( // one retry if the JSON was broken
                    Llm.generate(ctx, Content.ImageBytes(jpeg), Content.Text(prompt))
                )
                if (json == null) llmError = "Model returned invalid JSON"
            } catch (t: Throwable) {
                Log.w(TAG, "LLM failed for ${item.name}", t)
                llmError = t.message ?: t.javaClass.simpleName
            }

            // 5) Checks: grounding + regex + rules.
            val llmFields = HashMap<String, String?>()
            json?.optJSONObject("fields")?.let { f ->
                for (k in Prompts.FIELD_KEYS) llmFields[k] = if (f.isNull(k)) null else f.optString(k)
            }
            val (category, fields) = FieldExtractor.finalize(
                llmCategory = json?.optString("category"),
                llmFields = llmFields,
                ocr = ocr,
                dateTakenMs = item.dateTaken,
            )
            val tags = json?.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it).lowercase().trim() }.filter { it.isNotEmpty() }
            } ?: emptyList()
            val description = json?.optString("description")?.takeIf { it.isNotBlank() }
                ?: ocr.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
                ?: category.replace('_', ' ')

            return IndexedImage(
                mediaId = item.id,
                uri = item.uri.toString(),
                name = item.name,
                folder = item.folder,
                dateTaken = item.dateTaken,
                dateModified = item.dateModified,
                lat = lat,
                lng = lng,
                category = category,
                description = description,
                tags = tags,
                fields = fields,
                ocrText = ocr,
                status = if (json != null) "done" else "ocr_only",
                error = llmError,
                indexMs = SystemClock.elapsedRealtime() - t0,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Indexing failed for ${item.name}", t)
            return IndexedImage(
                mediaId = item.id, uri = item.uri.toString(), name = item.name, folder = item.folder,
                dateTaken = item.dateTaken, dateModified = item.dateModified, lat = lat, lng = lng,
                category = FieldExtractor.guessCategory(ocr), description = "", tags = emptyList(),
                fields = emptyMap(), ocrText = ocr, status = "failed",
                error = t.message ?: t.javaClass.simpleName,
                indexMs = SystemClock.elapsedRealtime() - t0,
            )
        }
    }

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, maxSide.toFloat() / max(w, h))
            decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    }

    private fun toJpeg(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun readLatLng(ctx: Context, uri: Uri): Pair<Double, Double>? = try {
        val original =
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
                MediaStore.setRequireOriginal(uri) else uri
        ctx.contentResolver.openInputStream(original)?.use { stream ->
            ExifInterface(stream).latLong?.let { it[0] to it[1] }
        }
    } catch (_: Throwable) {
        null
    }
}
