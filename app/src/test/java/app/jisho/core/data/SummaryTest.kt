package app.jisho.core.data

import app.jisho.testing.TestPacks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One-line meanings (design-spec 5): Japanese from the first enabled Japanese-Japanese
 * dictionary, Chinese from the first enabled Japanese-Chinese one; places the user didn't
 * choose are not looked up; exports use every installed dictionary.
 */
class SummaryTest {
    @Before fun packs() = TestPacks.assumePresent()

    private val tema = "core:1327410" // 手間
    private val jc = "shogakukan_jc3"

    private fun repo(dicts: DictPrefs = DictPrefs(), lines: SummaryPrefs = SummaryPrefs()) =
        DictionaryRepository(TestPacks.opener(), prefs = { dicts }, summaries = { lines })

    private val everywhere = SummaryPlace.entries.toSet()

    @Test fun glossLineJoinsGlossesBySense() {
        val body = """<p><span class="sense">1</span>〔仕事に必要な〕<span class="gloss">劳力和时间</span>，<span class="gloss">工夫</span>．</p>""" +
            """<p><span class="sense">2</span>〔労賃〕<span class="gloss">工钱</span>．</p>"""
        assertEquals("劳力和时间，工夫；工钱", glossLine(body))
        assertEquals("费工；费事", glossLine("""<p><span class="gloss">费工；费事．</span></p>"""))
        assertEquals("", glossLine("<p>no gloss</p>"))
    }

    @Test fun japaneseLineNeverComesFromAChineseDictionary() = runBlocking {
        // Even with the Japanese-Chinese dictionary first in the user's order.
        val r = repo(DictPrefs(order = listOf(jc)), SummaryPrefs(everywhere, everywhere))
        val s = r.summary(tema, "てま", SummaryPlace.Favorites)
        assertTrue(s.definition, s.definition.isNotEmpty() && s.definition.none { it in "劳时钱" })
        assertTrue(s.chinese, s.chinese.startsWith("劳力和时间"))
    }

    @Test fun linesFollowTheSettingsPerPlace() = runBlocking {
        val r = repo() // defaults: Japanese in favorites, Chinese under the headword
        r.summary(tema, "てま", SummaryPlace.Favorites).let { assertTrue(it.definition.isNotEmpty()); assertEquals("", it.chinese) }
        r.summary(tema, "てま", SummaryPlace.Header).let { assertEquals("", it.definition); assertTrue(it.chinese.isNotEmpty()) }
        r.summary(tema, "てま", SummaryPlace.Search).let { assertEquals("", it.definition); assertEquals("", it.chinese) }
    }

    @Test fun noEnabledDictionaryMeansNoLine() = runBlocking {
        val all = DictionaryRepository(TestPacks.opener()).dictionaries(DictPrefs()).map { it.info.id }
        val ja = all.filter { it != jc }.toSet()
        repo(DictPrefs(disabled = ja), SummaryPrefs(everywhere, everywhere)).summary(tema, "てま", SummaryPlace.Favorites).let {
            assertEquals("", it.definition); assertTrue(it.chinese.isNotEmpty())
        }
        repo(DictPrefs(disabled = setOf(jc)), SummaryPrefs(everywhere, everywhere)).summary(tema, "てま", SummaryPlace.Favorites).let {
            assertTrue(it.definition.isNotEmpty()); assertEquals("", it.chinese)
        }
        // A dictionary-only item follows its own dictionary's switch (it used to show regardless).
        val target = "dict:daijisen:1"
        assertEquals("", repo(DictPrefs(disabled = setOf("daijisen"))).summary(target, "", SummaryPlace.Favorites).definition)
        assertTrue(repo().summary(target, "", SummaryPlace.Favorites).definition.isNotEmpty())
        // Exports use every installed dictionary, whatever is turned off.
        repo(DictPrefs(disabled = all.toSet()), SummaryPrefs(emptySet(), emptySet())).summary(tema, "てま", place = null).let {
            assertTrue(it.definition.isNotEmpty()); assertTrue(it.chinese.isNotEmpty())
        }
    }

    @Test fun searchRowsGetLinesOnlyWhereChosen() = runBlocking {
        val plain = repo().search("てま").words.first { it.headword == "手間" }
        assertEquals("", plain.hint); assertEquals("", plain.zhHint)
        val all = repo(lines = SummaryPrefs(setOf(SummaryPlace.Search), setOf(SummaryPlace.Search))).search("てま").words
        assertTrue(all.all { it.hint.isNotEmpty() || it.zhHint.isNotEmpty() || it.headword != "手間" })
        all.first { it.headword == "手間" }.let { assertTrue(it.hint.isNotEmpty()); assertTrue(it.zhHint.startsWith("劳力")) }
        // Look-alike rows are told apart in both languages even with the lines off.
        val alike = repo().search("こし").words.filter { it.headword == "腰" }
        assertTrue(alike.size > 1 && alike.all { it.hint.isNotEmpty() })
        assertEquals(alike.size, alike.map { it.hint }.distinct().size)
        // Both link to the same Chinese entry: a Chinese line would not tell them apart, so none.
        assertTrue(alike.all { it.zhHint.isEmpty() })
        // Hints are whole lines (the row shows "…" where the screen ends), not cut at a fixed length.
        assertTrue(alike.any { it.hint.endsWith("。") })
    }
}
