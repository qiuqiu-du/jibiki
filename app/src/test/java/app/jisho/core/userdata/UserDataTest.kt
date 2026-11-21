package app.jisho.core.userdata

import app.jisho.testing.JdbcWritableDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

/** Favorites, history, CSV, Anki and backup files (design-spec 6). */
class UserDataTest {
    private var clock = 1_000L
    private fun store(db: JdbcWritableDb = JdbcWritableDb()) = UserStore(db, "默认") { clock++ }

    private val taberu = WordRef("食べる", "たべる", "core:1358280")
    private val nameru = WordRef("舐める", "なめる", "core:1571320")
    private val hon = WordRef("本", "ほん", "core:1522150")

    @Test fun noFolderUntilTheUserMakesOne() = runBlocking {
        val s = store()
        assertTrue(s.folders().isEmpty())
        assertEquals(null, s.lastUsedFolder())
        val a = s.createFolder("A")
        s.deleteFolder(a) // any folder can be deleted
        assertTrue(s.folders().isEmpty())
    }

    /** The first version's nameless built-in folder becomes an ordinary folder named 默认. */
    @Test fun oldNamelessFolderGetsAName() = runBlocking {
        val db = JdbcWritableDb()
        store(db)
        db.exec("INSERT INTO folder (id, name, ord, created) VALUES (1, '', 0, 0)")
        db.exec("INSERT INTO favorite VALUES (1, '食べる', 'たべる', 'core:1358280', 5)")
        val s = store(db)
        assertEquals(listOf("默认" to 1), s.folders().map { it.name to it.count })
        s.renameFolder(1, "N5"); s.deleteFolder(1)
        assertTrue(s.folders().isEmpty())
    }

    @Test fun aWordCanBeInSeveralFolders() = runBlocking {
        val s = store()
        val basic = s.createFolder("基本")
        val verbs = s.createFolder("動詞")
        s.addFavorite(taberu, basic)
        assertEquals(basic, s.lastUsedFolder())
        s.setFolders(taberu, setOf(basic, verbs))
        assertEquals(setOf(basic, verbs), s.foldersOf(taberu))
        // Deleting a folder only removes that link.
        s.deleteFolder(verbs)
        assertEquals(setOf(basic), s.foldersOf(taberu))
        s.setFolders(taberu, emptySet())
        assertTrue(s.foldersOf(taberu).isEmpty())
    }

    /** A word saved under another reading of the same entry (imports) still counts as saved. */
    @Test fun sameEntryCountsAsTheSameWord() = runBlocking {
        val s = store()
        val a = s.createFolder("A")
        val b = s.createFolder("B")
        s.addFavorite(WordRef("ビビンバ", "びびんば", "core:2015180"), a)
        val shown = WordRef("ビビンバ", "ビビンバ", "core:2015180")
        assertEquals(setOf(a), s.foldersOf(shown))
        s.setFolders(shown, setOf(b)) // unsaving from A removes the imported row
        assertEquals(setOf(b), s.foldersOf(shown))
        assertEquals(listOf("ビビンバ"), s.favorites(b).map { it.word.reading })
        assertTrue(s.favorites(a).isEmpty())
    }

    /** A saved look-up (no offline entry) is found again by its spelling, with the reading it was saved with. */
    @Test fun savedLookupKeepsItsReading() = runBlocking {
        val s = store()
        s.addFavorite(WordRef("お疲れ様です", "おつかれさまです", "lookup:お疲れ様です"), s.createFolder("A"))
        assertEquals("おつかれさまです", s.lookupReading("お疲れ様です"))
        assertEquals(null, s.lookupReading("食べる"))
    }

    /** Pitch marks other apps put after a reading are dropped on import and from rows saved earlier. */
    @Test fun readingsLoseTheirPitchMarks() = runBlocking {
        assertEquals("おなに", UserStore.cleanReading("おなに ⓪"))
        assertEquals("くんに", UserStore.cleanReading("くんに①"))
        assertEquals("〜ようが・〜ようと（も）", UserStore.cleanReading("〜ようが・〜ようと（も）"))
        val db = JdbcWritableDb()
        val s = store(db)
        val a = s.createFolder("A")
        assertEquals(1, s.importFavorites(listOf("A" to Favorite(0, WordRef("クンニ", "くんに ①", "core:2068460"), 5))))
        assertEquals(listOf("くんに"), s.favorites(a).map { it.word.reading })
        // A backup from before the rule: cleaned as it is restored; a duplicate of a clean row goes.
        val old = s.dump().let { d ->
            d.copy(
                favorites = d.favorites + listOf(
                    Favorite(a, WordRef("オナニ", "おなに ⓪", "core:1034490"), 7),
                    Favorite(a, WordRef("クンニ", "くんに ①", "core:2068460"), 6),
                ),
                history = listOf(HistoryItem(WordRef("オナニ", "おなに ⓪", "core:1034490"), 8)),
            )
        }
        s.replaceAll(old)
        assertEquals(listOf("おなに" to 7L, "くんに" to 5L), s.favorites(a).map { it.word.reading to it.added })
        assertEquals(listOf("おなに"), s.history().map { it.word.reading })
    }

    @Test fun foldersKeepTheirOrderAndCounts() = runBlocking {
        val s = store()
        val a = s.createFolder("A")
        val b = s.createFolder("B")
        s.addFavorite(taberu, b)
        val c = s.createFolder("C")
        s.reorderFolders(listOf(c, b)) // dragged: the rest keep their order after
        assertEquals(listOf(c, b, a), s.folders().map { it.id })
        assertEquals(1, s.folders().first { it.id == b }.count)
        s.renameFolder(a, "AA")
        assertEquals("AA", s.folders().first { it.id == a }.name)
    }

    @Test fun favoritesSortByTimeOrKanaAndMove() = runBlocking {
        val s = store()
        val main = s.createFolder("主")
        listOf(taberu, nameru, hon).forEach { s.addFavorite(it, main) }
        assertEquals(listOf("本", "舐める", "食べる"), s.favorites(main).map { it.word.headword }) // newest first
        assertEquals(listOf("たべる", "なめる", "ほん"), s.favorites(main, FavoriteSort.Kana).map { it.word.reading })
        val other = s.createFolder("其他")
        val added = s.favorites(main).first { it.word == hon }.added
        s.moveFavorites(main, other, listOf(hon))
        assertEquals(listOf(hon), s.favorites(other).map { it.word })
        assertEquals(added, s.favorites(other).single().added) // keeps its time
        val removed = s.favorites(main).filter { it.word == taberu }
        s.removeFavorites(main, listOf(taberu))
        assertEquals(listOf(nameru), s.favorites(main).map { it.word })
        s.restoreFavorites(removed) // undo: back with its time
        assertEquals(removed, s.favorites(main).filter { it.word == taberu })
        s.removeFavorites(main, listOf(taberu))
    }

    @Test fun historyMovesRepeatsToTheTopAndCanBeCleared() = runBlocking {
        val s = store()
        s.recordHistory(taberu); s.recordHistory(hon); s.recordHistory(taberu)
        assertEquals(listOf(taberu, hon), s.history().map { it.word })
        s.removeHistory(hon)
        assertEquals(listOf(taberu), s.history().map { it.word })
        s.clearHistory()
        assertTrue(s.history().isEmpty())
    }

    @Test fun csvRoundTripsThroughImport() = runBlocking {
        val s = store()
        val words = s.createFolder("単語")
        val verbs = s.createFolder("動詞, 基本") // a comma, quoted in CSV
        s.addFavorite(taberu, words); s.setFolders(nameru, setOf(verbs))
        val names = s.folders().associate { it.id to it.name }
        val rows = s.allFavorites().map { ExportRow(names.getValue(it.folderId), it, "2", "食物を口に入れ、\"かんで\"のみこむ。") }
        val text = FavoritesCsv.export(rows, ZoneOffset.UTC)
        assertTrue(text.startsWith("﻿"))
        val parsed = FavoritesCsv.parse(text, ZoneOffset.UTC)
        assertEquals(listOf("単語", "動詞, 基本"), parsed.map { it.folder })
        assertEquals(listOf("core:1358280", "core:1571320"), parsed.map { it.target })
        // Times are kept to the minute.
        assertEquals(rows.map { it.favorite.added / 60_000 * 60_000 }, parsed.map { it.added })

        // Importing into an empty store recreates the folders; importing again adds nothing.
        val t = store()
        val items = parsed.map { r -> r.folder to Favorite(0, WordRef(r.headword, r.reading, r.target), r.added ?: 0) }
        assertEquals(2, t.importFavorites(items))
        assertEquals(0, t.importFavorites(items))
        assertEquals(listOf("単語", "動詞, 基本"), t.folders().map { it.name })
        // Rows without a folder go to one named 默认.
        assertEquals(1, t.importFavorites(listOf("" to Favorite(0, hon, 1))))
        assertEquals("默认", t.folders().last().name)
    }

    @Test fun csvFromElsewhereNeedsOnlyAHeadwordColumn() {
        val rows = FavoritesCsv.parse("Headword,Reading,Note\r\n犬,いぬ,x\n\n猫,,\n")
        assertEquals(listOf("犬", "猫"), rows.map { it.headword })
        assertEquals(listOf("いぬ", ""), rows.map { it.reading })
        assertTrue(rows.all { it.folder.isEmpty() && it.target.isEmpty() && it.added == null })
        assertTrue(runCatching { FavoritesCsv.parse("word\n犬\n") }.isFailure)
    }

    @Test fun backupRestoresEverything() = runBlocking {
        val s = store()
        val main = s.createFolder("主")
        val f = s.createFolder("N1\t単語") // a tab survives the TSV
        s.addFavorite(taberu, main); s.setFolders(nameru, setOf(main, f)); s.recordHistory(hon)
        val settings = mapOf("lemmatizer" to "Rules", "dict_order" to "meikyo3,daijisen")
        val bytes = BackupFile.write(Backup(s.dump(), settings, created = 42))
        val back = BackupFile.read(bytes)
        assertEquals(settings, back.settings)
        assertEquals(42L, back.created)

        val t = store()
        t.addFavorite(hon, t.createFolder("消える")) // replaced by the restore
        t.replaceAll(back.data)
        assertEquals(s.folders(), t.folders())
        assertEquals(s.allFavorites(), t.allFavorites())
        assertEquals(s.history(), t.history())
    }

    /** Excel on Windows saves "CSV" in the system code page: GBK (Chinese) or Shift-JIS (Japanese). */
    @Test fun ansiCsvIsReadInEitherCodePage() {
        val text = "folder,headword,reading\r\n日语动词,食べる,たべる\r\n単語帳,舐める,なめる\r\n"
        for (cs in listOf("GBK", "Shift_JIS")) {
            // Shift-JIS can't hold 语/动; keep the parts each code page can write.
            val sample = if (cs == "GBK") text else text.replace("日语动词", "日本語")
            val bytes = sample.toByteArray(charset(cs))
            assertEquals(cs, sample, TextEncoding.decode(bytes, CsvEncoding.Ansi))
            // Picking UTF-8 for an ANSI file still works (it isn't valid UTF-8).
            assertEquals(cs, sample, TextEncoding.decode(bytes, CsvEncoding.Utf8))
        }
        // A UTF-8 file read as "ANSI" stays UTF-8 (with or without a byte-order mark).
        assertEquals(text, TextEncoding.decode(text.toByteArray(), CsvEncoding.Ansi))
        assertEquals(text, TextEncoding.decode(("\uFEFF" + text).toByteArray(), CsvEncoding.Ansi))
        // Kana-only words in GBK (GB2312 has kana too).
        val kana = "headword\r\nたべる\r\nカタカナ\r\n"
        assertEquals(kana, TextEncoding.decode(kana.toByteArray(charset("GBK")), CsvEncoding.Ansi))
        assertEquals(kana, TextEncoding.decode(kana.toByteArray(charset("Shift_JIS")), CsvEncoding.Ansi))
    }

    @Test fun otherFilesAreNotBackups() {
        assertTrue(runCatching { BackupFile.read("hello".toByteArray()) }.isFailure)
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { it.putNextEntry(java.util.zip.ZipEntry("x.txt")); it.write(1) }
        }.toByteArray()
        assertTrue(runCatching { BackupFile.read(zip) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun ankiPackageHoldsACollection() {
        val work = File.createTempFile("anki", ".db")
        val notes = listOf(AnkiNote("食べる", "たべる <b>②</b><br>食物を口に入れ…", "core:1358280"), AnkiNote("本", "ほん", "core:1522150"))
        val bytes = AnkiPackage.build("辞書::默认", notes, work, { JdbcWritableDb(it) })
        val names = ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("collection.anki2", "media"), names)
        assertFalse(work.exists())
        // The full check (Anki itself importing it) is tools/tests/check_anki.py.
        File(System.getProperty("java.io.tmpdir"), "jisho-test.apkg").writeBytes(bytes)
    }
}
