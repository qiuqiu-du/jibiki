package app.jisho.core.datapack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import app.jisho.core.data.PackOpener
import app.jisho.core.database.AndroidSqlDb
import app.jisho.core.database.CoreDb
import app.jisho.core.database.DictDb
import java.io.File

/**
 * Data packs live in the app's private files dir. They are copied once from a
 * folder the user picks (Storage Access Framework), e.g. Download/jisho.
 */
class DataPackManager(private val context: Context) : PackOpener {
    val dir: File = File(context.filesDir, "datapacks").apply { mkdirs() }
    val coreFile: File get() = File(dir, CORE)

    /** core.db is current and no dictionary pack is outdated (otherwise the import page shows). */
    fun isReady(): Boolean =
        coreFile.exists() && schemaVersion(coreFile) == CORE_SCHEMA_VERSION && outdatedDictionaries().isEmpty()

    fun dictionaryFiles(): List<File> {
        val files = dir.listFiles { f -> f.name.startsWith("dict_") && f.name.endsWith(".db") }.orEmpty()
        return files.sortedBy { f -> ORDER.indexOf(f.name).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    override fun openCore() = CoreDb(AndroidSqlDb(coreFile))

    /** Packs built for an older schema are skipped (re-import them); see [outdatedDictionaries]. */
    override fun openDictionaries() =
        dictionaryFiles().map { DictDb(AndroidSqlDb(it), it.nameWithoutExtension.removePrefix("dict_")) }
            .filter { d -> (d.schemaVersion == DICT_SCHEMA_VERSION).also { ok -> if (!ok) d.close() } }

    /** Dictionary packs that need re-importing because their schema is older than the app's. */
    fun outdatedDictionaries(): List<String> =
        dictionaryFiles().filter { schemaVersion(it) != DICT_SCHEMA_VERSION }.map { it.name }

    sealed interface Progress {
        data class Copying(val name: String, val index: Int, val total: Int) : Progress
        data object NoCore : Progress
        data class BadVersion(val name: String) : Progress
        data object Done : Progress
    }

    /** Copies core.db and dict_*.db from a picked tree. Blocking; call off the main thread. */
    fun importFromTree(treeUri: Uri, onProgress: (Progress) -> Unit) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        val packs = mutableListOf<Pair<String, Uri>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (name == CORE || (name.startsWith("dict_") && name.endsWith(".db"))) {
                    packs += name to DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
            }
        }
        if (packs.none { it.first == CORE }) { onProgress(Progress.NoCore); return }
        packs.sortBy { if (it.first == CORE) 0 else 1 }
        packs.forEachIndexed { i, (name, uri) ->
            onProgress(Progress.Copying(name, i + 1, packs.size))
            val tmp = File(dir, "$name.tmp")
            resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            val expected = if (name == CORE) CORE_SCHEMA_VERSION else DICT_SCHEMA_VERSION
            if (schemaVersion(tmp) != expected) {
                tmp.delete(); onProgress(Progress.BadVersion(name)); return
            }
            tmp.renameTo(File(dir, name))
        }
        onProgress(Progress.Done)
    }

    private fun schemaVersion(file: File): Int = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM meta WHERE key = 'schema_version'", null).use { c ->
                if (c.moveToFirst()) c.getString(0).toInt() else 0
            }
        }
    }.getOrDefault(0)

    companion object {
        const val CORE = "core.db"
        const val CORE_SCHEMA_VERSION = 4
        const val DICT_SCHEMA_VERSION = 2

        /** Default display order; the user's order (settings) comes first. */
        private val ORDER = listOf("dict_daijisen.db", "dict_daijirin4.db", "dict_meikyo3.db", "dict_shinmeikai8.db", "dict_shogakukan_jc3.db", "dict_shinseiki_jc.db")
    }
}
