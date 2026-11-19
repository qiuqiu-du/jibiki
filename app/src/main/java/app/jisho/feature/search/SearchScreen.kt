package app.jisho.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.jisho.R
import app.jisho.core.data.DictionaryRepository
import app.jisho.core.data.SearchResult
import app.jisho.core.database.Candidate
import app.jisho.core.database.DictCandidate
import app.jisho.core.designsystem.formatAccent
import app.jisho.core.designsystem.ja
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

class SearchViewModel(
    private val repository: DictionaryRepository,
    initialQuery: String = "",
    /** Waits for a pause in typing before querying; tests pass 0. */
    debounceMs: Long = 30,
) : ViewModel() {
    private val _query = MutableStateFlow(initialQuery)
    val query: StateFlow<String> = _query

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val result: StateFlow<SearchResult?> = _query
        .let { if (debounceMs > 0) it.debounce(debounceMs) else it }
        .mapLatest { q -> if (q.isBlank()) null else repository.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onQueryChange(q: String) { _query.value = q }
}

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenEntry: (Long) -> Unit,
    onOpenDictEntry: (String, Long) -> Unit,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.titleMedium.ja(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailingIcon = {
                if (query.isNotEmpty()) TextButton(onClick = { viewModel.onQueryChange("") }) {
                    Text(stringResource(R.string.search_clear))
                }
            },
        )
        val r = result
        when {
            query.isBlank() -> Hint(stringResource(R.string.search_empty_hint))
            r != null && r.words.isEmpty() && r.dictOnly.isEmpty() ->
                Hint(stringResource(R.string.search_no_result, query))
            r != null -> LazyColumn(Modifier.fillMaxSize()) {
                items(r.words, key = { "w${it.entryId}" }) { c ->
                    CandidateRow(c) { onOpenEntry(c.entryId) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                items(r.dictOnly, key = { "d${it.dictId}${it.entryId}" }) { c ->
                    DictCandidateRow(c) { onOpenDictEntry(c.dictId, c.entryId) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CandidateRow(c: Candidate, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.headword, style = MaterialTheme.typography.titleLarge.ja(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        if (c.kanjiNote.isNotEmpty()) {
            Text(
                c.kanjiNote, style = MaterialTheme.typography.bodyMedium.ja(),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (c.reading != c.headword) {
            Text(
                c.reading, style = MaterialTheme.typography.bodyLarge.ja(),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(formatAccent(c.accent), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
    if (c.hint.isNotEmpty()) {
        Text(
            c.hint, style = MaterialTheme.typography.bodySmall.ja(), color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    }
}

@Composable
private fun DictCandidateRow(c: DictCandidate, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(c.title, style = MaterialTheme.typography.titleMedium.ja(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
