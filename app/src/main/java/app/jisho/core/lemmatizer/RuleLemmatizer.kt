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
 * The parsed rule table: suffix rules, honorific prefixes (お, ご), [raw]: the intermediate
 * types the typed word itself can be, each with its label (null for none), [never]: marker ->
 * reasons or types no rule further in may have, and [minStem]: reason -> shortest kana-only
 * stem its rules may leave.
 */
data class RuleTable(
    val rules: List<DeinflectRule>,
    val prefixes: List<String>,
    val raw: Map<String, String?>,
    val never: Map<String, Set<String>> = emptyMap(),
    val minStem: Map<String, Int> = emptyMap(),
)

class RuleLemmatizer(private val table: RuleTable, private val maxDepth: Int = 7) : Lemmatizer {

    private val rules = table.rules

    /** [passed]: the reasons and types of every step undone so far, for the @never lines. */
    private class Form(val term: String, val types: Set<String>?, val reasons: List<String>, val passed: Set<String>)

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
        // A form reached again counts as new only if it carries other @never markers.
        val seen = HashSet<Triple<String, Set<String>, Set<String>>>()
        var frontier = listOf(Form(text, null, emptyList(), emptySet()))
        repeat(maxDepth) {
            val next = ArrayList<Form>()
            for (f in frontier) for (r in rules) {
                if (!f.term.endsWith(r.from)) continue
                val rawLabel = if (f.types == null) rawLabel(r) ?: continue else emptyList()
                if (f.types != null && r.typesIn.none { it in f.types || (it == "v5" && f.types.any { t -> t in GODAN_SUBTYPES }) }) continue
                val term = f.term.dropLast(r.from.length) + r.to
                val passed = f.passed + rawLabel
                if (term.isEmpty() || forbidden(r, passed) || tooShort(r, term)) continue
                if (!seen.add(Triple(term, r.typesOut, (passed + r.reasons + r.typesOut) intersect table.never.keys))) continue
                // The rule removes the innermost remaining step, so its labels go first.
                val form = Form(term, r.typesOut, r.reasons + rawLabel + f.reasons, passed + r.reasons + r.typesOut)
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

    /** An @never line forbids [r] inside one of the steps [passed] (書いてん is not 書いてる + ん). */
    private fun forbidden(r: DeinflectRule, passed: Set<String>): Boolean = passed.any { m ->
        val never = table.never[m] ?: return@any false
        r.reasons.any { it in never } || r.typesOut.any { it in never }
    }

    /** An @min-stem line: おいしそう is not お + い(る) + し + そう; a kanji stem (お見えになる) is fine. */
    private fun tooShort(r: DeinflectRule, stem: String): Boolean =
        r.reasons.any { reason -> table.minStem[reason]?.let { stem.length < it && stem.all { c -> c.isKana() } } == true }

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
                never = directive("@never").groupBy({ it[1] }, { it[2].split(',').map(String::trim) })
                    .mapValues { (_, lists) -> lists.flatten().toSet() },
                minStem = directive("@min-stem").associate { it[1] to it[2].toInt() },
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
