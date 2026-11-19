package app.jisho.core.japanese

import app.jisho.testing.TestPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for core/japanese: lookup-key normalization and romaji conversion. */
class JapaneseTextTest {

    // --- SearchKey ---

    @Test
    fun searchKeyMatchesPythonBuildScripts() {
        // Vectors produced by tools/tests/gen_search_key_vectors.py from lib/textnorm.py.
        val failures = TestPacks.resourceLines("search_key_vectors.tsv").mapNotNull { line ->
            val (input, expected) = line.split('\t')
            SearchKey.of(input).takeIf { it != expected }?.let { "$input -> $it (python: $expected)" }
        }
        assertEquals(failures.take(10).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun searchKeyNormalizesKanaWidthAndMarks() {
        assertEquals("たべる", SearchKey.of("タベル"))
        assertEquals("たべる", SearchKey.of("た・べる"))
        assertEquals("かたかな", SearchKey.of("ｶﾀｶﾅ"))
        assertEquals("こーひー", SearchKey.of("コーヒー"))
        assertEquals("ABC", SearchKey.of("ＡＢＣ"))
        assertEquals("食べる", SearchKey.of("食べる"))
    }

    @Test
    fun prefixUpperBoundSortsAfterEveryExtension() {
        val upper = SearchKey.prefixUpperBound("たべ")
        listOf("たべ", "たべる", "たべものや").forEach { assertTrue(it < upper) }
        assertFalse("たぼ" < upper)
    }

    // --- Romaji ---

    private fun romaji(input: String, kana: String) = assertEquals(input, kana, Romaji.toHiragana(input))

    @Test fun romajiBasic() { romaji("taberu", "たべる"); romaji("nihon", "にほん"); romaji("kanji", "かんじ") }
    @Test fun romajiSyllabicN() {
        romaji("kon'ya", "こんや"); romaji("konnya", "こんや"); romaji("konnnya", "こんにゃ")
        romaji("shinbun", "しんぶん"); romaji("hon", "ほん"); romaji("densha", "でんしゃ")
    }
    @Test fun romajiDoubledConsonants() { romaji("kitte", "きって"); romaji("matcha", "まっちゃ"); romaji("zasshi", "ざっし") }
    @Test fun romajiYoon() { romaji("kyou", "きょう"); romaji("shashin", "しゃしん"); romaji("jinja", "じんじゃ"); romaji("chotto", "ちょっと") }
    @Test fun romajiKunreiSpellings() { romaji("tukue", "つくえ"); romaji("sinbun", "しんぶん"); romaji("tyotto", "ちょっと") }
    @Test fun romajiLongVowelAndUppercase() { romaji("ko-hi-", "こーひー"); romaji("Tokyo", "ときょ") }
    @Test fun romajiDetection() {
        assertTrue(Romaji.isRomaji("taberu")); assertTrue(Romaji.isRomaji("kon'ya"))
        assertFalse(Romaji.isRomaji("食べる")); assertFalse(Romaji.isRomaji("")); assertFalse(Romaji.isRomaji("abc1"))
    }
}
