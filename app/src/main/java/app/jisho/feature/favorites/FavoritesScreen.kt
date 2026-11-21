package app.jisho.feature.favorites

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import app.jisho.core.designsystem.dragHandle
import app.jisho.core.designsystem.longPressDrag
import app.jisho.core.designsystem.rememberReorderState
import app.jisho.core.designsystem.reorderable
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jisho.R
import app.jisho.core.designsystem.ja
import app.jisho.core.userdata.Folder
import app.jisho.core.userdata.CsvEncoding

/** Shows [Message]s from a view model in [host]; one with [Message.undo] gets an 撤销 button and stays longer. */
@Composable
fun MessageEffect(messages: kotlinx.coroutines.flow.SharedFlow<Message>, host: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(messages) {
        messages.collect { m ->
            // A new message replaces the one showing (deleting several rows one after another).
            host.currentSnackbarData?.dismiss()
            launch {
                val text = context.getString(m.res, *m.args.toTypedArray())
                val r = host.showSnackbar(text, m.undo?.let { context.getString(R.string.common_undo) }, duration = if (m.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) m.undo?.invoke()
            }
        }
    }
}

/**
 * One option per row, with a line of explanation; picking one closes the dialog.
 * [options]: (title, description) string resources.
 */
@Composable
private fun FormatDialog(title: String, options: List<Pair<Int, Int>>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, (t, d) ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 12.dp)) {
                        Text(stringResource(t), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The favorites tab: all folders with word counts; new, export all and import. Holding a folder
 * switches to edit mode: drag ≡ to reorder, tap a name to rename, the bin to delete.
 */
@Composable
fun FavoritesScreen(viewModel: FavoritesViewModel, onOpenFolder: (Long) -> Unit) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(viewModel.messages, snackbar)
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Folder?>(null) } // id 0 = a new folder
    var deleting by remember { mutableStateOf<Folder?>(null) }
    var choosing by remember { mutableStateOf<String?>(null) } // "export" or "import": format dialog
    var importEncoding by remember { mutableStateOf(CsvEncoding.Utf8) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.Csv.mime)) { uri ->
        uri?.let { viewModel.exportAll(ExportFormat.Csv, it) }
    }
    val exportAnki = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.Anki.mime)) { uri ->
        uri?.let { viewModel.exportAll(ExportFormat.Anki, it) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importCsv(it, importEncoding) }
    }
    // The list as shown: follows the store, except while a row is being dragged.
    val order = remember { mutableStateListOf<Folder>() }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState,
        canMoveTo = { it is Long },
        onDone = { viewModel.reorder(order.map { it.id }) },
    ) { from, to ->
        val i = order.indexOfFirst { it.id == from }
        val j = order.indexOfFirst { it.id == to }
        if (i >= 0 && j >= 0) order.add(j, order.removeAt(i))
    }
    LaunchedEffect(folders) {
        if (reorder.dragging == null) { order.clear(); order.addAll(folders) }
    }
    if (folders.isEmpty()) editing = false
    BackHandler(editing) { editing = false }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (editing) R.string.favorites_edit_title else R.string.favorites_title),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                )
                if (editing) {
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.favorites_done)) }
                } else {
                    IconButton(onClick = { naming = Folder(0, "", 0) }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.favorites_new))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.favorites_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.favorites_export)) }, onClick = { menu = false; choosing = "export" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.favorites_import)) }, onClick = { menu = false; choosing = "import" })
                        }
                    }
                }
            }
            // Above the list, not in it: the list keeps its first row in place, so a row added
            // on top would start scrolled out of sight.
            if (editing) {
                Text(
                    stringResource(R.string.favorites_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(order, key = { it.id }) { f ->
                    Column(Modifier.reorderable(reorder, f.id, Modifier.animateItem()).background(MaterialTheme.colorScheme.surface)) {
                        if (editing) {
                            EditRow(
                                f,
                                onRename = { naming = f },
                                onDelete = { deleting = f },
                                modifier = Modifier.longPressDrag(reorder, f.id),
                                handle = Modifier.dragHandle(reorder, f.id),
                            )
                        } else {
                            FolderRow(f, onOpen = { onOpenFolder(f.id) }, onEdit = { editing = true })
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (folders.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.favorites_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
    when (choosing) {
        "export" -> FormatDialog(
            stringResource(R.string.favorites_export_as),
            listOf(R.string.format_csv to R.string.format_csv_desc, R.string.format_anki to R.string.format_anki_desc),
            onDismiss = { choosing = null },
        ) { i ->
            choosing = null
            if (i == 0) exportCsv.launch(UserFiles.exportName(ExportFormat.Csv)) else exportAnki.launch(UserFiles.exportName(ExportFormat.Anki))
        }
        "import" -> FormatDialog(
            stringResource(R.string.favorites_import_from),
            listOf(R.string.format_csv_utf8 to R.string.format_csv_utf8_desc, R.string.format_csv_ansi to R.string.format_csv_ansi_desc),
            onDismiss = { choosing = null },
        ) { i ->
            choosing = null
            importEncoding = if (i == 0) CsvEncoding.Utf8 else CsvEncoding.Ansi
            import.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
        }
    }
    naming?.let { f ->
        NameDialog(
            title = stringResource(if (f.id == 0L) R.string.favorites_new else R.string.favorites_rename),
            initial = f.name,
            taken = folders.map { it.name }.toSet(),
            onDismiss = { naming = null },
        ) { name ->
            if (f.id == 0L) viewModel.create(name) else viewModel.rename(f.id, name)
            naming = null
        }
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.favorites_delete_folder_title, f.name)) },
            text = { Text(stringResource(R.string.favorites_delete_folder_text, f.count)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(f.id); deleting = null }) { Text(stringResource(R.string.favorites_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** Normal mode: tap opens; hold switches to edit mode (reorder, rename, delete). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(f: Folder, onOpen: () -> Unit, onEdit: () -> Unit) {
    FolderTitle(
        f,
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onEdit)
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
    )
}

/** Edit mode: delete on the left, the name renames, ≡ on the right drags (so does holding the row). */
@Composable
private fun EditRow(f: Folder, onRename: () -> Unit, onDelete: () -> Unit, modifier: Modifier, handle: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.favorites_delete), tint = MaterialTheme.colorScheme.error)
        }
        FolderTitle(f, Modifier.weight(1f).clickable(onClick = onRename).padding(vertical = 8.dp))
        Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.favorites_drag), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FolderTitle(f: Folder, modifier: Modifier) {
    Column(modifier) {
        Text(f.name, style = MaterialTheme.typography.titleMedium.ja())
        Text(
            stringResource(R.string.favorites_words, f.count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
