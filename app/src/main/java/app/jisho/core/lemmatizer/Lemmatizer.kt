package app.jisho.core.lemmatizer

/** Which lemmatizer the user picked in settings (design-spec 4.2). */
enum class LemmatizerMode { Kuromoji, Rules }

enum class LemmaSource { Kuromoji, Rules }

/** A possible dictionary form of the input. Not trusted until [LemmaValidator] finds it in JMdict. */
data class LemmaCandidate(
    val lemma: String,
    /** Word types the lemma must have: v1, v5, v5k-s, v5u-s, v5aru, vk, vs, vz, adj-i, adj-na. */
    val types: Set<String>,
    /** Inflection labels from the dictionary form outward: 食べなかった -> [neg, past]. */
    val reasons: List<String>,
    val source: LemmaSource,
    /** Found only after dropping an honorific prefix (お読みになる -> 読む): a less direct reading. */
    val dropsPrefix: Boolean = false,
)

/** Turns an inflected form into possible dictionary forms. */
fun interface Lemmatizer {
    fun lemmatize(text: String): List<LemmaCandidate>
}

/** One word of a sentence, for tapping words one by one (Kuromoji mode only). */
data class Segment(val surface: String, val lookup: String)

fun interface Segmenter {
    fun segment(text: String): List<Segment>
}

/** Final word types a candidate can carry; the others (te, ta, stem, mizen) are intermediate. */
internal val FINAL_TYPES = setOf("v1", "v5", "v5k-s", "v5u-s", "v5aru", "vk", "vs", "vz", "adj-i", "adj-na")
