package app.jisho.core.data

import app.jisho.core.database.Candidate
import app.jisho.core.database.CoreDb
import app.jisho.core.database.CoreEntry
import app.jisho.core.database.DictCandidate
import app.jisho.core.database.DictDb
import app.jisho.core.database.DictEntry
import app.jisho.core.japanese.Romaji
import app.jisho.core.japanese.SearchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchResult(val query: String, val words: List<Candidate>, val dictOnly: List<DictCandidate>)

data class EntryDetail(val core: CoreEntry, val definitions: List<Pair<DictInfo, List<DictEntry>>>)

data class DictInfo(val id: String, val title: String)

/** Opens data packs; Android reads them from the app's files dir, tests from datapacks/. */
interface PackOpener {
    fun openCore(): CoreDb
    fun openDictionaries(): List<DictDb>
}

private const val HINT_LENGTH = 18

/** Body markup -> plain text without ruby readings (for hints). */
internal fun plainText(markup: String): String =
    markup.replace(Regex("<rt>.*?</rt>"), "").replace(Regex("<br>|</p>"), " ").replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()

/** Single entry point for dictionary data; opens the packs lazily. */
class DictionaryRepository(private val packs: PackOpener) {
    @Volatile private var core: CoreDb? = null
    @Volatile private var dicts: List<DictDb> = emptyList()

    private fun ensureOpen() {
        if (core != null) return
        synchronized(this) {
            if (core != null) return
            dicts = packs.openDictionaries()
            core = packs.openCore()
        }
    }

    fun reopen() = synchronized(this) {
        core?.close(); dicts.forEach { it.close() }
        core = null; dicts = emptyList()
    }

    suspend fun search(raw: String): SearchResult = withContext(Dispatchers.IO) {
        ensureOpen()
        val trimmed = raw.trim()
        val text = if (Romaji.isRomaji(trimmed)) Romaji.toHiragana(trimmed) else trimmed
        val key = SearchKey.of(text)
        val kanaInput = key.isNotEmpty() && key.all { it in 'ぁ'..'ゟ' || it == 'ー' }
        val words = distinguish(core!!.search(key, kanaInput))
        val dictOnly = if (words.size < 20) dicts.flatMap { it.searchOwnKeys(key, limit = 10) } else emptyList()
        SearchResult(raw, words, dictOnly)
    }

    /**
     * Display rule: rows in one result list must be distinguishable. When JMdict has
     * several entries that look the same (腰/こし twice), rows that lead to exactly the
     * same dictionary definitions are merged; the others get a short definition hint.
     */
    private fun distinguish(words: List<Candidate>): List<Candidate> {
        val groups = words.groupBy { it.displayKey }.filterValues { it.size > 1 }
        if (groups.isEmpty()) return words
        val dropped = HashSet<Long>()
        val hints = HashMap<Long, String>()
        for (group in groups.values) {
            val links = group.associate { c -> c.entryId to dicts.map { it.linkedIds(c.entryId) } }
            val kept = group.distinctBy { links[it.entryId] }
            group.filter { it !in kept }.forEach { dropped += it.entryId }
            if (kept.size > 1) kept.forEach { c ->
                // Prefer a definition only this row links to, so overlapping rows get different hints.
                val others = kept.filter { it != c }.map { links[it.entryId]!! }
                val own = links[c.entryId]!!.mapIndexed { d, ids -> ids.filter { id -> others.none { id in it[d] } } }
                hints[c.entryId] = hintFor(own).ifEmpty { hintFor(links[c.entryId]!!) }
            }
        }
        return words.filter { it.entryId !in dropped }
            .map { c -> hints[c.entryId]?.let { c.copy(hint = it) } ?: c }
    }

    /** First words of the first linked definition (plain text), or "" if none is linked. */
    private fun hintFor(linksPerDict: List<List<Long>>): String {
        dicts.zip(linksPerDict).forEach { (d, ids) ->
            val body = ids.firstOrNull()?.let { d.entry(it)?.body } ?: return@forEach
            val text = plainText(body).replace(Regex("^[［〔《(（【][^］〕》)）】]*[］〕》)）】]"), "").trim()
            if (text.isNotEmpty()) return text.take(HINT_LENGTH)
        }
        return ""
    }

    suspend fun entry(coreId: Long): EntryDetail? = withContext(Dispatchers.IO) {
        ensureOpen()
        val entry = core!!.entry(coreId) ?: return@withContext null
        EntryDetail(entry, dicts.map { DictInfo(it.dictId, it.title) to it.entriesForCore(coreId) })
    }

    suspend fun dictEntry(dictId: String, id: Long): Pair<DictInfo, DictEntry>? = withContext(Dispatchers.IO) {
        ensureOpen()
        val d = dicts.firstOrNull { it.dictId == dictId } ?: return@withContext null
        d.entry(id)?.let { DictInfo(d.dictId, d.title) to it }
    }
}
