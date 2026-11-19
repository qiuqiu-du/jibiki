package app.jisho.core.database

/** One spelling of a JMdict entry; kanji is null for kana-only spellings. */
data class Form(val kanji: String?, val reading: String)

/** A search result row backed by core.db. */
data class Candidate(
    val entryId: Long,
    val headword: String,
    val reading: String,
    val accent: String?,
    val exact: Boolean,
    /** Kanji spellings shown next to a kana headword so homophones stay distinguishable (なる 成る・為る). */
    val kanjiNote: String = "",
    /** Start of the definition, added only when rows would otherwise look identical. */
    val hint: String = "",
) {
    /** What the user sees; two rows in one result list must never share it. */
    val displayKey: List<String> get() = listOf(headword, reading, kanjiNote, hint)
}

/** A search result that exists only in one dictionary (encyclopedia items, idioms...). */
data class DictCandidate(val dictId: String, val entryId: Long, val title: String)

data class CoreEntry(
    val id: Long,
    val forms: List<Form>,
    val pos: List<String>,
    val freq: Int,
    val usuallyKana: Boolean,
    /** reading -> accent string such as "2", "0,2" or "1+1" */
    val accents: Map<String, String>,
) {
    /** Kana for words usually written in kana (とても), else the first spelling. */
    val headword: String get() = if (usuallyKana) reading else forms.first().let { it.kanji ?: it.reading }
    val reading: String get() = forms.first().reading
    val isCommon: Boolean get() = freq < 99
}

data class DictEntry(val dictId: String, val id: Long, val title: String, val body: String)
