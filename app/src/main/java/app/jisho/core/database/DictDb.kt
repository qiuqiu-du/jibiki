package app.jisho.core.database

import app.jisho.core.japanese.SearchKey
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictDecompress

/** Read-only access to one dictionary pack (schema: tools/lib/packdb.py). */
class DictDb(private val db: SqlDb, fallbackId: String) : AutoCloseable {

    val dictId: String = db.meta("dict_id") ?: fallbackId
    val title: String = db.meta("title") ?: dictId
    val schemaVersion: Int = db.meta("schema_version")?.toIntOrNull() ?: 0
    val bodyFormat: Int = db.meta("body_format")?.toIntOrNull() ?: 0
    /** A Japanese-Chinese dictionary (meta lang "ja-zh"): its senses mark their Chinese glosses. */
    val chinese: Boolean = db.meta("lang") == "ja-zh"

    private val zdict: ZstdDictDecompress by lazy {
        ZstdDictDecompress(db.query("SELECT data FROM zdict WHERE id = 1") { it.blob(0) }.first())
    }

    fun linkedIds(coreId: Long): List<Long> =
        db.query("SELECT entry_id FROM dict_link WHERE core_id = $coreId ORDER BY entry_id") { it.long(0) }

    /**
     * [linkedIds] for many words in one query (a search list has up to 60 rows; one query per
     * row and dictionary was the main cost of one-line meanings). Words without links are absent.
     */
    fun linkedIds(coreIds: Collection<Long>): Map<Long, List<Long>> =
        coreIds.distinct().chunked(IN_CHUNK).flatMap { chunk ->
            db.query("SELECT core_id, entry_id FROM dict_link WHERE core_id IN (${chunk.joinToString(",")}) ORDER BY core_id, entry_id") {
                it.long(0) to it.long(1)
            }
        }.groupBy({ it.first }, { it.second })

    /** Which of [coreIds] this dictionary defines, in one query. */
    fun defines(coreIds: Collection<Long>): Set<Long> =
        coreIds.distinct().chunked(IN_CHUNK).flatMapTo(HashSet()) { chunk ->
            db.query("SELECT DISTINCT core_id FROM dict_link WHERE core_id IN (${chunk.joinToString(",")})") { it.long(0) }
        }

    fun entriesForCore(coreId: Long): List<DictEntry> = linkedIds(coreId).mapNotNull { entry(it) }

    fun entry(id: Long): DictEntry? =
        db.query("SELECT body FROM dict_entry WHERE id = $id") { it.blob(0) }.firstOrNull()?.let { toEntry(id, it) }

    /** Every entry id, in order (for whole-dictionary checks in tests). */
    fun allIds(): List<Long> = db.query("SELECT id FROM dict_entry ORDER BY id") { it.long(0) }

    /** Keys that only this dictionary knows (core.db cannot reach them). */
    fun searchOwnKeys(key: String, limit: Int = 20): List<DictCandidate> {
        if (key.isEmpty()) return emptyList()
        return db.query(
            """
            SELECT entry_id, MAX(key = ?1) AS exact FROM dict_key
            WHERE key >= ?1 AND key < ?2 GROUP BY entry_id
            ORDER BY exact DESC, MIN(LENGTH(key)), entry_id LIMIT ${limit.coerceIn(1, 200)}
            """.trimIndent(),
            listOf(key, SearchKey.prefixUpperBound(key)),
        ) { it.long(0) }.mapNotNull { id -> entry(id)?.let { DictCandidate(dictId, id, it.title) } }
    }

    /** A gaiji picture (schema 2 packs). */
    fun media(name: String): ByteArray? =
        db.query("SELECT data FROM media WHERE name = ?1", listOf(name)) { it.blob(0) }.firstOrNull()

    /**
     * Just the body markup of an entry, without reading its gaiji pictures: for one-line
     * meanings, which are plain text (each picture is one more query).
     */
    fun body(id: Long): String? =
        db.query("SELECT body FROM dict_entry WHERE id = $id") { it.blob(0) }.firstOrNull()
            ?.let { decompress(it) }?.let { text -> text.indexOf('\n').let { if (it < 0) "" else text.substring(it + 1) } }

    private fun decompress(blob: ByteArray): String {
        val size = Zstd.getFrameContentSize(blob)
        return String(Zstd.decompress(blob, zdict, size.toInt()), Charsets.UTF_8)
    }

    private fun toEntry(id: Long, blob: ByteArray): DictEntry {
        val text = decompress(blob)
        val nl = text.indexOf('\n')
        if (nl < 0) return DictEntry(dictId, id, text, "")
        val body = text.substring(nl + 1)
        val images = if ("<img" !in body) emptyMap() else
            IMG.findAll(body).map { it.groupValues[1] }.distinct().mapNotNull { n -> media(n)?.let { n to it } }.toMap()
        return DictEntry(dictId, id, text.substring(0, nl), body, images)
    }

    private companion object {
        val IMG = Regex("<img src=\"([^\"]+)\">")
        /** Ids per IN list; they are written into the SQL (not bound), so only SQL length limits this. */
        const val IN_CHUNK = 500
    }

    override fun close() = db.close()
}
