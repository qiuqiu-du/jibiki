package app.jisho.core.data

import app.jisho.core.database.CoreDb
import app.jisho.core.database.DictDb
import app.jisho.testing.JdbcSqlDb
import app.jisho.testing.TestPacks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The app with no packs imported: needs only the bundled core.db, so it also runs in CI. */
class BundledCoreTest {
    @Before fun packs() = TestPacks.assumePresent()

    /** Only the app installed (bundled open core, no packs): search, inflections, accents and entry pages work. */
    @Test fun worksWithTheBundledCoreAlone() = runBlocking {
        val bare = object : PackOpener {
            override fun openCore() = CoreDb(JdbcSqlDb(TestPacks.bundledCore))
            override fun openDictionaries() = emptyList<DictDb>()
        }
        val r = DictionaryRepository(bare, { TestPacks.combined }, { TestPacks.kuromoji })
        assertEquals("食べる", r.search("食べなかった").words.first().headword)
        assertTrue(r.search("たべ").words.isNotEmpty())
        val e = r.entry(1358280)!!
        assertEquals("2", e.core.accents["たべる"])
        assertTrue(e.definitions.isEmpty())
        val s = r.summary("core:1358280", "たべる")
        assertEquals("2", s.accent)
        assertEquals("", s.definition)
        assertNotNull(r.entry(r.search("学校").words.first().entryId))
    }
}
