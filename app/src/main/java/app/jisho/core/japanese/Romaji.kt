package app.jisho.core.japanese

/** Converts IME-style romaji (Hepburn and Kunrei spellings) to hiragana. */
object Romaji {
    private val TABLE: Map<String, String> = buildMap {
        val rows = listOf(
            "" to listOf("a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お"),
        )
        rows.forEach { (_, list) -> list.forEach { put(it.first, it.second) } }
        fun row(c: String, kana: String) {
            val k = kana.split(" ")
            listOf("a", "i", "u", "e", "o").forEachIndexed { i, v -> if (k[i] != "-") put(c + v, k[i]) }
        }
        row("k", "か き く け こ"); row("g", "が ぎ ぐ げ ご")
        row("s", "さ し す せ そ"); row("z", "ざ じ ず ぜ ぞ")
        row("t", "た ち つ て と"); row("d", "だ ぢ づ で ど")
        row("n", "な に ぬ ね の"); row("h", "は ひ ふ へ ほ")
        row("b", "ば び ぶ べ ぼ"); row("p", "ぱ ぴ ぷ ぺ ぽ")
        row("m", "ま み む め も"); row("y", "や - ゆ いぇ よ")
        row("r", "ら り る れ ろ"); row("l", "ら り る れ ろ")
        row("w", "わ うぃ う うぇ を"); row("f", "ふぁ ふぃ ふ ふぇ ふぉ")
        row("v", "ゔぁ ゔぃ ゔ ゔぇ ゔぉ"); row("j", "じゃ じ じゅ じぇ じょ")
        row("x", "ぁ ぃ ぅ ぇ ぉ")
        put("shi", "し"); put("chi", "ち"); put("tsu", "つ"); put("ti", "ち"); put("tu", "つ")
        put("si", "し"); put("zi", "じ"); put("hu", "ふ"); put("di", "ぢ"); put("du", "づ")
        put("xtu", "っ"); put("ltu", "っ"); put("xya", "ゃ"); put("xyu", "ゅ"); put("xyo", "ょ")
        put("nn", "ん"); put("n'", "ん"); put("wo", "を"); put("-", "ー")
        put("thi", "てぃ"); put("dhi", "でぃ"); put("twu", "とぅ"); put("dwu", "どぅ")
        val yoon = mapOf(
            "ky" to "き", "gy" to "ぎ", "sy" to "し", "sh" to "し", "zy" to "じ", "jy" to "じ",
            "ty" to "ち", "ch" to "ち", "cy" to "ち", "dy" to "ぢ", "ny" to "に", "hy" to "ひ",
            "by" to "び", "py" to "ぴ", "my" to "み", "ry" to "り",
        )
        yoon.forEach { (c, k) ->
            put(c + "a", k + "ゃ"); put(c + "u", k + "ゅ"); put(c + "o", k + "ょ")
            if (c == "sh" || c == "ch" || c == "jy") put(c + "e", k + "ぇ")
        }
        put("che", "ちぇ"); put("she", "しぇ"); put("je", "じぇ")
    }
    private val MAX_KEY = TABLE.keys.maxOf { it.length }

    fun isRomaji(text: String): Boolean =
        text.isNotEmpty() && text.all { it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '\'' }

    fun toHiragana(input: String): String {
        val s = input.lowercase()
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // Doubled consonant -> small tsu (but "nn" is ん).
            if (i + 1 < s.length && c == s[i + 1] && c != 'n' && c !in "aeiou-'") {
                out.append('っ'); i++; continue
            }
            // "tch" -> っち
            if (c == 't' && s.startsWith("tch", i)) { out.append('っ'); i++; continue }
            var matched = false
            for (len in minOf(MAX_KEY, s.length - i) downTo 1) {
                val kana = TABLE[s.substring(i, i + len)] ?: continue
                out.append(kana); i += len; matched = true; break
            }
            if (matched) continue
            // A lone n before a consonant (or at the end) is ん.
            if (c == 'n' && (i + 1 == s.length || s[i + 1] !in "aeiouy")) {
                out.append('ん'); i++; continue
            }
            out.append(c); i++
        }
        return out.toString()
    }
}
