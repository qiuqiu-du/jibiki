package app.jisho.core.data

import app.jisho.core.database.Candidate
import app.jisho.core.designsystem.badgeFallback
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

/**
 * The user's dictionary settings: display order (ids; unknown ids go last in default order)
 * and which dictionaries are turned off. Off means hidden everywhere: entries, search hints,
 * dictionary-only results, and "has a definition" checks.
 */
data class DictPrefs(val order: List<String> = emptyList(), val disabled: Set<String> = emptySet())

/** One imported dictionary as the settings page lists it. */
data class DictStatus(val info: DictInfo, val enabled: Boolean)

/** What the settings page needs from [DictionaryRepository]. */
interface DictionaryList {
    /** All imported dictionaries in [prefs]' order, with their on/off state. */
    suspend fun dictionaries(prefs: DictPrefs): List<DictStatus>
}

/** Opens data packs; Android reads them from the app's files dir, tests from datapacks/. */
interface PackOpener {
    fun openCore(): CoreDb
    fun openDictionaries(): List<DictDb>
}

private const val HINT_LENGTH = 18

/** Body markup -> plain text without ruby readings (for hints); number badges become ［一］〔一〕(1). */
internal fun plainText(markup: String): String =
    markup.replace(Regex("<rt>.*?</rt>"), "")
        .replace(Regex("<span class=\"(box-b|box-w|num-c)\">(.*?)</span>")) { badgeFallback(it.groupValues[1], it.groupValues[2]) }.replace(Regex("<br>|</p>"), " ").replace(Regex("<[^>]+>"), "")
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
    private val prefs: () -> DictPrefs = { DictPrefs() },
) : DictionaryList {
    @Volatile private var core: CoreDb? = null
    /** Every imported dictionary, in the default order; [dicts] is what the user turned on, in their order. */
    @Volatile private var all: List<DictDb> = emptyList()
    private val dicts: List<DictDb> get() = arrange(all, prefs()).filter { it.dictId !in prefs().disabled }

    private fun ensureOpen() {
        if (core != null) return
        synchronized(this) {
            if (core != null) return
            all = packs.openDictionaries()
            core = packs.openCore()
        }
    }

    fun reopen() = synchronized(this) {
        core?.close(); all.forEach { it.close() }
        core = null; all = emptyList()
    }

    override suspend fun dictionaries(prefs: DictPrefs): List<DictStatus> = withContext(Dispatchers.IO) {
        ensureOpen()
        val p = prefs
        arrange(all, p).map { DictStatus(DictInfo(it.dictId, it.title), it.dictId !in p.disabled) }
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
        val ds = dicts // one snapshot: the settings may change while this runs
        val dropped = HashSet<Long>()
        val hints = HashMap<Long, String>()
        for (group in groups.values) {
            val links = group.associate { c -> c.entryId to ds.map { it.linkedIds(c.entryId) } }
            val kept = group.distinctBy { links[it.entryId] }
            group.filter { it !in kept }.forEach { dropped += it.entryId }
            if (kept.size > 1) kept.forEach { c ->
                // Prefer a definition only this row links to, so overlapping rows get different hints.
                val others = kept.filter { it != c }.map { links[it.entryId]!! }
                val own = links[c.entryId]!!.mapIndexed { d, ids -> ids.filter { id -> others.none { id in it[d] } } }
                hints[c.entryId] = hintFor(ds, own).ifEmpty { hintFor(ds, links[c.entryId]!!) }
            }
        }
        return words.filter { it.entryId !in dropped }
            .map { c -> hints[c.entryId]?.let { c.copy(hint = it) } ?: c }
    }

    /** First words of the first linked definition (plain text), or "" if none is linked. */
    private fun hintFor(ds: List<DictDb>, linksPerDict: List<List<Long>>): String {
        ds.zip(linksPerDict).forEach { (d, ids) ->
            val body = ids.firstOrNull()?.let { d.entry(it)?.body } ?: return@forEach
            val text = dropLeadingLabel(plainText(body)).trim()
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

    /**
     * What a saved word shows in lists and exports: its pitch accent (raw, such as "0,2") and the
     * first sentence of its first definition. [target]: see [app.jisho.core.userdata.WordRef].
     */
    suspend fun summary(target: String, reading: String): WordSummary = withContext(Dispatchers.IO) {
        ensureOpen()
        val parts = target.split(':')
        when (parts[0]) {
            "core" -> parts.getOrNull(1)?.toLongOrNull()?.let { id ->
                val entry = core!!.entry(id) ?: return@let null
                val body = dicts.firstNotNullOfOrNull { d -> d.entriesForCore(id).firstOrNull()?.body }
                WordSummary(entry.accents[reading] ?: entry.accents[entry.reading], body?.let { firstSentence(it) }.orEmpty())
            }
            "dict" -> parts.getOrNull(2)?.toLongOrNull()?.let { id ->
                val d = all.firstOrNull { it.dictId == parts[1] } ?: return@let null
                WordSummary(null, d.entry(id)?.body?.let { firstSentence(it) }.orEmpty())
            }
            else -> null
        } ?: WordSummary(null, "")
    }

    /**
     * Where an imported word without a usable target leads: the JMdict entry with this spelling
     * (and reading, if given), else a dictionary-only item, else a plain look-up.
     */
    suspend fun resolve(headword: String, reading: String): String {
        val r = search(headword)
        val exact = r.words.filter { it.exact && it.inflection == null }
        val word = exact.firstOrNull { reading.isEmpty() || it.reading == reading } ?: exact.firstOrNull()
        return when {
            word != null -> "core:${word.entryId}"
            r.dictOnly.isNotEmpty() -> r.dictOnly.first().let { "dict:${it.dictId}:${it.entryId}" }
            else -> "lookup:$headword"
        }
    }

    /** One dictionary item by id; links inside a turned-off dictionary still open. */
    suspend fun dictEntry(dictId: String, id: Long): Pair<DictInfo, DictEntry>? = withContext(Dispatchers.IO) {
        ensureOpen()
        val d = all.firstOrNull { it.dictId == dictId } ?: return@withContext null
        d.entry(id)?.let { DictInfo(d.dictId, d.title) to it }
    }
}

/** [accent]: raw pitch accent or null; [definition]: first sentence of the first definition, "" if none. */
data class WordSummary(val accent: String?, val definition: String)

private const val SUMMARY_LENGTH = 80

/**
 * The first sentence of a definition: of its first numbered sense when it has numbered senses
 * (before them come grammar notes: ［動バ下一］［文］た・ぶ［バ下二］《…》), without leading labels.
 */
internal fun firstSentence(body: String): String {
    var text = plainText(body)
    // Sense 1: "1", "１", "❶", "①" or a drawn circle "(1)", standing alone (not 第1音), and only
    // where sense numbers can be: outside brackets (《…type 1》) and before any sentence ends.
    val t = text
    FIRST_SENSE.findAll(t).firstOrNull { m -> depthAt(t, m.range.first) == 0 && '。' !in t.substring(0, m.range.first) }
        ?.let { text = t.substring(it.range.last + 1) }
    while (true) {
        val trimmed = dropLeadingLabel(text).trimStart()
        if (trimmed == text) break
        text = trimmed
    }
    val end = text.indexOf('。')
    return (if (end >= 0) text.substring(0, end + 1) else text).take(SUMMARY_LENGTH)
}

private val FIRST_SENSE = Regex("(?:^|\\s)(?:[1１❶①]|\\(1\\))(?=\\s|[^0-9０-９])\\s*")
private const val OPENERS = "［〔《(（【〘"
private const val CLOSERS = "］〕》)）】〙"

/**
 * [text] without one bracketed label at its start, such as ［動ワ五（ハ四）］: brackets may nest,
 * so the label ends where the depth returns to zero, not at the first closing bracket.
 */
/** How many brackets are open just before [end] in [text]. */
private fun depthAt(text: String, end: Int): Int {
    var depth = 0
    for (i in 0 until end) {
        val c = text[i]
        if (c in OPENERS) depth++ else if (c in CLOSERS && depth > 0) depth--
    }
    return depth
}

internal fun dropLeadingLabel(text: String): String {
    if (text.isEmpty() || text[0] !in OPENERS) return text
    var depth = 0
    text.forEachIndexed { i, c ->
        if (c in OPENERS) depth++ else if (c in CLOSERS && --depth == 0) return text.substring(i + 1)
    }
    return text // never closed: leave it
}

/** [all] sorted by [DictPrefs.order]; dictionaries it doesn't name keep their default order, after it. */
private fun arrange(all: List<DictDb>, prefs: DictPrefs): List<DictDb> =
    all.sortedBy { d -> prefs.order.indexOf(d.dictId).let { if (it < 0) Int.MAX_VALUE else it } }
