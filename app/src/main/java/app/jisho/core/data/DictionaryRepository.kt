package app.jisho.core.data

import app.jisho.core.database.Candidate
import app.jisho.core.database.CoreDb
import app.jisho.core.database.CoreEntry
import app.jisho.core.database.DictCandidate
import app.jisho.core.database.DictDb
import app.jisho.core.database.DictEntry
import app.jisho.core.database.Inflection
import app.jisho.core.lemmatizer.LemmaValidator
import app.jisho.core.lemmatizer.Lemmatizer
import app.jisho.core.lemmatizer.Segment
import app.jisho.core.lemmatizer.Segmenter
import app.jisho.core.japanese.Romaji
import app.jisho.core.japanese.SearchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchResult(
    val query: String,
    /** Exact matches, then dictionary forms of an inflected input, then prefix matches. */
    val words: List<Candidate>,
    val dictOnly: List<DictCandidate>,
    /** Words of a sentence, each tappable on its own (Kuromoji mode, no exact match). */
    val segments: List<Segment> = emptyList(),
)

data class EntryDetail(
    val core: CoreEntry,
    val definitions: List<Pair<DictInfo, List<DictEntry>>>,
    /** Only for words no dictionary defines: dictionary forms it may be an inflection of. */
    val baseForms: List<Candidate> = emptyList(),
)

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

/** Inputs shorter than this aren't lemmatized: one kana is a prefix, not an inflected form. */
private const val MIN_LEMMA_INPUT = 2

/**
 * Single entry point for dictionary data; opens the packs lazily. [lemmatizer] and
 * [segmenter] are read on every search, so a settings change applies immediately.
 */
class DictionaryRepository(
    private val packs: PackOpener,
    private val lemmatizer: () -> Lemmatizer? = { null },
    private val segmenter: () -> Segmenter? = { null },
) {
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
        val found = core!!.search(key, kanaInput)
        val (exact, prefix) = found.partition { it.exact }
        val lemmas = lemmaRows(key).filter { l -> exact.none { it.entryId == l.entryId } }.filter { hasDefinition(it.entryId) }
        // JMdict lists some inflected expressions on their own (信じられない) that no dictionary
        // defines; when the input also has a defined dictionary form, that form goes first.
        val (defined, undefined) = if (lemmas.isEmpty()) exact to emptyList() else exact.partition { hasDefinition(it.entryId) }
        val words = distinguish(defined + lemmas + undefined + prefix.filter { p -> lemmas.none { it.entryId == p.entryId } })
        val dictOnly = if (words.size < 20) dicts.flatMap { it.searchOwnKeys(key, limit = 10) } else emptyList()
        // Word by word whenever the input is not one word or one inflected word: a sentence, a
        // word with a particle (食べますよ), or a word with an auxiliary verb (食べてくる).
        val segments = if (exact.isEmpty() && lemmas.isEmpty()) {
            segmenter()?.segment(text).orEmpty().takeIf { it.size >= 2 || it.singleOrNull()?.surface != text }.orEmpty()
        } else emptyList()
        SearchResult(raw, words, dictOnly, segments)
    }

    private fun hasDefinition(coreId: Long) = dicts.any { it.linkedIds(coreId).isNotEmpty() }

    /** Dictionary forms of an inflected input (食べなかった -> 食べる), checked against JMdict. */
    private fun lemmaRows(key: String): List<Candidate> {
        if (key.length < MIN_LEMMA_INPUT) return emptyList()
        val l = lemmatizer() ?: return emptyList()
        val validated = LemmaValidator(core!!).validate(l.lemmatize(key)).associateBy { it.entryId }
        return core!!.candidates(validated.keys.toList()) { validated.getValue(it).key }.map { c ->
            c.copy(exact = false, inflection = Inflection(key, validated.getValue(c.entryId).candidate.reasons))
        }
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
        val definitions = dicts.map { DictInfo(it.dictId, it.title) to it.entriesForCore(coreId) }
        // An undefined word may be an inflected form (信じられない -> 信じる): point to it.
        val baseForms = if (definitions.any { it.second.isNotEmpty() }) emptyList() else
            entry.forms.map { SearchKey.of(it.kanji ?: it.reading) }.distinct()
                .flatMap { lemmaRows(it) }.distinctBy { it.entryId }
                .filter { it.entryId != coreId && hasDefinition(it.entryId) }
        EntryDetail(entry, definitions, baseForms)
    }

    suspend fun dictEntry(dictId: String, id: Long): Pair<DictInfo, DictEntry>? = withContext(Dispatchers.IO) {
        ensureOpen()
        val d = dicts.firstOrNull { it.dictId == dictId } ?: return@withContext null
        d.entry(id)?.let { DictInfo(d.dictId, d.title) to it }
    }
}
