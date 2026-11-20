package app.jisho.feature.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The word the web tabs look up for a dictionary item. */
class WebTabsTest {
    @Test fun titleWordTakesTheFirstSpelling() {
        assertEquals("食べる", titleWord("たべる【食べる】"))
        assertEquals("相", titleWord("あい〔あひ〕【相】"))
        assertEquals("生憎", titleWord("あい–にく【▽生憎】"))
        assertEquals("ああ", titleWord("ああ"))
        assertEquals("あ", titleWord("あ［五十音］"))
        assertEquals("浜寺", titleWord("はまでら【浜寺】"))
    }

    @Test fun spellingsAreKanjiFirstThenReadings() {
        // コトバンク files なめる under 嘗める and なめる, not 舐める: every spelling gets a try.
        val forms = listOf("舐める" to "なめる", "嘗める" to "なめる", "甞める" to "なめる")
        assertEquals(listOf("舐める", "嘗める", "甞める", "なめる"), webSpellings("舐める", forms))
        assertEquals(listOf("ああ"), webSpellings("ああ", listOf(null to "ああ")))
    }

    @Test fun titleReadingIsTheKanaPart() {
        assertEquals("たべる", titleReading("たべる【食べる】"))
        assertEquals("あい", titleReading("あい〔あひ〕【相】"))
        assertEquals("あいにく", titleReading("あい‐にく【▽生憎】"))
        assertNull(titleReading("ああ"))
    }
}
