package ai.kairo.gallery.index

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ML Kit on-device OCR. Exact text — this is the source of truth for numbers and IDs. */
object Ocr {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * @param skipTopFraction drop text blocks that start inside this top strip of the image.
     * Used for screenshots so the status bar ("13:04", "5G", "42%") never becomes a field.
     */
    suspend fun read(bitmap: Bitmap, skipTopFraction: Float = 0f): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val cut = bitmap.height * skipTopFraction
                val text = if (cut <= 0f) result.text else result.textBlocks
                    .filter { b -> (b.boundingBox?.top ?: Int.MAX_VALUE) >= cut }
                    .joinToString("\n") { it.text }
                cont.resume(text)
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
