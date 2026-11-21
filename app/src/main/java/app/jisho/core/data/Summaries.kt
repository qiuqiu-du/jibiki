package app.jisho.core.data

/** Where a one-line meaning can appear (design-spec 5). */
enum class SummaryPlace { Search, Favorites, Header }

/**
 * Where the user wants one-line meanings: Japanese (from the first enabled Japanese-Japanese
 * dictionary that defines the word) and Chinese (from the first enabled Japanese-Chinese one).
 * In search results a line not chosen here still appears where rows would otherwise look the
 * same. Places not chosen are not looked up at all.
 */
data class SummaryPrefs(
    val japanese: Set<SummaryPlace> = setOf(SummaryPlace.Favorites),
    val chinese: Set<SummaryPlace> = setOf(SummaryPlace.Header),
) {
    /** Stored as "ja:Favorites,zh:Header". */
    fun encode(): String = (japanese.map { "ja:${it.name}" } + chinese.map { "zh:${it.name}" }).sorted().joinToString(",")

    companion object {
        fun decode(s: String?): SummaryPrefs {
            if (s == null) return SummaryPrefs()
            val items = s.split(',').mapNotNull { item ->
                val (lang, place) = item.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                SummaryPlace.entries.firstOrNull { it.name == place }?.let { lang to it }
            }
            return SummaryPrefs(
                items.filter { it.first == "ja" }.map { it.second }.toSet(),
                items.filter { it.first == "zh" }.map { it.second }.toSet(),
            )
        }
    }
}

/**
 * The Chinese meaning of a Japanese-Chinese entry: its marked glosses (<span class="gloss">),
 * joined with 「，」 within a sense and 「；」 between senses; "" if it marks none.
 */
internal fun glossLine(body: String): String {
    val senses = mutableListOf<MutableList<String>>(mutableListOf())
    var i = 0
    while (true) {
        val sense = body.indexOf(SENSE, i)
        val gloss = body.indexOf(GLOSS, i)
        if (gloss < 0) break
        if (sense in 0 until gloss) {
            if (senses.last().isNotEmpty()) senses += mutableListOf<String>()
            i = sense + SENSE.length
            continue
        }
        val end = closingSpan(body, gloss + GLOSS.length)
        val text = plainText(body.substring(gloss + GLOSS.length, end)).trim().trimEnd('．', '.', '。', ' ')
        if (text.isNotEmpty()) senses.last() += text
        i = end
    }
    return senses.filter { it.isNotEmpty() }.joinToString("；") { it.joinToString("，") }
}

private const val SENSE = "<span class=\"sense\">"
private const val GLOSS = "<span class=\"gloss\">"

/** Index of the </span> closing a span whose content starts at [from] (spans may nest). */
private fun closingSpan(body: String, from: Int): Int {
    var depth = 1
    var i = from
    while (i < body.length) {
        when {
            body.startsWith("<span", i) -> depth++
            body.startsWith("</span>", i) -> if (--depth == 0) return i
        }
        i++
    }
    return body.length
}
