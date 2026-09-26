package ai.kairo.gallery.index

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val folder: String,
    val dateTaken: Long,     // epoch millis
    val dateModified: Long,  // epoch seconds
)

/**
 * Lists images (never videos) from the watched folders via MediaStore.
 * Demo: only Pictures/Kairo/. Optionally also new screenshots.
 * For the full gallery later: drop the folder filter.
 */
object GalleryScanner {
    const val DEMO_FOLDER = "Pictures/Kairo/"

    fun scan(ctx: Context, screenshotsSinceSec: Long): List<MediaItem> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        val path = MediaStore.Images.Media.RELATIVE_PATH
        var selection = "$path LIKE ?"
        val args = mutableListOf("$DEMO_FOLDER%")
        if (screenshotsSinceSec > 0) {
            selection = "($selection) OR ($path LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?)"
            args += "%Screenshots/%"
            args += screenshotsSinceSec.toString()
        }

        val out = ArrayList<MediaItem>()
        ctx.contentResolver.query(
            collection, projection, selection, args.toTypedArray(),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val modified = c.getLong(modCol)
                val taken = c.getLong(takenCol).takeIf { it > 0 } ?: (modified * 1000)
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "image_$id",
                    folder = c.getString(pathCol) ?: "",
                    dateTaken = taken,
                    dateModified = modified,
                )
            }
        }
        return out
    }
}
