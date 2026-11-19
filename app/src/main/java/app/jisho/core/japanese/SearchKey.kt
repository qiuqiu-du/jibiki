package app.jisho.core.japanese

import java.text.Normalizer

/**
 * Lookup-key normalization. Must stay identical to `search_key()` in
 * tools/lib/textnorm.py, otherwise lookups miss (checked by SearchKeyTest).
 */
object SearchKey {
    // Whitespace is spelled out to match Python's Unicode \s. Do not use the (?U) flag:
    // desktop Java accepts it, but Android's ICU regex engine throws on it.
    private val MARKS = Regex("[・‐\\-‑–=＝▽▼×△〈〉《》〔〕\\p{Z}\\t\\n\\x0B\\f\\r\\x1C-\\x1F\\x85]")

    fun of(text: String): String =
        kataToHira(MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKC), ""))

    fun kataToHira(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(if (c in 'ァ'..'ヴ') c - 0x60 else c)
        return sb.toString()
    }

    /** Exclusive upper bound for a prefix range query: key >= prefix AND key < upper. */
    fun prefixUpperBound(prefix: String): String = prefix + '￿'
}
