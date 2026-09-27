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
    // Things in the photo, each with its OWN attributes: "red road bicycle", "blue bicycle", "red handlebar
    // wrap". Search matches a colour to its object inside one phrase - see search/ObjectMatch.
    val objects: List<String> = emptyList(),
)

/**
 * Plain SQLite + FTS4 (no annotation processing, fewer build issues).
 * `images` holds the record, `images_fts` holds searchable text with docid = media_id,
 * `embeddings` holds the CLIP vector per image (own table, so a Gemma re-index never drops it).
 */
class IndexDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "kairo_index.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        createImageTables(db)
        createEmbeddingTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createEmbeddingTable(db)
        // NULL = indexed before object phrases existed; known() reports those as stale, so the next
        // "Index now" re-reads them with Gemma. Nothing is wiped and CLIP vectors are kept.
        if (oldVersion < 3) db.execSQL("ALTER TABLE images ADD COLUMN objects TEXT")
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
                indexed_at INTEGER,
                objects TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_images_category ON images(category)")
        db.execSQL("CREATE VIRTUAL TABLE images_fts USING fts4(description, tags, ocr_text, fields_text)")
    }

    /**
     * media_id -> (date_modified, status) for change detection. A photo finished before object phrases
     * existed reads as "stale" rather than "done", so it gets indexed again once.
     */
    fun known(): Map<Long, Pair<Long, String>> {
        val out = HashMap<Long, Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT media_id, date_modified, CASE WHEN status = 'done' AND objects IS NULL THEN 'stale' ELSE status END FROM images",
            null
        ).use { c ->
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
                put("objects", JSONArray(img.objects).toString())
            }
            db.insertWithOnConflict("images", null, v, SQLiteDatabase.CONFLICT_REPLACE)

            db.execSQL("DELETE FROM images_fts WHERE docid = ?", arrayOf<Any>(img.mediaId))
            val fieldsText = img.fields.values.filterNotNull().joinToString(" ")
            db.execSQL(
                "INSERT INTO images_fts(docid, description, tags, ocr_text, fields_text) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    img.mediaId,
                    img.category.replace('_', ' ') + " " + img.description,
                    (img.tags + img.objects).joinToString(" "),
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

    /** Wipes every indexed record, text index and CLIP vector. Photos themselves are untouched. */
    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM images")
            db.execSQL("DELETE FROM images_fts")
            db.execSQL("DELETE FROM embeddings")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    /**
     * Full-text match; `term` must already be sanitized (letters/digits only).
     * Prefix search only for words of 5+ letters ("snack" -> "snacks"): short prefixes match junk
     * ("car*" hit "card" on every ID card, found in the 2026-09-26 evaluation).
     */
    fun ftsIds(term: String): Set<Long> {
        if (term.isBlank()) return emptySet()
        val out = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT docid FROM images_fts WHERE images_fts MATCH ?",
            arrayOf(if (term.length >= 5) "$term*" else term)
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
        val objectsArr = runCatching { JSONArray(s("objects") ?: "[]") }.getOrDefault(JSONArray())
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
            objects = (0 until objectsArr.length()).map { objectsArr.optString(it) },
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
