package app.jisho.core.datapack

import app.jisho.testing.JdbcSqlDb
import app.jisho.testing.JdbcWritableDb
import app.jisho.testing.TestPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * core.db as the app builds it (design-spec 3.6): the bundled open core plus the packs'
 * accents (NHK, then 新明解, then 大辞林, Kanjium last) and 新明解's ranking scores.
 */
class CoreComposerTest {

    /** (headword, reading, first accent): checked against NHK and standard references. */
    private val pitch = listOf(
        Triple("食べる", "たべる", "2"), Triple("橋", "はし", "2"), Triple("箸", "はし", "1"), Triple("端", "はし", "0"),
        Triple("雨", "あめ", "1"), Triple("飴", "あめ", "0"), Triple("生物", "せいぶつ", "1"), Triple("昨日", "きのう", "2"),
        Triple("日本", "にほん", "2"), Triple("行く", "いく", "0"), Triple("読む", "よむ", "1"), Triple("先生", "せんせい", "3"),
        Triple("学校", "がっこう", "0"), Triple("東京", "とうきょう", "0"), Triple("綺麗", "きれい", "1"),
        Triple("大丈夫", "だいじょうぶ", "3"), Triple("花", "はな", "2"), Triple("鼻", "はな", "0"), Triple("箱", "はこ", "0"),
        Triple("犬", "いぬ", "2"), Triple("猫", "ねこ", "1"), Triple("山", "やま", "2"), Triple("川", "かわ", "2"),
        Triple("水", "みず", "0"), Triple("友達", "ともだち", "0"), Triple("勉強", "べんきょう", "0"),
        Triple("始める", "はじめる", "0"), Triple("見る", "みる", "1"), Triple("来る", "くる", "1"), Triple("為る", "する", "0"),
        Triple("言う", "いう", "0"), Triple("話す", "はなす", "2"), Triple("書く", "かく", "1"), Triple("赤い", "あかい", "0"),
        Triple("白い", "しろい", "2"), Triple("高い", "たかい", "2"), Triple("新しい", "あたらしい", "4"),
        Triple("電話", "でんわ", "0"), Triple("時計", "とけい", "0"), Triple("朝", "あさ", "1"), Triple("昼", "ひる", "2"),
        Triple("夜", "よる", "1"), Triple("今日", "きょう", "1"), Triple("明日", "あした", "3"),
        Triple("柿", "かき", "0"), Triple("牡蠣", "かき", "1"), Triple("神", "かみ", "1"), Triple("紙", "かみ", "2"),
        Triple("髪", "かみ", "2"),
    )

    private fun accents(core: File): List<String?> = JdbcSqlDb(core).use { db ->
        pitch.map { (head, reading) ->
            db.query(
                "SELECT p.accent FROM form f JOIN entry e ON e.id = f.entry_id JOIN pitch p ON p.entry_id = f.entry_id AND p.reading = f.reading " +
                    "WHERE (f.kanji = ?1 OR f.reading = ?1) AND f.reading = ?2 ORDER BY f.kanji = ?1 DESC, e.freq LIMIT 1",
                listOf(head, reading),
            ) { it.string(0).split(',')[0] }.firstOrNull()
        }
    }

    private fun composed(packs: List<String>): File = File.createTempFile("jisho-core", ".db").also { f ->
        f.deleteOnExit()
        TestPacks.bundledCore.copyTo(f, overwrite = true)
        JdbcWritableDb(f).use { CoreComposer.compose(it, packs.map { File(TestPacks.dir, it) }) }
    }

    private fun scores(db: JdbcSqlDb): Map<Long, Int> = db.query("SELECT id, score FROM entry") { it.long(0) to it.int(1) }.toMap()
    private fun bundledScores() = JdbcSqlDb(TestPacks.bundledCore).use { scores(it) }

    private fun presentPacks() = listOf("pitch_nhk2016.db", "dict_shinmeikai8.db", "dict_daijirin4.db").filter { File(TestPacks.dir, it).exists() }

    @Test fun everyPackGivesTheReferenceAccents() {
        TestPacks.assumePresent()
        val got = accents(TestPacks.coreFile)
        val wrong = pitch.zip(got).filter { (want, g) -> g != want.third }.map { (w, g) -> "${w.first}（${w.second}）: ${w.third} != $g" }
        assertEquals(emptyList<String>(), wrong)
    }

    /** The bundled core alone (Kanjium): what someone without any pack sees. Reported, and mostly right. */
    @Test fun bundledCoreAloneHasMostAccents() {
        TestPacks.assumePresent()
        val got = accents(TestPacks.bundledCore)
        val right = pitch.zip(got).count { (want, g) -> g == want.third }
        println("bundled core (Kanjium only): $right/${pitch.size} reference accents; missing ${got.count { it == null }}")
        pitch.zip(got).filter { (want, g) -> g != want.third }.forEach { (w, g) -> println("  ${w.first}（${w.second}）: ${w.third} != $g") }
        assertTrue(right >= pitch.size * 8 / 10)
    }

    /** Priority comes from the packs' meta, not from the order they are listed in. */
    @Test fun packOrderDoesNotMatter() {
        TestPacks.assumePresent()
        val packs = presentPacks()
        fun dump(f: File) = JdbcSqlDb(f).use { db ->
            db.query("SELECT entry_id || ':' || reading || '=' || accent || '/' || source FROM pitch ORDER BY entry_id, reading") { it.string(0) }.hashCode() to
                db.query("SELECT sum(rank) FROM search_key") { it.long(0) }.single()
        }
        assertEquals(dump(composed(packs)), dump(composed(packs.reversed())))
    }

    /** A dictionary turned off (left out of the packs) leaves no accent and no score behind. */
    @Test fun leftOutPackLeavesNothing() {
        TestPacks.assumePresent()
        val packs = presentPacks().filter { it != "dict_shinmeikai8.db" }
        JdbcSqlDb(composed(packs)).use { db ->
            assertEquals(0L, db.query("SELECT count(*) FROM pitch WHERE source = 'smk8'") { it.long(0) }.single())
            assertEquals(bundledScores(), scores(db))
        }
    }

    /** NHK wins over 新明解 and 大辞林; Kanjium only fills gaps; 新明解's ＊＊ words rank higher. */
    @Test fun higherPriorityWinsAndScoresImprove() {
        TestPacks.assumeDictionaries()
        JdbcSqlDb(TestPacks.coreFile).use { db ->
            val sources = db.query("SELECT source, count(*) FROM pitch GROUP BY source") { it.string(0) to it.long(1) }.toMap()
            println("composed pitch rows by source: $sources")
            assertTrue(sources.keys.containsAll(listOf("nhk2016", "kanjium")))
            // 新明解's ＊＊/＊ marks raise some words further than Jiten already did.
            val bundled = bundledScores()
            val improved = scores(db).count { (id, s) -> s < bundled.getValue(id) }
            println("scores raised by the 新明解 pack beyond the bundled core: $improved")
            if (File(TestPacks.dir, "dict_shinmeikai8.db").exists()) assertTrue("improved scores: $improved", improved > 100)
            // rank = score * 100 + spelling frequency, for improved words too
            val bad = db.query(
                "SELECT count(*) FROM search_key s JOIN entry e ON e.id = s.entry_id WHERE s.rank / 100 <> e.score",
            ) { it.long(0) }.single()
            assertEquals(0L, bad)
        }
    }
}
