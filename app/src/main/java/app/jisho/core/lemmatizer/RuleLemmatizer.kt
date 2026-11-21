package app.jisho.core.lemmatizer

/** One line of assets/deinflect_rules.tsv. */
data class DeinflectRule(
    val from: String,
    val to: String,
    /** Empty: only applies to the raw input. */
    val typesIn: Set<String>,
    val typesOut: Set<String>,
    val reasons: List<String>,
)

/**
 * Rule-table lemmatizer (design-spec 4.2, option B): strips inflection suffixes step by
 * step, as in 食べなかった -> 食べない (adj-i) -> 食べ (mizen) -> 食べる (v1). It lists every
 * reading of an ambiguous form (いった -> いく・いう・いる); JMdict validation removes the
 * forms that don't exist. All grammar lives in the rule table, none in this code.
 */
/**
 * The parsed rule table: suffix rules, honorific prefixes (お, ご), and [raw]: the
 * intermediate types the typed word itself can be, each with its label (null for none).
 */
data class RuleTable(val rules: List<DeinflectRule>, val prefixes: List<String>, val raw: Map<String, String?>)

class RuleLemmatizer(private val table: RuleTable, private val maxDepth: Int = 7) : Lemmatizer {

    private val rules = table.rules

    private class Form(val term: String, val types: Set<String>?, val reasons: List<String>)

    override fun lemmatize(text: String): List<LemmaCandidate> {
        // Honorific frames (お〜になる, お〜する, お〜です, お〜ください) need their prefix: without
        // it, おいしそう would read as お + い(る) + し(する) + そう.
        val plain = deinflect(text).filter { HONORIFIC !in it.reasons }
        // お読みになる -> 読む: without the prefix, but only through an honorific ending.
        val unprefixed = table.prefixes.filter { text.length > it.length + 1 && text.startsWith(it) }
            .flatMap { p -> deinflect(text.removePrefix(p)).filter { HONORIFIC in it.reasons } }
            .map { it.copy(dropsPrefix = true) }
        return plain + unprefixed
    }

    private fun deinflect(text: String): List<LemmaCandidate> {
        val result = ArrayList<LemmaCandidate>()
        val seen = HashSet<Pair<String, Set<String>>>()
        var frontier = listOf(Form(text, null, emptyList()))
        repeat(maxDepth) {
            val next = ArrayList<Form>()
            for (f in frontier) for (r in rules) {
                if (!f.term.endsWith(r.from)) continue
                val rawLabel = if (f.types == null) rawLabel(r) ?: continue else emptyList()
                if (f.types != null && r.typesIn.none { it in f.types }) continue
                val term = f.term.dropLast(r.from.length) + r.to
                if (term.isEmpty() || !seen.add(term to r.typesOut)) continue
                // A lone kana is too little to carry an honorific frame: おいしそう is not
                // お + い(る) + し(する) + そう. One-kanji stems (お見えになる's 見え, 出) are fine.
                if (HONORIFIC in r.reasons && term.length == 1 && term[0].isKana()) continue
                // The rule removes the innermost remaining step, so its labels go first.
                val form = Form(term, r.typesOut, r.reasons + rawLabel + f.reasons)
                next += form
                val finals = r.typesOut intersect FINAL_TYPES
                if (finals.isNotEmpty() && term != text) {
                    result += LemmaCandidate(term, finals, form.reasons.dedupeAdjacent(), LemmaSource.Rules)
                }
            }
            if (next.isEmpty()) return result
            frontier = next
        }
        return result
    }

    /**
     * Whether [r] may read the typed word itself, and the label that reading adds: a rule
     * for final types or for no types needs none; one for intermediate types needs an
     * @raw line (読み can be a 連用形, 読ま is never a 未然形 on its own). Null: not allowed.
     */
    private fun rawLabel(r: DeinflectRule): List<String>? {
        if (r.typesIn.isEmpty() || r.typesIn.any { it in FINAL_TYPES }) return emptyList()
        val type = r.typesIn.firstOrNull { it in table.raw } ?: return null
        return listOfNotNull(table.raw[type])
    }

    companion object {
        const val HONORIFIC = "honorific"

        /** Parses the TSV rule table ("-" = empty). */
        fun parse(text: String): RuleTable {
            val lines = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
            val (directives, ruleLines) = lines.partition { it.startsWith("@") }
            fun directive(name: String) = directives.filter { it.startsWith("$name\t") }.map { it.split('\t') }
            return RuleTable(
                parseRules(ruleLines),
                prefixes = directive("@prefix").map { it[1] },
                raw = directive("@raw").associate { it[1] to it[2].takeIf { r -> r != "-" } },
            )
        }

        private fun parseRules(lines: List<String>): List<DeinflectRule> = lines
            .map { line ->
                val c = line.split('\t')
                require(c.size == 5) { "deinflect rule needs 5 columns: $line" }
                fun str(s: String) = if (s == "-") "" else s
                fun list(s: String) = if (s == "-") emptyList() else s.split(',').map { it.trim() }
                DeinflectRule(str(c[0]), str(c[1]), list(c[2]).toSet(), list(c[3]).toSet(), list(c[4]))
            }
    }
}

private fun Char.isKana() = this in '\u3041'..'\u30FF'

internal fun List<String>.dedupeAdjacent(): List<String> =
    filterIndexed { i, s -> i == 0 || this[i - 1] != s }
