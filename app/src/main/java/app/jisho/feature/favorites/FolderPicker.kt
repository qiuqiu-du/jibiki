package app.jisho.feature.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import app.jisho.R
import app.jisho.core.designsystem.ja
import app.jisho.core.userdata.Folder

/**
 * Bottom sheet listing all folders with check boxes ([checked]); each tap applies at once.
 * [single]: pick one folder (moving words) instead of toggling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(
    folders: List<Folder>,
    checked: Set<Long>,
    onToggle: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.favorites_pick_title),
    single: Boolean = false,
) {
    var naming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!single) TextButton(onClick = onDismiss) { Text(stringResource(R.string.favorites_done)) }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                folders.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(f.id) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (single) Spacer(Modifier.size(12.dp)) else Checkbox(checked = f.id in checked, onCheckedChange = { onToggle(f.id) })
                        Text(f.name, style = MaterialTheme.typography.bodyLarge.ja(), modifier = Modifier.weight(1f).padding(vertical = 14.dp))
                        Text(
                            pluralStringResource(R.plurals.favorites_words, f.count, f.count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.favorites_new), color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (naming) {
        NameDialog(
            title = stringResource(R.string.favorites_new),
            initial = "",
            taken = folders.map { it.name }.toSet(),
            onDismiss = { naming = false },
        ) { onCreate(it); naming = false }
    }
}

/** Asks for a folder name; [taken] names can't be used again. */
@Composable
fun NameDialog(title: String, initial: String, taken: Set<String>, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val trimmed = name.trim()
    val clash = trimmed != initial && trimmed in taken
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace("\n", "") },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.favorites_name_hint)) },
                isError = clash,
                supportingText = { if (clash) Text(stringResource(R.string.favorites_name_taken)) },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(trimmed) }, enabled = trimmed.isNotEmpty() && !clash) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}
