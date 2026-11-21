package app.jisho.feature.settings

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.jisho.BuildConfig
import app.jisho.core.datapack.DataPackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One imported data pack as 数据与关于 lists it; [title] is null for core.db. */
data class PackInfo(val title: String?, val builtAt: String, val bytes: Long)

/** What 数据与关于 shows: versions and where the storage goes. */
data class AppInfo(
    val version: String,
    val packs: List<PackInfo>,
    /** 0 when the handwriting model is not downloaded. */
    val modelBytes: Long,
    /** Favorites, history, settings and the automatic pre-restore backups. */
    val userBytes: Long,
) {
    val packBytes: Long get() = packs.sumOf { it.bytes }
}

/** Reads [AppInfo]; an interface so the view model holds no Context. */
fun interface AppInfoSource {
    suspend fun read(): AppInfo
}

/**
 * Reads files only: pack metadata from each pack's meta table, sizes from the app's folders.
 * The handwriting model is judged by ML Kit's folder, not by asking ML Kit, whose first call
 * in a process takes about 0.7 s (design-spec 4.3).
 */
class AndroidAppInfoSource(private val context: Context, private val packs: DataPackManager) : AppInfoSource {
    override suspend fun read(): AppInfo = withContext(Dispatchers.IO) {
        val files = listOf(packs.coreFile) + packs.dictionaryFiles() + packs.pitchFiles()
        AppInfo(
            version = BuildConfig.VERSION_NAME,
            packs = files.filter { it.exists() }.map { f ->
                val meta = meta(f)
                PackInfo(if (f == packs.coreFile) null else meta["title"] ?: f.name, meta["built_at"].orEmpty().take(10), f.length())
            },
            modelBytes = size(File(context.filesDir, MODEL_DIR)),
            userBytes = size(context.getDatabasePath("x").parentFile) + size(File(context.filesDir, "backups")) +
                size(File(context.filesDir, "datastore")),
        )
    }

    private fun meta(f: File): Map<String, String> = runCatching {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT key, value FROM meta WHERE key IN ('title', 'built_at')", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun size(f: File?): Long = f?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0

    private companion object {
        /** Where ML Kit keeps downloaded Digital Ink models (found on the phone, 2026-11-21). */
        const val MODEL_DIR = "mlkit_digital_ink_recognition"
    }
}
