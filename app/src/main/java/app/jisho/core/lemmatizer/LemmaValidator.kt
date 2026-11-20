package app.jisho.core.lemmatizer

import app.jisho.core.database.CoreDb
import app.jisho.core.japanese.SearchKey

/** A lemma that exists in JMdict with a matching part of speech. */
data class ValidatedLemma(val entryId: Long, val key: String, val candidate: LemmaCandidate)

/**
 * The single place that checks lemmatizer output (Architecture 3.4): a candidate survives
 * only if JMdict has the word and its part of speech fits the inflection that was undone
 * (いった -> いる keeps 要る, a godan verb, and drops 居る, an ichidan verb).
 */
class LemmaValidator(private val core: CoreDb) {

    fun validate(candidates: List<LemmaCandidate>, limit: Int = 10): List<ValidatedLemma> {
        if (candidates.isEmpty()) return emptyList()
        // Each candidate may be looked up as itself, and suru verbs also as the bare noun.
        val lookups = candidates.flatMap { c ->
            val key = SearchKey.of(c.lemma)
            val suruNoun = if ("vs" in c.types && key.endsWith("する") && key.length > 2) key.dropLast(2) else null
            listOfNotNull(Lookup(c, key, noun = false), suruNoun?.let { Lookup(c, it, noun = true) })
        }.filter { it.key.isNotEmpty() }
        val hits = core.lookupKeys(lookups.map { it.key }.distinct()).groupBy { it.key }
        val found = ArrayList<Pair<ValidatedLemma, Int>>()
        for (l in lookups) for (h in hits[l.key].orEmpty()) {
            val ok = if (l.noun) "vs" in h.pos else l.candidate.types.any { accepts(it, l.candidate.lemma, h.pos) }
            if (ok) found += ValidatedLemma(h.entryId, l.key, l.candidate) to h.rank
        }
        // Direct readings before ones that drop a prefix (おいしそう: おいしい before お + いる),
        // then common words, then simpler inflections, then Kuromoji's reading.
        return found.sortedWith(
            compareBy<Pair<ValidatedLemma, Int>>(
                { it.first.candidate.dropsPrefix },
                { it.second },
                { it.first.candidate.reasons.size },
                { if (it.first.candidate.source == LemmaSource.Kuromoji) 0 else 1 },
            ),
        ).map { it.first }.distinctBy { it.entryId }.take(limit)
    }

    private class Lookup(val candidate: LemmaCandidate, val key: String, val noun: Boolean)

    companion object {
        private val GODAN_BY_ENDING = mapOf(
            'く' to setOf("v5k", "v5k-s"), 'ぐ' to setOf("v5g"), 'す' to setOf("v5s"), 'つ' to setOf("v5t"),
            'ぬ' to setOf("v5n"), 'ぶ' to setOf("v5b"), 'む' to setOf("v5m"),
            'る' to setOf("v5r", "v5r-i", "v5aru"), 'う' to setOf("v5u", "v5u-s"),
        )

        private val ARU_ENDINGS = listOf("さる", "ざる", "しゃる")

        /** Does a JMdict entry with these part-of-speech codes conjugate as [type]? */
        fun accepts(type: String, lemma: String, pos: List<String>): Boolean = when (type) {
            "v1" -> "v1" in pos || "v1-s" in pos
            "v5" -> GODAN_BY_ENDING[lemma.lastOrNull()].orEmpty().any { it in pos }
            "vs" -> lemma.endsWith("する") && ("vs-i" in pos || "vs-s" in pos)
            "adj-i" -> "adj-i" in pos || "adj-ix" in pos
            // JMdict tags some honorific -aru verbs as plain godan (ござる is v5r), so accept that
            // for the whole class: the verbs ending in さる・ざる・しゃる.
            "v5aru" -> "v5aru" in pos || ("v5r" in pos && ARU_ENDINGS.any { lemma.endsWith(it) })
            else -> type in pos // v5k-s, v5aru, vk, vz, adj-na
        }
    }
}
