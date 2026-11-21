package app.jisho.core.database

import app.jisho.testing.TestPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for core.db access: entries, forms, pitch, prefix search and display rules. */
class CoreDbTest {
    private val core get() = TestPacks.sharedCore

    @Before fun packs() = TestPacks.assumePresent()

    @Test fun schemaVersionMatchesApp() = assertEquals(app.jisho.core.datapack.DataPackManager.CORE_SCHEMA_VERSION, core.schemaVersion)

    @Test
    fun entryHasFormsPosAndPitch() {
        val taberu = core.entry(1358280)!!
        assertEquals("食べる", taberu.headword)
        assertEquals("たべる", taberu.reading)
        assertTrue("v1" in taberu.pos)
        assertEquals("2", taberu.accents["たべる"])
        assertTrue(taberu.isCommon)
    }

    @Test
    fun usuallyKanaWordsUseKanaHeadword() {
        val suru = core.entry(1157170)!! // 為る
        assertTrue(suru.usuallyKana)
        assertEquals("する", suru.headword)
    }

    @Test
    fun usuallyKanaDisplayOnlyForTheMainReading() {
        val hashi = core.search("はし", kanaInput = true)
        // 嘴 is usually written くちばし; matched through its minor reading はし it keeps the kanji.
        assertTrue(hashi.any { it.headword == "嘴" && it.reading == "はし" })
        assertTrue(hashi.none { it.headword == "はし" && it.entryId == 1565560L })
        assertEquals("とても", core.search("とても", kanaInput = true).first().headword)
    }

    @Test
    fun kanaHeadwordsCarryTheirKanji() {
        val naru = core.search("なる", kanaInput = true).take(2)
        assertTrue(naru.all { it.headword == "なる" && it.kanjiNote.isNotEmpty() })
        assertTrue(naru[0].kanjiNote != naru[1].kanjiNote)
    }

    @Test fun missingEntryIsNull() = assertEquals(null, core.entry(-1))

    @Test
    fun exactMatchesComeFirst() {
        val r = core.search("たべる", kanaInput = true)
        assertTrue(r.first().exact)
        assertEquals("食べる", r.first().headword)
        assertTrue("prefix results follow", r.drop(1).all { !it.exact })
    }

    @Test
    fun prefixSearchFindsLongerWords() {
        val r = core.search("食べ", kanaInput = false).map { it.headword }
        assertTrue(r.containsAll(listOf("食べる", "食べ物")))
    }

    @Test
    fun displayShowsTheMatchedSpelling() {
        // 剃る is reachable through its alternative reading する; show that reading, not そる.
        val soru = core.search("する", kanaInput = true).first { it.headword == "剃る" }
        assertEquals("する", soru.reading)
        // Searching by kanji keeps the kanji even for usually-kana words.
        assertEquals("為る", core.search("為る", kanaInput = false).first().headword)
    }

    @Test
    fun candidatesCarryPitch() {
        val hashi = core.search("はし", kanaInput = true).associate { it.headword to it.accent }
        assertEquals("2", hashi["橋"]); assertEquals("1", hashi["箸"]); assertEquals("0", hashi["端"])
    }

    @Test
    fun emptyAndUnknownQueries() {
        assertTrue(core.search("", kanaInput = true).isEmpty())
        assertTrue(core.search("ゐゑゐゑゐ", kanaInput = true).isEmpty())
    }

    @Test
    fun limitIsRespected() = assertEquals(10, core.search("か", kanaInput = true, limit = 10).size)

    @Test
    fun searchIsFastOnTheJvm() {
        // Coarse guard against slow query plans; the real target (≤ 50 ms) is measured on the phone.
        val queries = listOf("た", "か", "し", "たべ", "せんせい", "日", "日本", "こうこう", "あ", "ん")
        queries.forEach { core.search(it, true) } // warm up
        val start = System.nanoTime()
        repeat(5) { queries.forEach { q -> assertNotNull(core.search(q, true)) } }
        val avgMs = (System.nanoTime() - start) / 1e6 / (5 * queries.size)
        assertTrue("average search $avgMs ms", avgMs < 30)
    }
}
