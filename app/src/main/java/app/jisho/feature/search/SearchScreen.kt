package app.jisho.feature.search

import androidx.compose.foundation.background
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
import app.jisho.core.designsystem.reasonText
import app.jisho.core.lemmatizer.Segment
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.jisho.core.designsystem.ja
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

class SearchViewModel(
    private val repository: DictionaryRepository,
    initialQuery: String = "",
    /** Waits for a pause in typing before querying; tests pass 0. */
    debounceMs: Long = 30,
    /** Each emission re-runs the current query (lemmatizer setting changed or finished loading). */
    refresh: Flow<Any> = flowOf(Unit),
) : ViewModel() {
    private val _query = MutableStateFlow(initialQuery)
    val query: StateFlow<String> = _query

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val result: StateFlow<SearchResult?> = _query
        .let { if (debounceMs > 0) it.debounce(debounceMs) else it }
        .combine(refresh) { q, _ -> q }
        .mapLatest { q -> if (q.isBlank()) null else repository.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onQueryChange(q: String) { _query.value = q }

    /**
     * Whether the keyboard opens the next time this screen is fully shown: on app launch and
     * after returning from a word, not after returning from settings or the app switcher.
     */
    private var focusOnResume = true

    fun consumeFocusRequest(): Boolean = focusOnResume.also { focusOnResume = false }

    /** Called when leaving for an entry page, so the keyboard comes back on return. */
    fun focusOnReturn() { focusOnResume = true }

    /** Handwriting appends recognized characters to what is already typed. */
    fun append(text: String) { _query.value += text }
}

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    handwriting: HandwritingViewModel,
    onOpenEntry: (Candidate) -> Unit,
    onOpenDictEntry: (String, Long) -> Unit,
    onLookup: (String) -> Unit,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    var writing by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // The field keeps its own cursor; re-created on return, it starts with the cursor at the end.
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    LaunchedEffect(query) {
        if (query != field.text) field = TextFieldValue(query, TextRange(query.length)) // clear, handwriting
    }
    // RESUMED only once a page transition (or a back gesture) has fully finished.
    LifecycleResumeEffect(Unit) {
        if (viewModel.consumeFocusRequest()) {
            focus.requestFocus()
            keyboard?.show()
        }
        onPauseOrDispose { }
    }
    // Leaving for an entry page: drop the keyboard now instead of after the transition.
    fun leave(go: () -> Unit) {
        keyboard?.hide()
        focusManager.clearFocus()
        viewModel.focusOnReturn()
        go()
    }
    if (writing) {
        HandwritingPanel(handwriting, typed = query, onPick = viewModel::append, onDismiss = { writing = false })
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it; viewModel.onQueryChange(it.text) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.titleMedium.ja(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear))
                    }
                    IconButton(onClick = { keyboard?.hide(); writing = true }) {
                        Icon(Icons.Default.Create, contentDescription = stringResource(R.string.search_handwriting))
                    }
                }
            },
        )
        val r = result
        when {
            query.isBlank() -> Hint(stringResource(R.string.search_empty_hint))
            r != null && r.words.isEmpty() && r.dictOnly.isEmpty() && r.segments.isEmpty() ->
                Hint(stringResource(R.string.search_no_result, query))
            r != null -> LazyColumn(Modifier.fillMaxSize()) {
                if (r.segments.isNotEmpty()) {
                    item(key = "segments") { SegmentBar(r.segments) { key -> leave { onLookup(key) } } }
                }
                items(r.words, key = { "w${it.entryId}" }) { c ->
                    CandidateRow(c) { leave { onOpenEntry(c) } }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (r.words.isNotEmpty() && r.dictOnly.isNotEmpty()) {
                    item(key = "dictOnlyHeader") { DictOnlyHeader() }
                }
                items(r.dictOnly, key = { "d${it.dictId}${it.entryId}" }) { c ->
                    DictCandidateRow(c) { leave { onOpenDictEntry(c.dictId, c.entryId) } }
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
    c.inflection?.let { inf ->
        val reasons = reasonText(inf.reasons)
        Text(
            if (reasons.isEmpty()) stringResource(R.string.search_inflection, inf.from)
            else stringResource(R.string.search_inflection_reasons, inf.from, reasons),
            style = MaterialTheme.typography.bodySmall.ja(), color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    if (c.hint.isNotEmpty()) {
        Text(
            c.hint, style = MaterialTheme.typography.bodySmall.ja(), color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    }
}

/** The words of a typed sentence; tapping one looks it up on its own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentBar(segments: List<Segment>, onLookup: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.search_segments),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            segments.forEach { s ->
                SuggestionChip(
                    onClick = { onLookup(s.lookup) },
                    label = { Text(s.surface, style = MaterialTheme.typography.titleMedium.ja()) },
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Separates words that only one dictionary has (no JMdict entry, so no shared main entry). */
@Composable
private fun DictOnlyHeader() {
    Text(
        stringResource(R.string.search_dict_only),
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
