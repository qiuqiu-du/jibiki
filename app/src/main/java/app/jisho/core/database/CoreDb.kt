package app.jisho.core.database

import app.jisho.core.japanese.SearchKey

/** Read-only access to core.db (schema: tools/build_core.py). */
class CoreDb(
    private val db: SqlDb,
    /** entry.flags bit that marks "usually written in kana": 1 = main sense (default), 2 = any sense. */
    private val usuallyKanaMask: Int = 1,
) : AutoCloseable {

    val schemaVersion: Int = db.meta("schema_version")?.toIntOrNull() ?: 0

    /**
     * Prefix search on normalized keys, in two cheap steps:
     *  1. exact matches (few rows): on kana input, common "usually kana" words whose main
     *     reading is the typed kana come first (する -> 為る), then by precomputed rank;
     *  2. prefix matches: a single-table range scan ordered by the precomputed
     *     search_key.rank (entry score + spelling frequency), deduplicated per entry.
     * Ranking quality is measured by RankingTest, speed by DevicePerformanceTest.
     */
    fun search(key: String, kanaInput: Boolean, limit: Int = 60): List<Candidate> {
        if (key.isEmpty()) return emptyList()
        val max = limit.coerceIn(1, 500)
        val boost = if (kanaInput) "(e.flags & $usuallyKanaMask) != 0 AND f0.reading = ?1 AND e.score < 60" else "0"
        val exact = db.query(
            """
            SELECT s.entry_id FROM search_key s
            JOIN entry e ON e.id = s.entry_id
            JOIN form f0 ON f0.entry_id = s.entry_id AND f0.ord = 0
            WHERE s.key = ?1
            ORDER BY CASE WHEN $boost THEN 0 ELSE 1 END, s.rank
            LIMIT $max
            """.trimIndent(),
            listOf(key),
        ) { it.long(0) }
        val ids = LinkedHashSet(exact)
        if (ids.size < max) {
            // An entry can have several keys in the range; over-fetch, keep each entry's best key.
            val prefix = db.query(
                "SELECT entry_id FROM search_key WHERE key > ?1 AND key < ?2 ORDER BY rank, LENGTH(key) LIMIT ${max * 4}",
                listOf(key, SearchKey.prefixUpperBound(key)),
            ) { it.long(0) }
            for (id in prefix) {
                if (ids.size >= max) break
                ids += id
            }
        }
        if (ids.isEmpty()) return emptyList()
        val idList = ids.toList()
        val forms = formsFor(idList)
        val accents = accentsFor(idList)
        val usuallyKana = db.query("SELECT id, flags FROM entry WHERE id IN (${idList.joinToString(",")})") {
            it.long(0) to (it.int(1) and usuallyKanaMask != 0)
        }.toMap()
        val exactSet = exact.toSet()
        return idList.map { id ->
            display(id, forms[id].orEmpty(), key, usuallyKana[id] == true, id in exactSet, accents[id].orEmpty())
        }
    }

    /**
     * How one candidate is shown. Rule: the row must identify the word, so a kana
     * headword (for words usually written in kana) always carries its kanji spellings.
     */
    private fun display(
        id: Long, fs: List<Form>, key: String, usuallyKana: Boolean, exact: Boolean, accents: Map<String, String>,
    ): Candidate {
        // Show the spelling the user typed: forms are in JMdict order (main spelling first),
        // so take the first exact match, then the first prefix match (食べ -> 食べ物, not 食べもの).
        fun kanjiKey(f: Form) = f.kanji?.let { SearchKey.of(it) }
        val kanjiMatch = fs.firstOrNull { kanjiKey(it) == key } ?: fs.firstOrNull { kanjiKey(it)?.startsWith(key) == true }
        val shown = kanjiMatch
            ?: fs.firstOrNull { SearchKey.of(it.reading) == key }
            ?: fs.firstOrNull { SearchKey.of(it.reading).startsWith(key) }
            ?: fs.firstOrNull()
        val reading = shown?.reading.orEmpty()
        val kanjiForReading = fs.filter { it.reading == reading }.mapNotNull { it.kanji }.distinct()
        // とても, not 迚も; but only for the main reading (はし must still show 嘴, not はし).
        val kanaHead = kanjiMatch == null && usuallyKana && reading == fs.firstOrNull()?.reading
        val head = when {
            kanjiMatch != null -> kanjiMatch.kanji!!
            kanaHead -> reading
            else -> kanjiForReading.firstOrNull() ?: reading
        }
        return Candidate(
            entryId = id,
            headword = head,
            reading = reading,
            accent = accents[reading],
            exact = exact,
            kanjiNote = if (kanaHead) kanjiForReading.take(3).joinToString("・") else "",
        )
    }

    fun entry(id: Long): CoreEntry? {
        val row = db.query("SELECT pos, freq, flags FROM entry WHERE id = $id") {
            Triple(it.string(0), it.int(1), it.int(2))
        }.firstOrNull() ?: return null
        return CoreEntry(
            id = id,
            forms = formsFor(listOf(id))[id].orEmpty(),
            pos = row.first.split(',').filter { it.isNotEmpty() },
            freq = row.second,
            usuallyKana = row.third and usuallyKanaMask != 0,
            accents = accentsFor(listOf(id))[id].orEmpty(),
        )
    }

    private fun formsFor(ids: List<Long>): Map<Long, List<Form>> =
        db.query("SELECT entry_id, kanji, reading FROM form WHERE entry_id IN (${ids.joinToString(",")}) ORDER BY entry_id, ord") {
            it.long(0) to Form(it.stringOrNull(1), it.string(2))
        }.groupBy({ it.first }, { it.second })

    private fun accentsFor(ids: List<Long>): Map<Long, Map<String, String>> =
        db.query("SELECT entry_id, reading, accent FROM pitch WHERE entry_id IN (${ids.joinToString(",")})") {
            Triple(it.long(0), it.string(1), it.string(2))
        }.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, v) -> v.toMap() }

    override fun close() = db.close()
}
