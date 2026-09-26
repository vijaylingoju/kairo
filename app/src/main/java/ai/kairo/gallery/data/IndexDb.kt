package ai.kairo.gallery.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One indexed gallery image. */
data class IndexedImage(
    val mediaId: Long,
    val uri: String,
    val name: String,
    val folder: String,
    val dateTaken: Long,          // epoch millis
    val dateModified: Long,       // epoch seconds (MediaStore value, used for change detection)
    val lat: Double?,
    val lng: Double?,
    val category: String,
    val description: String,
    val tags: List<String>,
    val fields: Map<String, String?>,
    val ocrText: String,
    val status: String,           // done | ocr_only | failed
    val error: String?,
    val indexMs: Long,
)

/**
 * Plain SQLite + FTS4 (no annotation processing, fewer build issues).
 * `images` holds the record, `images_fts` holds searchable text with docid = media_id,
 * `embeddings` holds the CLIP vector per image (own table, so a Gemma re-index never drops it).
 */
class IndexDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "kairo_index.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        createImageTables(db)
        createEmbeddingTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createEmbeddingTable(db)
    }

    private fun createEmbeddingTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS embeddings (media_id INTEGER PRIMARY KEY, vec BLOB NOT NULL)")
    }

    private fun createImageTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE images (
                media_id INTEGER PRIMARY KEY,
                uri TEXT NOT NULL,
                name TEXT,
                folder TEXT,
                date_taken INTEGER,
                date_modified INTEGER,
                lat REAL,
                lng REAL,
                category TEXT,
                description TEXT,
                tags TEXT,
                fields_json TEXT,
                ocr_text TEXT,
                status TEXT,
                error TEXT,
                index_ms INTEGER,
                indexed_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_images_category ON images(category)")
        db.execSQL("CREATE VIRTUAL TABLE images_fts USING fts4(description, tags, ocr_text, fields_text)")
    }

    /** media_id -> (date_modified, status) for change detection. */
    fun known(): Map<Long, Pair<Long, String>> {
        val out = HashMap<Long, Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT media_id, date_modified, status FROM images", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1) to (c.getString(2) ?: "")
        }
        return out
    }

    fun upsert(img: IndexedImage) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply {
                put("media_id", img.mediaId)
                put("uri", img.uri)
                put("name", img.name)
                put("folder", img.folder)
                put("date_taken", img.dateTaken)
                put("date_modified", img.dateModified)
                if (img.lat != null) put("lat", img.lat) else putNull("lat")
                if (img.lng != null) put("lng", img.lng) else putNull("lng")
                put("category", img.category)
                put("description", img.description)
                put("tags", JSONArray(img.tags).toString())
                put("fields_json", fieldsToJson(img.fields).toString())
                put("ocr_text", img.ocrText)
                put("status", img.status)
                put("error", img.error)
                put("index_ms", img.indexMs)
                put("indexed_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("images", null, v, SQLiteDatabase.CONFLICT_REPLACE)

            db.execSQL("DELETE FROM images_fts WHERE docid = ?", arrayOf<Any>(img.mediaId))
            val fieldsText = img.fields.values.filterNotNull().joinToString(" ")
            db.execSQL(
                "INSERT INTO images_fts(docid, description, tags, ocr_text, fields_text) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    img.mediaId,
                    img.category.replace('_', ' ') + " " + img.description,
                    img.tags.joinToString(" "),
                    img.ocrText,
                    fieldsText
                )
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Remove records for images that left the watched folders. */
    fun deleteMissing(keep: Set<Long>) {
        val gone = known().keys - keep
        if (gone.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in gone) {
                db.delete("images", "media_id = ?", arrayOf(id.toString()))
                db.delete("embeddings", "media_id = ?", arrayOf(id.toString()))
                db.execSQL("DELETE FROM images_fts WHERE docid = ?", arrayOf<Any>(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun all(): List<IndexedImage> =
        readableDatabase.rawQuery("SELECT * FROM images ORDER BY date_taken DESC", null).use { c ->
            val list = ArrayList<IndexedImage>()
            while (c.moveToNext()) list += c.toImage()
            list
        }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM images", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun setEmbedding(mediaId: Long, vec: FloatArray) {
        val buf = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(vec)
        val v = ContentValues().apply {
            put("media_id", mediaId)
            put("vec", buf.array())
        }
        writableDatabase.insertWithOnConflict("embeddings", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun embeddingIds(): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT media_id FROM embeddings", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    /** All CLIP vectors, media_id -> 512 floats. ~2 KB per image. */
    fun embeddings(): Map<Long, FloatArray> {
        val out = HashMap<Long, FloatArray>()
        readableDatabase.rawQuery("SELECT media_id, vec FROM embeddings", null).use { c ->
            while (c.moveToNext()) {
                val fb = ByteBuffer.wrap(c.getBlob(1)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                out[c.getLong(0)] = FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        return out
    }

    /** Full-text match; `term` must already be sanitized (letters/digits only). Prefix search. */
    fun ftsIds(term: String): Set<Long> {
        if (term.isBlank()) return emptySet()
        val out = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT docid FROM images_fts WHERE images_fts MATCH ?",
            arrayOf("$term*")
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    private fun Cursor.toImage(): IndexedImage {
        fun s(col: String) = getString(getColumnIndexOrThrow(col))
        fun l(col: String) = getLong(getColumnIndexOrThrow(col))
        fun d(col: String): Double? {
            val i = getColumnIndexOrThrow(col)
            return if (isNull(i)) null else getDouble(i)
        }
        val tagsArr = runCatching { JSONArray(s("tags") ?: "[]") }.getOrDefault(JSONArray())
        val fieldsObj = runCatching { JSONObject(s("fields_json") ?: "{}") }.getOrDefault(JSONObject())
        return IndexedImage(
            mediaId = l("media_id"),
            uri = s("uri"),
            name = s("name") ?: "",
            folder = s("folder") ?: "",
            dateTaken = l("date_taken"),
            dateModified = l("date_modified"),
            lat = d("lat"),
            lng = d("lng"),
            category = s("category") ?: "other",
            description = s("description") ?: "",
            tags = (0 until tagsArr.length()).map { tagsArr.optString(it) },
            fields = jsonToFields(fieldsObj),
            ocrText = s("ocr_text") ?: "",
            status = s("status") ?: "",
            error = s("error"),
            indexMs = l("index_ms"),
        )
    }

    companion object {
        @Volatile private var instance: IndexDb? = null

        fun get(ctx: Context): IndexDb =
            instance ?: synchronized(this) {
                instance ?: IndexDb(ctx.applicationContext).also { instance = it }
            }

        fun fieldsToJson(fields: Map<String, String?>): JSONObject {
            val o = JSONObject()
            for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
            return o
        }

        fun jsonToFields(o: JSONObject): Map<String, String?> {
            val out = LinkedHashMap<String, String?>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = if (o.isNull(k)) null else o.optString(k)
            }
            return out
        }
    }
}
