package app.jisho.core.userdata

import app.jisho.core.database.WritableDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * A word the user saved or looked up. [headword] and [reading] identify it, so it survives a
 * dictionary update; [target] says what to open: "core:<JMdict id>", "dict:<dict id>:<item id>"
 * or "lookup:<text>" (an imported word not matched yet).
 */
data class WordRef(val headword: String, val reading: String, val target: String)

data class Folder(val id: Long, val name: String, val count: Int)

data class Favorite(val folderId: Long, val word: WordRef, val added: Long)

data class HistoryItem(val word: WordRef, val viewed: Long)

/** Everything a backup holds besides settings. Folder ids are only meaningful within one copy. */
data class UserData(
    val folders: List<Folder>,
    val favorites: List<Favorite>,
    val history: List<HistoryItem>,
)

enum class FavoriteSort { Added, Kana }

/**
 * The user's own data (design-spec 6): folders, favorites (a word may be in several folders)
 * and lookup history, in user.db. There is no built-in folder: the user makes them all.
 * Every write bumps [version], which screens watch to reload.
 *
 * [unnamed]: name given to folders without one (the first version had a nameless built-in
 * "default" folder; it becomes an ordinary folder with this name).
 */
class UserStore(
    private val db: WritableDb,
    private val unnamed: String = "默认",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    init {
        db.transaction {
            SCHEMA.forEach { db.exec(it) }
            nameUnnamed()
            db.exec("INSERT OR IGNORE INTO meta (key, value) VALUES ('schema_version', ?)", listOf(SCHEMA_VERSION.toString()))
        }
    }

    private suspend fun <T> read(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun <T> write(block: () -> T): T = withContext(Dispatchers.IO) {
        db.transaction(block).also { _version.update { it + 1 } }
    }

    private fun nameUnnamed() = db.exec("UPDATE folder SET name = ? WHERE name = ''", listOf(unnamed))

    // --- Folders ---

    /** All folders in the user's order, with their word counts. */
    suspend fun folders(): List<Folder> = read {
        db.query(
            "SELECT f.id, f.name, (SELECT COUNT(*) FROM favorite WHERE folder_id = f.id) FROM folder f ORDER BY f.ord, f.id",
        ) { Folder(it.long(0), it.string(1), it.int(2)) }
    }

    suspend fun createFolder(name: String): Long = write { insertFolder(name) }

    private fun insertFolder(name: String): Long {
        val ord = db.query("SELECT COALESCE(MAX(ord), 0) + 1 FROM folder") { it.long(0) }.first()
        db.exec("INSERT INTO folder (name, ord, created) VALUES (?, ?, ?)", listOf(name, ord, now()))
        return db.query("SELECT last_insert_rowid()") { it.long(0) }.first()
    }

    suspend fun renameFolder(id: Long, name: String) = write {
        db.exec("UPDATE folder SET name = ? WHERE id = ?", listOf(name, id))
    }

    /** Deletes a folder and its links; the words stay in their other folders. */
    suspend fun deleteFolder(id: Long) = write {
        db.exec("DELETE FROM favorite WHERE folder_id = ?", listOf(id))
        db.exec("DELETE FROM folder WHERE id = ?", listOf(id))
    }

    /** Moves a folder [delta] places up (-1) or down (+1) in the list. */
    /** Puts the folders in the order of [ids] (as dragged in the list); folders it leaves out go last. */
    suspend fun reorderFolders(ids: List<Long>) = write {
        val all = db.query("SELECT id FROM folder ORDER BY ord, id") { it.long(0) }
        (ids.filter { it in all } + all.filter { it !in ids }).forEachIndexed { ord, f ->
            db.exec("UPDATE folder SET ord = ? WHERE id = ?", listOf(ord, f))
        }
    }

    // --- Favorites ---

    /**
     * Rows that are [word]: the same headword and reading, or the same entry ([WordRef.target]).
     * The second catches words saved under another spelling or reading of the entry, as imports
     * often are (ビビンバ saved as びびんば, or with an accent mark in the reading).
     */
    private val sameWord = "((headword = ? AND reading = ?) OR target = ?)"
    private fun sameWordArgs(word: WordRef) = listOf(word.headword, word.reading, word.target)

    /** Folders [word] is in. */
    suspend fun foldersOf(word: WordRef): Set<Long> = read { foldersNow(word) }

    private fun foldersNow(word: WordRef): Set<Long> =
        db.query("SELECT folder_id FROM favorite WHERE $sameWord", sameWordArgs(word)) { it.long(0) }.toSet()

    /** The reading [headword] was saved with as a plain look-up ([WordRef.target] "lookup:…"), if any. */
    suspend fun lookupReading(headword: String): String? = read {
        db.query("SELECT reading FROM favorite WHERE headword = ? AND target = ? ORDER BY added LIMIT 1", listOf(headword, "lookup:$headword")) { it.string(0) }.firstOrNull()
    }

    /** Puts [word] in exactly [folderIds]: new links get the current time, existing ones keep theirs. */
    suspend fun setFolders(word: WordRef, folderIds: Set<Long>) = write {
        val current = foldersNow(word)
        (current - folderIds).forEach { db.exec("DELETE FROM favorite WHERE folder_id = ? AND $sameWord", listOf(it) + sameWordArgs(word)) }
        (folderIds - current).forEach { insertFavorite(it, word, now()) }
    }

    suspend fun addFavorite(word: WordRef, folderId: Long) = write { insertFavorite(folderId, word, now()) }

    /** The folder the user saved to most recently (where a quick tap on the star saves), or null. */
    suspend fun lastUsedFolder(): Long? = read {
        db.query("SELECT folder_id FROM favorite ORDER BY added DESC LIMIT 1") { it.long(0) }.firstOrNull()
    }

    private fun insertFavorite(folderId: Long, word: WordRef, added: Long) = db.exec(
        "INSERT OR IGNORE INTO favorite (folder_id, headword, reading, target, added) VALUES (?, ?, ?, ?, ?)",
        listOf(folderId, word.headword, word.reading, word.target, added),
    )

    private fun deleteFavorite(folderId: Long, headword: String, reading: String) = db.exec(
        "DELETE FROM favorite WHERE folder_id = ? AND headword = ? AND reading = ?", listOf(folderId, headword, reading),
    )

    /** The words of one folder; [FavoriteSort.Kana] orders by reading (gojūon), then headword. */
    suspend fun favorites(folderId: Long, sort: FavoriteSort = FavoriteSort.Added): List<Favorite> = read {
        val order = when (sort) {
            FavoriteSort.Added -> "added DESC, headword"
            FavoriteSort.Kana -> "reading, headword"
        }
        db.query(
            "SELECT folder_id, headword, reading, target, added FROM favorite WHERE folder_id = ? ORDER BY $order",
            listOf(folderId.toString()),
        ) { it.toFavorite() }
    }

    /** Every favorite of the given folders (all folders if null), folder by folder. */
    suspend fun allFavorites(folderIds: Set<Long>? = null): List<Favorite> = read { allFavoritesNow(folderIds) }

    private fun allFavoritesNow(folderIds: Set<Long>?): List<Favorite> =
        db.query(
            "SELECT fav.folder_id, fav.headword, fav.reading, fav.target, fav.added FROM favorite fav " +
                "JOIN folder f ON f.id = fav.folder_id ORDER BY f.ord, f.id, fav.added",
        ) { it.toFavorite() }.filter { folderIds == null || it.folderId in folderIds }

    private fun app.jisho.core.database.SqlRow.toFavorite() =
        Favorite(long(0), WordRef(string(1), string(2), string(3)), long(4))

    suspend fun removeFavorites(folderId: Long, words: Collection<WordRef>) = write {
        words.forEach { deleteFavorite(folderId, it.headword, it.reading) }
    }

    /** Puts removed favorites back as they were (folder and time added): undo. */
    suspend fun restoreFavorites(favorites: Collection<Favorite>) = write {
        favorites.forEach { insertFavorite(it.folderId, it.word, it.added) }
    }

    /** Moves words from one folder to another, keeping when they were added. */
    suspend fun moveFavorites(from: Long, to: Long, words: Collection<WordRef>) = write {
        if (from == to) return@write
        words.forEach { w ->
            val added = db.query(
                "SELECT added FROM favorite WHERE folder_id = ? AND headword = ? AND reading = ?",
                listOf(from.toString(), w.headword, w.reading),
            ) { it.long(0) }.firstOrNull() ?: now()
            deleteFavorite(from, w.headword, w.reading)
            insertFavorite(to, w, added)
        }
    }

    /**
     * Adds imported favorites to the folders they name, creating folders that don't exist yet. Words already in a folder are skipped. Returns how many were added.
     */
    suspend fun importFavorites(items: List<Pair<String, Favorite>>): Int = write {
        val byName = db.query("SELECT id, name FROM folder") { it.string(1) to it.long(0) }.toMap().toMutableMap()
        var added = 0
        items.forEach { (folderName, item) ->
            val fav = item.copy(word = item.word.copy(reading = cleanReading(item.word.reading)))
            val name = folderName.ifEmpty { unnamed }
            val folder = byName.getOrPut(name) { insertFolder(name) }
            val exists = db.query(
                "SELECT 1 FROM favorite WHERE folder_id = ? AND headword = ? AND reading = ?",
                listOf(folder.toString(), fav.word.headword, fav.word.reading),
            ) { 1 }.isNotEmpty()
            if (!exists) {
                insertFavorite(folder, fav.word, fav.added)
                added++
            }
        }
        added
    }

    // --- History ---

    /** Records a look-up; a word looked up again moves to the top. Keeps the latest [HISTORY_LIMIT]. */
    suspend fun recordHistory(word: WordRef) = write {
        db.exec(
            "INSERT OR REPLACE INTO history (headword, reading, target, viewed) VALUES (?, ?, ?, ?)",
            listOf(word.headword, word.reading, word.target, now()),
        )
        db.exec(
            "DELETE FROM history WHERE rowid NOT IN (SELECT rowid FROM history ORDER BY viewed DESC LIMIT ?)",
            listOf(HISTORY_LIMIT),
        )
    }

    suspend fun history(limit: Int = HISTORY_LIMIT): List<HistoryItem> = read { historyNow(limit) }

    private fun historyNow(limit: Int) = db.query(
        "SELECT headword, reading, target, viewed FROM history ORDER BY viewed DESC LIMIT ?", listOf(limit.toString()),
    ) { HistoryItem(WordRef(it.string(0), it.string(1), it.string(2)), it.long(3)) }

    suspend fun removeHistory(word: WordRef) = write {
        db.exec("DELETE FROM history WHERE headword = ? AND reading = ?", listOf(word.headword, word.reading))
    }

    suspend fun clearHistory() = write { db.exec("DELETE FROM history") }

    // --- Backup ---

    /** A full copy of the user's data (for backups). */
    suspend fun dump(): UserData = read {
        db.transaction {
            UserData(
                db.query("SELECT id, name, ord FROM folder ORDER BY ord, id") { Folder(it.long(0), it.string(1), 0) },
                allFavoritesNow(null),
                historyNow(HISTORY_LIMIT),
            )
        }
    }

    /** Replaces all user data with [data] (restoring a backup); folders keep their order. */
    suspend fun replaceAll(data: UserData) = write {
        db.exec("DELETE FROM favorite")
        db.exec("DELETE FROM history")
        db.exec("DELETE FROM folder")
        data.folders.forEachIndexed { ord, f ->
            db.exec("INSERT INTO folder (id, name, ord, created) VALUES (?, ?, ?, ?)", listOf(f.id, f.name, ord, now()))
        }
        nameUnnamed()
        // Readings are cleaned as they come in (an old backup may carry pitch marks).
        data.favorites.forEach { insertFavorite(it.folderId, it.word.copy(reading = cleanReading(it.word.reading)), it.added) }
        data.history.forEach {
            db.exec(
                "INSERT OR REPLACE INTO history (headword, reading, target, viewed) VALUES (?, ?, ?, ?)",
                listOf(it.word.headword, cleanReading(it.word.reading), it.word.target, it.viewed),
            )
        }
    }

    companion object {
        const val HISTORY_LIMIT = 500

        /** Circled numbers: pitch accents other apps write after a reading (MOJi: 「おなに ⓪」). */
        private val PITCH_MARK = Regex("[\u24EA\u2460-\u2473\u24F5-\u24FE\u3251-\u325F\u32B1-\u32BF]")

        /** A reading as the app keeps it: no pitch marks, single spaces, trimmed. */
        fun cleanReading(reading: String): String =
            reading.replace(PITCH_MARK, " ").replace(Regex("\\s+"), " ").trim()
        const val SCHEMA_VERSION = 1

        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS folder (id INTEGER PRIMARY KEY, name TEXT NOT NULL, ord INTEGER NOT NULL, created INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS favorite (folder_id INTEGER NOT NULL, headword TEXT NOT NULL, reading TEXT NOT NULL, " +
                "target TEXT NOT NULL, added INTEGER NOT NULL, PRIMARY KEY (folder_id, headword, reading))",
            "CREATE INDEX IF NOT EXISTS favorite_word ON favorite (headword, reading)",
            "CREATE TABLE IF NOT EXISTS history (headword TEXT NOT NULL, reading TEXT NOT NULL, target TEXT NOT NULL, " +
                "viewed INTEGER NOT NULL, PRIMARY KEY (headword, reading))",
            "CREATE INDEX IF NOT EXISTS history_viewed ON history (viewed)",
        )
    }
}
