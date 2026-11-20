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

    private val lemmaRepo by lazy { TestPacks.repositoryWithLemmatizer() }

    @Test fun inflectedInputShowsDictionaryFormFirst() = runBlocking {
        val first = lemmaRepo.search("食べなかった").words.first()
        assertEquals("食べる", first.headword)
        assertEquals(listOf("neg", "past"), first.inflection?.reasons)
        // Romaji goes through the same path.
        assertEquals("食べる", lemmaRepo.search("tabenakatta").words.first().headword)
    }

    @Test fun exactMatchStaysAboveLemmas() = runBlocking {
        val words = lemmaRepo.search("読める").words
        assertEquals("読める", words.first().headword)
        assertTrue(words.any { it.headword == "読む" && it.inflection?.reasons == listOf("potential") })
    }

    @Test fun ambiguousKanaListsEveryDictionaryForm() = runBlocking {
        // Usually-kana words show kana with their kanji beside it (いる 要る).
        val heads = lemmaRepo.search("いった").words.filter { it.inflection != null }.map { it.headword + " " + it.kanjiNote }
        for (h in listOf("行く", "言う", "要る")) assertTrue("いった -> $h in $heads", heads.any { h in it })
    }

    @Test fun definedDictionaryFormBeatsUndefinedExactMatch() = runBlocking {
        // JMdict has 信じられない as its own entry, but no dictionary defines it.
        val words = lemmaRepo.search("shinjirarenai").words
        assertEquals("信じる", words.first().headword)
        val undefined = words.first { it.headword == "信じられない" }
        val detail = lemmaRepo.entry(undefined.entryId)!!
        assertTrue(detail.definitions.all { it.second.isEmpty() })
        assertEquals("信じる", detail.baseForms.first().headword)
    }

    /** Across the whole test set, the first row of an inflected input always has a definition. */
    @Test fun firstRowOfInflectedInputHasDefinition() = runBlocking {
        val inputs = TestPacks.resourceLines("lemma_cases.tsv").map { it.substringBefore('\t') }.distinct()
        val empty = inputs.filter { q ->
            val first = lemmaRepo.search(q).words.firstOrNull() ?: return@filter true
            lemmaRepo.entry(first.entryId)!!.definitions.all { it.second.isEmpty() }
        }
        assertTrue("first row without definition: $empty", empty.isEmpty())
    }

    /** JMdict search-only spellings (sK): findable, never shown, and no obstacle to linking. */
    @Test fun searchOnlySpellingIsHiddenButSearchable() = runBlocking {
        val row = repo.search("居らっしゃる").words.first()
        assertEquals("いらっしゃる", row.headword)
        val detail = repo.entry(row.entryId)!!
        assertTrue(detail.core.forms.none { it.kanji == "居らっしゃる" })
        assertTrue("いらっしゃる has definitions", detail.definitions.count { it.second.isNotEmpty() } == 3)
    }

    @Test fun singleKanaIsNotLemmatized() = runBlocking {
        assertTrue(lemmaRepo.search("い").words.none { it.inflection != null })
    }

    @Test fun sentenceIsSplitIntoWords() = runBlocking {
        val r = lemmaRepo.search("昨日は本を読まなかった")
        assertEquals(listOf("昨日", "本", "読まなかった"), r.segments.map { it.surface })
        assertEquals(emptyList<Any>(), lemmaRepo.search("食べる").segments) // a word, not a sentence
        assertEquals(emptyList<Any>(), lemmaRepo.search("食べなかった").segments) // one inflected word
    }

    /** A word plus an auxiliary verb is two words: the word bar offers both. */
    @Test fun auxiliaryVerbGoesToWordBar() = runBlocking {
        for ((q, lookups) in listOf(
            "食べてくる" to listOf("食べる", "くる"),
            "決めかねる" to listOf("決める", "かねる"),
            "読み終わった" to listOf("読む", "終わる"),
        )) {
            val r = lemmaRepo.search(q)
            assertEquals(q, lookups, r.segments.map { it.lookup })
            // Not an inflection the rule table explains, so no lemma row (and never one without labels).
            assertTrue(q, r.words.none { it.inflection != null })
        }
        // An inflection the rule table explains gets a lemma row instead of the word bar.
        val r = lemmaRepo.search("食べている")
        assertTrue(r.words.any { it.headword == "食べる" && it.inflection != null })
        assertEquals(emptyList<Any>(), r.segments)
    }

    /** A word plus a particle is not an inflection: no lemma row, but the word bar offers the word. */
    @Test fun wordWithParticleGoesToWordBar() = runBlocking {
        val r = lemmaRepo.search("食べますよ")
        assertTrue(r.words.none { it.inflection != null })
        assertEquals(listOf("食べます"), r.segments.map { it.surface })
    }

    @Test fun lemmaRowsStayDistinguishable() = runBlocking {
        for (q in listOf("いった", "かった", "きた", "した", "よんだ", "あった")) {
            val rows = lemmaRepo.search(q).words
            val dup = rows.groupBy { it.displayKey }.filterValues { it.size > 1 }.keys
            assertTrue("$q: identical rows $dup", dup.isEmpty())
        }
    }

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

    /** Dictionary settings: entries follow the user's order; a turned-off dictionary disappears everywhere. */
    @Test fun dictionaryOrderAndOnOff() = runBlocking {
        var prefs = DictPrefs(order = listOf("shinmeikai8", "daijisen"))
        val r = DictionaryRepository(TestPacks.opener(), prefs = { prefs })
        // Named ones first in the given order, the rest after them in default order.
        assertEquals(listOf("shinmeikai8", "daijisen", "meikyo3"), r.entry(1358280)!!.definitions.map { it.first.id })
        assertEquals(listOf("shinmeikai8", "daijisen", "meikyo3"), r.dictionaries(prefs).map { it.info.id })

        prefs = DictPrefs(disabled = setOf("daijisen"))
        assertEquals(listOf("meikyo3", "shinmeikai8"), r.entry(1358280)!!.definitions.map { it.first.id })
        assertEquals(listOf(false, true, true), r.dictionaries(prefs).map { it.enabled }) // default order
        // Daijisen-only items (浜寺) are no longer offered.
        assertTrue(r.search("はまでら").dictOnly.none { it.dictId == "daijisen" })
        // A link inside daijisen still opens.
        assertNotNull(r.dictEntry("daijisen", 1))
    }
}
