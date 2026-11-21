package app.jisho.core.database

import app.jisho.testing.TestPacks
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ranking quality, measured over whole case sets instead of single words, so a
 * change that fixes one word but breaks others shows up as a lower hit rate.
 */
class RankingTest {
    private data class Case(val query: String, val expected: String, val maxRank: Int)

    private val cases by lazy {
        TestPacks.resourceLines("ranking_cases.tsv").map {
            val (q, e, n) = it.split('\t')
            Case(q, e, n.toInt())
        }
    }

    @Before fun packs() = TestPacks.assumePresent()

    /** Rank (1-based) of the expected word for a query, or null if not in the top 20. */
    private fun rank(core: CoreDb, case: Case): Int? {
        val isKana = case.query.all { it in 'ぁ'..'ヿ' || it == 'ー' }
        val key = app.jisho.core.japanese.SearchKey.of(case.query)
        val results = core.search(key, kanaInput = isKana, limit = 20)
        val idx = results.indexOfFirst { c ->
            c.headword == case.expected || core.entry(c.entryId)!!.forms.any { it.kanji == case.expected }
        }
        return if (idx < 0) null else idx + 1
    }

    private fun evaluate(mask: Int, file: java.io.File = TestPacks.coreFile): Pair<Int, List<String>> {
        CoreDb(app.jisho.testing.JdbcSqlDb(file), mask).use { core ->
            val misses = cases.mapNotNull { c ->
                val r = rank(core, c)
                if (r != null && r <= c.maxRank) null else "${c.query} → ${c.expected}: rank ${r ?: ">20"} (max ${c.maxRank})"
            }
            return (cases.size - misses.size) to misses
        }
    }

    @Test
    fun handWrittenCases() {
        val (hitsMain, missesMain) = evaluate(mask = 1)
        val (hitsAny, missesAny) = evaluate(mask = 2)
        println("Ranking hit rate, 'usually kana' from main sense: $hitsMain/${cases.size}")
        missesMain.forEach { println("  miss: $it") }
        println("Ranking hit rate, 'usually kana' from any sense:  $hitsAny/${cases.size}")
        missesAny.forEach { println("  miss: $it") }
        assertTrue("main-sense rule must not be worse than any-sense", hitsMain >= hitsAny)
        assertTrue("hit rate ${hitsMain}/${cases.size}; misses: $missesMain", hitsMain >= cases.size * 0.95)
    }

    /**
     * With only the app (bundled core: JMdict raised by Jiten's frequency, no 新明解 pack) the
     * ranking must stay close: 2026-11-22, 115/117 (with the pack 117/117).
     */
    @Test
    fun bundledCoreAloneRanksWell() {
        val (hits, misses) = evaluate(mask = 1, file = TestPacks.bundledCore)
        println("Ranking hit rate, bundled core only: $hits/${cases.size}")
        misses.forEach { println("  miss: $it") }
        assertTrue("hit rate $hits/${cases.size}; misses: $misses", hits >= cases.size - 3)
    }

    /** Every very common kanji word must be in the top 3 when typed exactly (homographs share a key). */
    @Test
    fun commonKanjiWordsAreFoundByTheirSpelling() {
        val core = TestPacks.sharedCore
        val ids = (1..24).flatMap { freq -> commonIds(freq) }.distinct()
        val misses = ids.mapNotNull { id ->
            val e = core.entry(id)!!
            val kanji = e.forms.first().kanji ?: return@mapNotNull null
            val top = core.search(app.jisho.core.japanese.SearchKey.of(kanji), kanaInput = false, limit = 3)
            if (top.any { it.entryId == id }) null else kanji
        }
        println("Common kanji words: ${ids.size - misses.size}/${ids.size} in top 3; misses: ${misses.take(20)}")
        assertTrue("misses: ${misses.take(20)}", misses.size <= ids.size / 100)
    }

    private fun commonIds(freq: Int): List<Long> =
        app.jisho.testing.JdbcSqlDb(TestPacks.coreFile).use { db ->
            db.query("SELECT id FROM entry WHERE freq = $freq") { it.long(0) }
        }
}
