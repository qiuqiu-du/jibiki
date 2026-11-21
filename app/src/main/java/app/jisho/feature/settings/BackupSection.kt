package app.jisho.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.jisho.R
import app.jisho.core.userdata.Backup
import app.jisho.core.userdata.BackupManager
import app.jisho.feature.favorites.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Backup and restore in settings (design-spec 6). */
class BackupViewModel(private val backups: BackupManager) : ViewModel() {
    private val _canUndo = MutableStateFlow(backups.lastSafetyCopy() != null)
    val canUndo: StateFlow<Boolean> = _canUndo

    /** A backup read from a file, waiting for the user to confirm the restore. */
    private val _pending = MutableStateFlow<Backup?>(null)
    val pending: StateFlow<Backup?> = _pending

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 4)
    val messages: SharedFlow<Message> = _messages

    private fun say(res: Int) { _messages.tryEmit(Message(res)) }

    fun backupTo(uri: Uri) = viewModelScope.launch {
        say(if (runCatching { backups.writeTo(uri) }.isSuccess) R.string.settings_backup_done else R.string.favorites_file_failed)
    }

    fun open(uri: Uri) = viewModelScope.launch {
        runCatching { backups.readFrom(uri) }
            .onSuccess { _pending.value = it }
            .onFailure { say(R.string.settings_restore_failed) }
    }

    fun cancelRestore() { _pending.value = null }

    fun confirmRestore() = viewModelScope.launch {
        val b = _pending.value ?: return@launch
        _pending.value = null
        backups.restore(b)
        _canUndo.value = true
        say(R.string.settings_restore_done)
    }

    fun undoRestore() = viewModelScope.launch {
        if (backups.undoRestore()) say(R.string.settings_undo_restore_done)
        _canUndo.value = backups.lastSafetyCopy() != null
    }

}

@Composable
fun BackupSection(viewModel: BackupViewModel) {
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(viewModel::backupTo) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::open) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        stringResource(R.string.settings_backup_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = muted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { save.launch(BackupManager.fileName()) }) { Text(stringResource(R.string.settings_backup_now)) }
        TextButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(stringResource(R.string.settings_restore)) }
    }
    if (canUndo) {
        TextButton(onClick = { viewModel.undoRestore() }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.settings_undo_restore))
        }
    }

    pending?.let { b ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(stringResource(R.string.settings_restore_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_restore_text,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(b.created)),
                        b.data.folders.size, b.data.favorites.size, b.data.history.size,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmRestore() }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
