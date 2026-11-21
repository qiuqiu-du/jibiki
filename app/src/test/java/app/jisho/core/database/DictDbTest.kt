package app.jisho.core.database

import app.jisho.testing.TestPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for dictionary packs: metadata, decompression, markup vocabulary, links, gaiji, alignment. */
class DictDbTest {
    private val dicts get() = TestPacks.sharedDicts

    @Before fun packs() = TestPacks.assumePresent()

    @Test
    fun allThreeDictionariesPresentWithCurrentFormats() {
        assertEquals(TestPacks.dictionaryIds, dicts.map { it.dictId })
        dicts.forEach {
            assertEquals(it.dictId, 2, it.schemaVersion)
            assertEquals(it.dictId, 1, it.bodyFormat)
            assertTrue(it.title.isNotBlank())
        }
    }

    /** Decompresses every entry once and checks the markup contract (Architecture.md §9). */
    @Test
    fun everyEntryDecompressesWithAllowedMarkupAndValidLinks() {
        val allowedTags = setOf("p", "br", "b", "i", "sup", "sub", "ruby", "rt", "span", "a", "img")
        val allowedSpans = setOf("pos", "sense", "ex", "label", "note", "mark", "box-b", "box-w", "num-c", "gloss", "zh")
        val tag = Regex("</?([a-zA-Z0-9-]+)")
        val span = Regex("<span class=\"([^\"]*)\"")
        val internalLink = Regex("<a href=\"#(\\d+)\"")
        val img = Regex("<img src=\"([^\"]+)\">")
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
                // Gaiji: every picture comes with the entry; none became a placeholder label.
                img.findAll(e.body).map { it.groupValues[1] }.filter { it !in e.images }
                    .forEach { problems += "picture $it missing in #$id" }
                if ("［〓］" in e.body || "［3分の2］" in e.body) problems += "gaiji placeholder in #$id"
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

    /** Section numbers are drawn by the app (大辞泉 あい【相】: black-box 一 and 二 before 名 and 接頭). */
    @Test
    fun sectionNumbersAreBadges() {
        val daijisen = dicts.first { it.dictId == "daijisen" }
        val ai = daijisen.allIds().asSequence().map { daijisen.entry(it)!! }.first { it.title.startsWith("あい〔あひ〕【相】") }
        assertTrue(ai.body, "<span class=\"box-b\">一</span>" in ai.body && "<span class=\"box-b\">二</span>" in ai.body && "<img" !in ai.body)
    }

    /** Black circled sense numbers are badges everywhere; white ①-㊿ are references to a sense and stay text. */
    @Test
    fun noCircledSenseNumbersLeftAsText() {
        val drawnElsewhere = Regex("[\u2776-\u277f\u24eb-\u24f4]") // ❶-❿, ⓫-⓴
        dicts.forEach { d ->
            val hits = d.allIds().filter { drawnElsewhere.containsMatchIn(d.entry(it)!!.body) }
            assertTrue("${d.dictId}: ${hits.take(5)} (${hits.size})", hits.isEmpty())
        }
    }
}
