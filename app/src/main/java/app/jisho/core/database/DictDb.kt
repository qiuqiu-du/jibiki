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

    private val zdict: ZstdDictDecompress by lazy {
        ZstdDictDecompress(db.query("SELECT data FROM zdict WHERE id = 1") { it.blob(0) }.first())
    }

    fun linkedIds(coreId: Long): List<Long> =
        db.query("SELECT entry_id FROM dict_link WHERE core_id = $coreId ORDER BY entry_id") { it.long(0) }

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

    private fun toEntry(id: Long, blob: ByteArray): DictEntry {
        val size = Zstd.getFrameContentSize(blob)
        val text = String(Zstd.decompress(blob, zdict, size.toInt()), Charsets.UTF_8)
        val nl = text.indexOf('\n')
        return if (nl < 0) DictEntry(dictId, id, text, "")
        else DictEntry(dictId, id, text.substring(0, nl), text.substring(nl + 1))
    }

    override fun close() = db.close()
}
