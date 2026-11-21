package app.jisho.core.lemmatizer

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Kuromoji + IPADIC lemmatizer (design-spec 4.2, option A). It reads the word in context,
 * so it gives one answer for an ambiguous form; [CombinedLemmatizer] adds the rule table's
 * other readings. It only finds the dictionary form; whether the input is an inflection,
 * and which one, is decided by the rule table (see [CombinedLemmatizer]). Building the [Tokenizer] takes about a second, so callers load it in the
 * background (see [KuromojiHolder]).
 */
class KuromojiLemmatizer(private val tokenizer: Tokenizer) : Lemmatizer, Segmenter {

    override fun lemmatize(text: String): List<LemmaCandidate> {
        val tokens = tokenizer.tokenize(text)
        var i = tokens.indexOfFirst { it.partOfSpeechLevel1 != "接頭詞" } // お読みになる -> 読む
        if (i < 0) return emptyList()
        val head = tokens[i]
        val rest = tokens.subList(i + 1, tokens.size)
        val candidate = when {
            // ございます: IPADIC tags ござる as an auxiliary, but it conjugates like a verb
            head.partOfSpeechLevel1 == "動詞" || head.partOfSpeechLevel1 == "形容詞" ||
                (head.partOfSpeechLevel1 == "助動詞" && typeOf(head) != null) ->
                typeOf(head)?.let { LemmaCandidate(baseOf(head), setOf(it), emptyList(), LemmaSource.Kuromoji) }
            // 勉強した: a suru noun followed by する
            head.partOfSpeechLevel2 == "サ変接続" && rest.firstOrNull()?.baseForm == "する" ->
                LemmaCandidate(head.surface + "する", setOf("vs"), emptyList(), LemmaSource.Kuromoji)
            // 綺麗だった: a na-adjective stem followed by its endings
            head.partOfSpeechLevel2 == "形容動詞語幹" && rest.isNotEmpty() ->
                LemmaCandidate(head.surface, setOf("adj-na"), emptyList(), LemmaSource.Kuromoji)
            else -> null
        }
        return listOfNotNull(candidate?.takeIf { it.lemma != text })
    }

    /**
     * Content words of a sentence, each with the endings that follow it (食べなかった -> 食べる).
     * Auxiliary verbs and adjectives (補助動詞: 食べて｜くる, 決め｜かねる, 読み｜終わった) are
     * words of their own: dictionaries define them as separate headwords, not as inflections.
     */
    override fun segment(text: String): List<Segment> {
        val out = ArrayList<Segment>()
        var surface = StringBuilder()
        var lookup: String? = null
        fun flush() { lookup?.let { out += Segment(surface.toString(), it) }; surface = StringBuilder(); lookup = null }
        for (t in tokenizer.tokenize(text)) {
            val auxiliary = t.partOfSpeechLevel1 in AUXILIARY_HEADS && t.partOfSpeechLevel2 == "非自立"
            val content = auxiliary || (t.partOfSpeechLevel1 in CONTENT && t.partOfSpeechLevel2 !in SUFFIX_LIKE)
            val attaches = lookup != null && !auxiliary && (t.partOfSpeechLevel1 == "助動詞" || t.partOfSpeechLevel2 in SUFFIX_LIKE ||
                (t.partOfSpeechLevel1 == "助詞" && t.partOfSpeechLevel2 == "接続助詞" && t.baseForm in setOf("て", "で")))
            when {
                attaches -> surface.append(t.surface)
                content -> { flush(); surface.append(t.surface); lookup = baseOf(t) }
                else -> flush()
            }
        }
        flush()
        return out
    }

    private fun baseOf(t: Token) = t.baseForm?.takeIf { it != "*" } ?: t.surface

    private fun typeOf(t: Token): String? {
        val c = t.conjugationType
        return when {
            c.startsWith("一段") -> "v1"
            c.startsWith("五段") -> "v5"
            c.startsWith("カ変") -> "vk"
            c.startsWith("サ変・－ズル") -> "vz"
            c.startsWith("サ変") -> "vs"
            c.startsWith("形容詞") -> "adj-i"
            else -> null
        }
    }

    private companion object {
        val CONTENT = setOf("名詞", "動詞", "形容詞", "副詞", "連体詞", "接続詞", "感動詞")
        val SUFFIX_LIKE = setOf("非自立", "接尾")
        /** Parts of speech whose 非自立 words are auxiliaries (くる, いく, かねる, ほしい). */
        val AUXILIARY_HEADS = setOf("動詞", "形容詞")
    }
}

/**
 * Mode A: Kuromoji's context-based reading first, then every other reading from the rule
 * table. The rule table is the single source of grammar: a Kuromoji reading is kept only
 * when the rule table also reaches that dictionary form from the whole input, and it takes
 * the word type and labels of that rule path. Kuromoji ignores trailing text it can't attach
 * (食べないあ -> 食べる), so without this check any extra kana would still give a lemma.
 */
class CombinedLemmatizer(private val kuromoji: () -> KuromojiLemmatizer?, private val rules: RuleLemmatizer) : Lemmatizer {
    override fun lemmatize(text: String): List<LemmaCandidate> {
        val ruled = rules.lemmatize(text)
        val read = kuromoji()?.lemmatize(text).orEmpty().mapNotNull { c ->
            // The rule path decides the word type as well as the labels: おうだ is a na-adjective
            // + だ, so Kuromoji's verb 追う must not pass as one.
            ruleMatch(c, ruled)?.let { r -> c.copy(types = r.types, reasons = r.reasons, dropsPrefix = r.dropsPrefix) }
        }
        return read + ruled
    }

    /** The shortest rule path to the same lemma, preferring one with the same word type; null if none. */
    private fun ruleMatch(c: LemmaCandidate, ruled: List<LemmaCandidate>): LemmaCandidate? {
        val sameLemma = ruled.filter { it.lemma == c.lemma }
        return sameLemma.filter { r -> r.types.any { it in c.types } }.ifEmpty { sameLemma }
            .minWithOrNull(compareBy({ it.dropsPrefix }, { it.reasons.size }))
    }
}

/** Builds the Kuromoji tokenizer once, off the main thread; until then mode A uses the rule table only. */
class KuromojiHolder {
    @Volatile var lemmatizer: KuromojiLemmatizer? = null
        private set

    @Synchronized fun load(): KuromojiLemmatizer = lemmatizer ?: KuromojiLemmatizer(Tokenizer()).also { lemmatizer = it }
}
