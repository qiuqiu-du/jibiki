package app.jisho.core.userdata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A backup's contents: user data plus the settings worth keeping (key -> value). */
data class Backup(val data: UserData, val settings: Map<String, String>, val created: Long)

/**
 * The backup file (design-spec 6): a .zip of small tab-separated tables, readable by hand and
 * independent of user.db's layout. Dictionary packs are not included.
 *
 *   manifest.tsv   format, version, created
 *   folders.tsv    id, name (in the user's order; "" = the default folder)
 *   favorites.tsv  folder id, headword, reading, target, added
 *   history.tsv    headword, reading, target, viewed
 *   settings.tsv   key, value
 */
object BackupFile {
    const val FORMAT = "jisho-backup"
    const val VERSION = 1

    fun write(backup: Backup): ByteArray {
        val d = backup.data
        val files = linkedMapOf(
            "manifest.tsv" to listOf(listOf("format", FORMAT), listOf("version", VERSION.toString()), listOf("created", backup.created.toString())),
            "folders.tsv" to d.folders.map { listOf(it.id.toString(), it.name) },
            "favorites.tsv" to d.favorites.map {
                listOf(it.folderId.toString(), it.word.headword, it.word.reading, it.word.target, it.added.toString())
            },
            "history.tsv" to d.history.map { listOf(it.word.headword, it.word.reading, it.word.target, it.viewed.toString()) },
            "settings.tsv" to backup.settings.toSortedMap().map { (k, v) -> listOf(k, v) },
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, rows) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(rows.joinToString("") { r -> r.joinToString("\t") { escape(it) } + "\n" }.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Reads a backup; throws [IllegalArgumentException] for anything that isn't one. */
    fun read(bytes: ByteArray): Backup {
        val files = HashMap<String, List<List<String>>>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name.endsWith(".tsv") && e.size < MAX_FILE) {
                        files[e.name] = zip.readBytes().decodeToString().split('\n').filter { it.isNotEmpty() }
                            .map { line -> line.split('\t').map { unescape(it) } }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("not a zip file", e)
        }
        val manifest = files["manifest.tsv"]?.associate { it[0] to it.getOrElse(1) { "" } }
        require(manifest?.get("format") == FORMAT) { "not a backup file" }
        val version = manifest!!["version"]?.toIntOrNull() ?: 0
        require(version in 1..VERSION) { "backup from a newer version ($version)" }
        fun rows(name: String) = files[name].orEmpty()
        try {
            return Backup(
                UserData(
                    folders = rows("folders.tsv").map { Folder(it[0].toLong(), it[1], 0) },
                    favorites = rows("favorites.tsv").map { Favorite(it[0].toLong(), WordRef(it[1], it[2], it[3]), it[4].toLong()) },
                    history = rows("history.tsv").map { HistoryItem(WordRef(it[0], it[1], it[2]), it[3].toLong()) },
                ),
                settings = rows("settings.tsv").associate { it[0] to it.getOrElse(1) { "" } },
                created = manifest["created"]?.toLongOrNull() ?: 0,
            )
        } catch (e: RuntimeException) { // missing columns, bad numbers
            throw IllegalArgumentException("damaged backup file", e)
        }
    }

    private const val MAX_FILE = 64L shl 20

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                b.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> s[i + 1] })
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }
}
