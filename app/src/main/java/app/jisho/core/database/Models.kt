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
    /** Start of the Japanese definition: to tell look-alike rows apart, or always if the user chose so. */
    val hint: String = "",
    /** The Chinese meaning (glosses of a Japanese-Chinese dictionary), by the same rule as [hint]. */
    val zhHint: String = "",
    /** Set when the row was found by undoing an inflection (食べなかった -> 食べる). */
    val inflection: Inflection? = null,
) {
    /** What the user sees; two rows in one result list must never share it. */
    val displayKey: List<Any?> get() = listOf(headword, reading, kanjiNote, hint, inflection)
}

/** How the typed form relates to the dictionary form: [reasons] are rule-table label keys. */
data class Inflection(val from: String, val reasons: List<String>)

/** One search_key row with the entry's parts of speech (JMdict codes). */
/** [reading]: the entry's main reading (御座る -> ござる), for checks on how the word ends. */
data class KeyHit(val key: String, val entryId: Long, val pos: List<String>, val rank: Int, val reading: String = "")

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

/** [images]: gaiji pictures the body refers to (<img src="NAME">), as PNG or GIF bytes. */
data class DictEntry(
    val dictId: String,
    val id: Long,
    val title: String,
    val body: String,
    val images: Map<String, ByteArray> = emptyMap(),
)
