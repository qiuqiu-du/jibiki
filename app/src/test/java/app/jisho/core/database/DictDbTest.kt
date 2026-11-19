package app.jisho.core.database

import app.jisho.testing.TestPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for dictionary packs: metadata, decompression, markup vocabulary, links, alignment. */
class DictDbTest {
    private val dicts get() = TestPacks.sharedDicts

    @Before fun packs() = TestPacks.assumePresent()

    @Test
    fun allThreeDictionariesPresentWithCurrentFormats() {
        assertEquals(TestPacks.dictionaryIds, dicts.map { it.dictId })
        dicts.forEach {
            assertEquals(it.dictId, 1, it.schemaVersion)
            assertEquals(it.dictId, 1, it.bodyFormat)
            assertTrue(it.title.isNotBlank())
        }
    }

    /** Decompresses every entry once and checks the markup contract (Architecture.md §9). */
    @Test
    fun everyEntryDecompressesWithAllowedMarkupAndValidLinks() {
        val allowedTags = setOf("p", "br", "b", "i", "sup", "sub", "ruby", "rt", "span", "a", "img")
        val allowedSpans = setOf("pos", "sense", "ex", "label", "note", "mark")
        val tag = Regex("</?([a-zA-Z0-9-]+)")
        val span = Regex("<span class=\"([^\"]*)\"")
        val internalLink = Regex("<a href=\"#(\\d+)\"")
        dicts.forEach { d ->
            val ids = d.allIds()
            val idSet = ids.toHashSet()
            val problems = mutableListOf<String>()
            ids.forEach { id ->
                val e = d.entry(id)!!
                if (e.title.isBlank()) problems += "empty title #$id"
                tag.findAll(e.body).map { it.groupValues[1] }.filter { it !in allowedTags }
                    .forEach { problems += "tag <$it> in #$id" }
                span.findAll(e.body).map { it.groupValues[1] }.filter { it !in allowedSpans }
                    .forEach { problems += "span.$it in #$id" }
                internalLink.findAll(e.body).map { it.groupValues[1].toLong() }.filter { it !in idSet }
                    .forEach { problems += "link to missing #$it in #$id" }
            }
            assertTrue("${d.dictId}: ${problems.take(10)} (${problems.size} total)", problems.isEmpty())
        }
    }

    @Test
    fun commonWordsAreLinkedFromEveryDictionary() {
        // 食べる 1358280, 橋 1237410, 雨 1170930 (JMdict ent_seq)
        for (coreId in listOf(1358280L, 1237410L)) {
            dicts.forEach { d ->
                val entries = d.entriesForCore(coreId)
                assertTrue("${d.dictId} has no entry for $coreId", entries.isNotEmpty())
                assertTrue(entries.first().body.isNotBlank())
            }
        }
    }

    /** Daijisen titles with category labels (ひかり［列車］, が［接］) must still align to JMdict. */
    @Test
    fun labeledTitlesAreLinked() {
        val daijisen = dicts.first { it.dictId == "daijisen" }
        val trainHikari = 1010480L // JMdict: ひかり, the Shinkansen service
        assertTrue(daijisen.entriesForCore(trainHikari).any { it.title == "ひかり［列車］" })
        val kedo = app.jisho.testing.JdbcSqlDb(TestPacks.coreFile).use { db ->
            db.query("SELECT entry_id FROM form WHERE reading = 'けど' AND kanji IS NULL") { it.long(0) }
        }
        assertTrue(kedo.any { id -> daijisen.entriesForCore(id).any { "［接］" in it.title } })
    }

    @Test
    fun titlesMatchTheWord() {
        val daijisen = dicts.first { it.dictId == "daijisen" }
        assertEquals("た・べる【食べる】", daijisen.entriesForCore(1358280).first().title)
    }

    @Test
    fun dictionaryOnlyKeysFindEncyclopedicEntries() {
        // Place names are not in JMdict; they must be reachable through dict_key.
        val hits = dicts.first { it.dictId == "daijisen" }.searchOwnKeys("はまでら")
        assertTrue(hits.any { "浜寺" in it.title })
    }

    @Test fun missingEntryIsNull() = dicts.forEach { assertEquals(null, it.entry(-1)) }
}
