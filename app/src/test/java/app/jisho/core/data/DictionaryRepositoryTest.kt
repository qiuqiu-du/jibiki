package app.jisho.core.data

import app.jisho.testing.TestPacks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for core/data: input handling and how results from the packs are combined. */
class DictionaryRepositoryTest {
    private val repo by lazy { DictionaryRepository(TestPacks.opener()) }

    @Before fun packs() = TestPacks.assumePresent()

    @Test fun romajiIsConvertedBeforeSearching() = runBlocking {
        assertEquals("食べる", repo.search("taberu").words.first().headword)
        assertEquals("先生", repo.search("sensei").words.first().headword)
    }

    @Test fun katakanaAndWhitespaceAreNormalized() = runBlocking {
        assertEquals(repo.search("たべる").words.first().entryId, repo.search(" タベル ").words.first().entryId)
    }

    @Test fun dictionaryOnlyResultsAppearWhenFewWordsMatch() = runBlocking {
        val r = repo.search("はまでら")
        assertTrue(r.dictOnly.any { "浜寺" in it.title })
    }

    @Test fun entryCombinesCoreAndAllDictionaries() = runBlocking {
        val e = repo.entry(1358280)!!
        assertEquals("食べる", e.core.headword)
        assertEquals(TestPacks.dictionaryIds, e.definitions.map { it.first.id })
        assertTrue(e.definitions.all { it.second.isNotEmpty() })
    }

    @Test fun dictEntryById() = runBlocking {
        val first = repo.entry(1358280)!!.definitions.first().second.first()
        val (info, entry) = repo.dictEntry(first.dictId, first.id)!!
        assertEquals("daijisen", info.id)
        assertEquals(first.title, entry.title)
        assertEquals(null, repo.dictEntry("nope", 1))
    }

    /**
     * Display rule: rows in one result list must be distinguishable. Checked for every
     * reading shared by two or more common words, where homophones collide.
     */
    @Test fun homophoneCandidatesAreDistinguishable() = runBlocking {
        val readings = app.jisho.testing.JdbcSqlDb(TestPacks.coreFile).use { db ->
            db.query(
                "SELECT f.reading FROM form f JOIN entry e ON e.id = f.entry_id WHERE e.score < 60 " +
                    "GROUP BY f.reading HAVING COUNT(DISTINCT f.entry_id) > 1"
            ) { it.string(0) }
        }
        var hinted = 0
        val clashes = readings.mapNotNull { r ->
            val rows = repo.search(r).words.take(20)
            hinted += rows.count { it.hint.isNotEmpty() }
            val dup = rows.groupBy { it.displayKey }.filterValues { it.size > 1 }.keys
            if (dup.isEmpty()) null else "$r: $dup"
        }
        println("homophone readings: ${readings.size}, rows with a hint: $hinted, still identical: ${clashes.size}")
        clashes.take(20).forEach { println("  $it") }
        assertTrue("identical rows: ${clashes.take(10)}", clashes.isEmpty())
    }

    @Test fun naruRowsAreDistinguishable() = runBlocking {
        val top = repo.search("naru").words.take(2)
        assertTrue(top.all { it.headword == "なる" })
        assertTrue(top[0].displayKey != top[1].displayKey)
    }

    @Test fun reopenAfterImport() = runBlocking {
        assertNotNull(repo.entry(1358280))
        repo.reopen()
        assertNotNull(repo.entry(1358280))
    }
}
