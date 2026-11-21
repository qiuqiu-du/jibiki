package app.jisho.core.userdata

import android.content.Context
import android.net.Uri
import app.jisho.core.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Backup and restore (design-spec 6): to and from a file the user picks, with a safety copy
 * of the current data before every restore (in the app's own files, the latest [KEEP]) so a
 * restore can be undone.
 */
class BackupManager(
    private val context: Context,
    private val store: UserStore,
    private val settings: SettingsRepository,
) {
    private val safetyDir = File(context.filesDir, "backups")

    suspend fun current(): Backup = Backup(store.dump(), settings.backupSettings(), System.currentTimeMillis())

    suspend fun writeTo(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = BackupFile.write(current())
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }

    /** Reads a backup file; throws [IllegalArgumentException] if it isn't one. */
    suspend fun readFrom(uri: Uri): Backup = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("can't read the file")
        BackupFile.read(bytes)
    }

    /** Replaces the user's data and settings with [backup], after saving a safety copy. */
    suspend fun restore(backup: Backup) {
        saveSafetyCopy()
        store.replaceAll(backup.data)
        settings.restoreSettings(backup.settings)
    }

    /** The newest safety copy (what the data was before the last restore), or null. */
    fun lastSafetyCopy(): File? = safetyDir.listFiles { f -> f.name.endsWith(".zip") }?.maxByOrNull { it.name }

    /** Undoes the last restore: puts back the newest safety copy, then deletes it. */
    suspend fun undoRestore(): Boolean {
        val file = lastSafetyCopy() ?: return false
        val backup = withContext(Dispatchers.IO) { BackupFile.read(file.readBytes()) }
        store.replaceAll(backup.data)
        settings.restoreSettings(backup.settings)
        file.delete()
        return true
    }

    private suspend fun saveSafetyCopy() {
        val bytes = BackupFile.write(current())
        withContext(Dispatchers.IO) {
            safetyDir.mkdirs()
            File(safetyDir, "before-restore-${stamp()}.zip").writeBytes(bytes)
            safetyDir.listFiles { f -> f.name.endsWith(".zip") }!!.sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        }
    }

    private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))

    companion object {
        const val KEEP = 5

        /** File name offered when the user saves a backup. */
        fun fileName() = "jisho-backup-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}.zip"
    }
}
