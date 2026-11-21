package app.jisho.core.datapack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import app.jisho.core.data.PackOpener
import app.jisho.core.database.AndroidSqlDb
import app.jisho.core.database.AndroidWritableDb
import app.jisho.core.database.CoreDb
import app.jisho.core.database.DictDb
import com.github.luben.zstd.ZstdInputStream
import java.io.File

/**
 * Data packs live in the app's private files dir (design-spec 3.6):
 * - core.db comes with the app (assets/core.db.zst, open data only) and is rebuilt there
 *   whenever the app or the set of packs changes: [prepareCore] merges the packs' accents and
 *   ranking scores into it ([CoreComposer]).
 * - dict_*.db (dictionaries) and pitch_*.db (accent-only, NHK) are optional, copied once from
 *   a folder the user picks (Storage Access Framework), e.g. Download/jisho.
 */
class DataPackManager(private val context: Context) : PackOpener {
    val dir: File = File(context.filesDir, "datapacks").apply { mkdirs() }
    val coreFile: File get() = File(dir, CORE)
    /** What [coreFile] was built from; see [signature]. */
    private val stampFile: File get() = File(dir, "core.stamp")
    /** Dictionaries turned off in settings: their accents and ranking scores stay out of core.db. */
    private val disabledFile: File get() = File(dir, "core.disabled")

    /** Search works: core.db is built for this app and these packs, and no pack is outdated. */
    fun isReady(): Boolean = isCoreCurrent() && outdatedDictionaries().isEmpty()

    /** Cheap (file names, sizes and dates only): safe on the main thread at startup. */
    fun isCoreCurrent(): Boolean = coreFile.exists() && stampFile.exists() && stampFile.readText() == signature()

    fun dictionaryFiles(): List<File> {
        val files = dir.listFiles { f -> f.name.startsWith("dict_") && f.name.endsWith(".db") }.orEmpty()
        return files.sortedBy { f -> ORDER.indexOf(f.name).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    /** The dictionaries whose accents and scores are left out of core.db (see [setDisabled]). */
    fun disabled(): Set<String> =
        disabledFile.takeIf { it.exists() }?.readText()?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /**
     * Follows the settings' on/off state: a dictionary turned off is not consulted for accents or
     * ranking either. Returns true when that changed, and core.db then needs [prepareCore].
     */
    fun setDisabled(ids: Set<String>): Boolean {
        if (ids == disabled()) return false
        disabledFile.writeText(ids.sorted().joinToString(","))
        return true
    }

    /** Accent-only packs (pitch_nhk2016.db). */
    fun pitchFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith("pitch_") && f.name.endsWith(".db") }.orEmpty().sortedBy { it.name }

    override fun openCore() = CoreDb(AndroidSqlDb(coreFile))

    /** Packs built for an older schema are skipped (re-import them); see [outdatedDictionaries]. */
    override fun openDictionaries() =
        dictionaryFiles().map { DictDb(AndroidSqlDb(it), it.nameWithoutExtension.removePrefix("dict_")) }
            .filter { d -> (d.schemaVersion == DICT_SCHEMA_VERSION).also { ok -> if (!ok) d.close() } }

    /** Packs that need re-importing because their schema is older than the app's. */
    fun outdatedDictionaries(): List<String> =
        (dictionaryFiles() + pitchFiles()).filter { schemaVersion(it) != DICT_SCHEMA_VERSION }.map { it.name }

    /**
     * Rebuilds core.db from the bundled copy and the current packs, unless it is current.
     * About a second; call off the main thread, before anything opens core.db.
     */
    @Synchronized
    fun prepareCore() {
        if (isCoreCurrent()) return
        val tmp = File(dir, "$CORE.tmp")
        tmp.delete()
        context.assets.open(BUNDLED_CORE).use { asset ->
            ZstdInputStream(asset).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        }
        val off = disabled().map { "dict_$it.db" }.toSet()
        val packs = (dictionaryFiles() + pitchFiles()).filter { it.name !in off && schemaVersion(it) == DICT_SCHEMA_VERSION }
        AndroidWritableDb(tmp).use { CoreComposer.compose(it, packs) }
        File(dir, "$CORE.tmp-journal").delete()
        // An open core.db keeps reading its old file until DictionaryRepository.reopen().
        check(tmp.renameTo(coreFile)) { "could not replace $CORE" }
        stampFile.writeText(signature())
    }

    /**
     * The app build (its core.db.zst changes with it), every pack's name, size and date, and the
     * dictionaries turned off: importing, replacing or deleting a pack, or a switch, changes it.
     */
    private fun signature(): String {
        val app = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val packs = (dictionaryFiles() + pitchFiles()).joinToString(";") { "${it.name}:${it.length()}:${it.lastModified()}" }
        return "$app|$packs|off:${disabled().sorted()}"
    }

    sealed interface Progress {
        data class Copying(val name: String, val index: Int, val total: Int) : Progress
        /** Merging accents and ranking into core.db. */
        data object Preparing : Progress
        data object NoPacks : Progress
        data class BadVersion(val name: String) : Progress
        data object Done : Progress
    }

    /** Copies dict_*.db and pitch_*.db from a picked tree, then rebuilds core.db. Blocking; call off the main thread. */
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
                // An old core.db in the folder is ignored: the app brings its own.
                if ((name.startsWith("dict_") || name.startsWith("pitch_")) && name.endsWith(".db")) {
                    packs += name to DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
            }
        }
        if (packs.isEmpty()) { onProgress(Progress.NoPacks); return }
        packs.sortBy { it.first }
        packs.forEachIndexed { i, (name, uri) ->
            onProgress(Progress.Copying(name, i + 1, packs.size))
            val tmp = File(dir, "$name.tmp")
            resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            if (schemaVersion(tmp) != DICT_SCHEMA_VERSION) {
                tmp.delete(); onProgress(Progress.BadVersion(name)); return
            }
            tmp.renameTo(File(dir, name))
        }
        onProgress(Progress.Preparing)
        prepareCore()
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
        /** In assets/, generated by tools/build_core.py (app/build.gradle.kts copies it). */
        const val BUNDLED_CORE = "core.db.zst"
        const val CORE_SCHEMA_VERSION = 5
        const val DICT_SCHEMA_VERSION = 3

        /** Default display order; the user's order (settings) comes first. */
        private val ORDER = listOf("dict_daijisen.db", "dict_daijirin4.db", "dict_meikyo3.db", "dict_shinmeikai8.db", "dict_shogakukan_jc3.db", "dict_shinseiki_jc.db")
    }
}
