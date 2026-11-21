package app.jisho

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jisho.core.database.AndroidWritableDb
import app.jisho.core.userdata.AnkiNote
import app.jisho.core.userdata.AnkiPackage
import app.jisho.core.userdata.Backup
import app.jisho.core.userdata.BackupFile
import app.jisho.core.userdata.UserStore
import app.jisho.core.userdata.WordRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Favorites, backup and Anki export on Android's own SQLite (the JVM tests use JDBC). Works in
 * scratch files only: the user's user.db is never touched.
 */
@RunWith(AndroidJUnit4::class)
class DeviceUserDataTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun scratch(name: String) = File(context.cacheDir, name).also { it.delete(); File(it.path + "-journal").delete() }

    @Test
    fun backupRestoresIntoAnEmptyStore() = runBlocking {
        val a = AndroidWritableDb(scratch("device-test-a.db")).let { UserStore(it) }
        val main = a.createFolder("単語")
        val folder = a.createFolder("N2")
        val word = WordRef("食べる", "たべる", "core:1358280")
        a.addFavorite(word, main)
        a.setFolders(word, setOf(main, folder))
        a.recordHistory(WordRef("本", "ほん", "core:1522150"))
        val bytes = BackupFile.write(Backup(a.dump(), mapOf("lemmatizer" to "Rules"), 1))

        // A fresh store stands in for a reinstalled app.
        val b = AndroidWritableDb(scratch("device-test-b.db")).let { UserStore(it) }
        b.replaceAll(BackupFile.read(bytes).data)
        assertEquals(a.folders(), b.folders())
        assertEquals(a.allFavorites(), b.allFavorites())
        assertEquals(a.history(), b.history())
    }

    /** Leaves the package in the app's files for tools/tests/check_anki.py (see AGENTS.md). */
    @Test
    fun ankiPackageBuildsWithAndroidSqlite() {
        val bytes = AnkiPackage.build(
            "辞書::device", listOf(AnkiNote("食べる", "たべる ②<br>食物をかんで、のみこむ。", "食べる|たべる")),
            scratch("device-anki.db"),
            openDb = { AndroidWritableDb(it) },
        )
        File(context.filesDir, "device-test.apkg").writeBytes(bytes)
    }
}
