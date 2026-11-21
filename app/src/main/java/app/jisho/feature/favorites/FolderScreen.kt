package app.jisho.feature.favorites

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jisho.R
import app.jisho.core.data.WordSummary
import app.jisho.core.data.SummaryPrefs
import app.jisho.core.data.SummaryPlace
import app.jisho.core.designsystem.formatAccent
import app.jisho.core.designsystem.ja
import app.jisho.core.designsystem.zh
import app.jisho.core.userdata.Favorite
import app.jisho.core.userdata.FavoriteSort

/**
 * One folder's words (design-spec 5): headword, reading, pitch accent and the first definition
 * sentence; sorted by time added or gojūon. Swipe a word left to delete it; hold a word to
 * select several, then move or delete. Deleting is undone from the snackbar, so it asks nothing
 * (exporting is done for all favorites, on the folder list).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(viewModel: FolderViewModel, folderId: Long, onBack: () -> Unit, onOpenTarget: (String) -> Unit) {
    val words by viewModel.words.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val folder = folders.firstOrNull { it.id == folderId }
    val name = folder?.name.orEmpty()
    val selecting = selected.isNotEmpty()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(viewModel.messages, snackbar)
    var moving by remember { mutableStateOf(false) }
    BackHandler(selecting) { viewModel.clearSelection() }

    // The Scaffold's padding already includes the status bar (adding statusBarsPadding too left a blank band).
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel)) }
                    Text(stringResource(R.string.favorites_selected, selected.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::selectAll) { Text(stringResource(R.string.favorites_select_all)) }
                    TextButton(onClick = { moving = true }) { Text(stringResource(R.string.favorites_move)) }
                    IconButton(onClick = viewModel::removeSelected) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.favorites_delete)) }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.entry_back), modifier = Modifier.size(30.dp))
                    }
                    Text(name, style = MaterialTheme.typography.titleLarge.ja(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { viewModel.setSort(if (sort == FavoriteSort.Added) FavoriteSort.Kana else FavoriteSort.Added) }) {
                        Text(stringResource(if (sort == FavoriteSort.Added) R.string.favorites_sort_added else R.string.favorites_sort_kana))
                    }
                }
            }
            val list = words
            if (list != null && list.isEmpty()) {
                Text(
                    stringResource(R.string.favorites_folder_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            // Drawn once the words are loaded: rows on screen from the start don't animate in (a
            // list shown empty first made every row "new", fading in under dividers already drawn).
            if (list != null) LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.word.headword + "|" + it.word.reading }) { fav ->
                    val checked = fav.word in selected
                    Column(Modifier.animateItem()) {
                    SwipeToDelete(enabled = !selecting, onDelete = { viewModel.remove(setOf(fav.word)) }, modifier = Modifier) {
                        WordRow(
                            fav,
                            summary = { viewModel.summary(fav.word) },
                            cached = viewModel.cachedSummary(fav.word),
                            lines = viewModel.lines(),
                            selecting = selecting,
                            checked = checked,
                            modifier = Modifier.combinedClickable(
                                onClick = { if (selecting) viewModel.toggle(fav.word) else onOpenTarget(fav.word.target) },
                                onLongClick = { viewModel.toggle(fav.word) },
                            ),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
    if (moving) {
        FolderPickerSheet(
            folders.filter { it.id != folderId },
            checked = emptySet(),
            title = stringResource(R.string.favorites_move_to),
            single = true,
            onToggle = { id ->
                moving = false
                folders.firstOrNull { it.id == id }?.let { f -> viewModel.moveSelected(f, f.name) }
            },
            onCreate = { n -> moving = false; viewModel.createFolderAndMove(n) },
            onDismiss = { moving = false },
        )
    }
}

/** Swiping the row to the left past the threshold deletes it (a red band with a bin shows while swiping). */
@Composable
private fun SwipeToDelete(enabled: Boolean, onDelete: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    // Plain remember, not rememberSwipeToDismissBoxState: that one is saved per list key, so a word
    // brought back by 撤销 came back "already swiped away" and was deleted again at once.
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it * 0.4f }) }
    SwipeToDismissBox(
        state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = enabled,
        onDismiss = { onDelete() },
        backgroundContent = {
            // Only while the row is being swiped: a held (selected) row must not show it.
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Row(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.favorites_swipe_delete), color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

@Composable
private fun WordRow(
    fav: Favorite,
    summary: suspend () -> WordSummary,
    cached: WordSummary?,
    lines: SummaryPrefs,
    selecting: Boolean,
    checked: Boolean,
    modifier: Modifier,
) {
    val s by produceState(cached, fav.word) { if (value == null) value = summary() }
    Row(
        modifier.fillMaxWidth()
                        // Opaque: the swipe band lies behind the row.
            .background(if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f).compositeOver(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface)
            .padding(start = if (selecting) 4.dp else 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fav.word.headword, style = MaterialTheme.typography.titleMedium.ja())
                if (fav.word.reading.isNotEmpty() && fav.word.reading != fav.word.headword) {
                    Text("  " + fav.word.reading, style = MaterialTheme.typography.bodyMedium.ja(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val accent = formatAccent(s?.accent)
                if (accent.isNotEmpty()) Text("  $accent", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            // Meanings are normally known before the row is drawn (the view model looks them up
            // first), so a row without one is compact from the start. Only a row drawn before its
            // lookup finished keeps a blank line per meaning turned on, so it doesn't jump.
            val shown = when (val loaded = s) {
                null -> listOfNotNull(
                    (" " to false).takeIf { SummaryPlace.Favorites in lines.japanese && !fav.word.target.startsWith("lookup:") },
                    (" " to true).takeIf { SummaryPlace.Favorites in lines.chinese && !fav.word.target.startsWith("lookup:") },
                )
                else -> listOf(loaded.definition to false, loaded.chinese to true).filter { (t, _) -> t.isNotEmpty() }
            }
            shown.forEach { (text, chinese) ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium.let { if (chinese) it.zh() else it.ja() },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
