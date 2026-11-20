package app.jisho.feature.entry

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.jisho.R
import app.jisho.core.data.DictInfo
import app.jisho.core.data.DictionaryRepository
import app.jisho.core.database.Candidate
import app.jisho.core.database.CoreEntry
import app.jisho.core.database.DictEntry
import app.jisho.core.database.Inflection
import app.jisho.core.designsystem.reasonText
import app.jisho.core.designsystem.BodyColors
import app.jisho.core.designsystem.formatAccent
import app.jisho.core.designsystem.ja
import app.jisho.core.designsystem.renderBody
import app.jisho.core.designsystem.DefaultLabelStyle
import app.jisho.DebugOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the entry page shows: a JMdict-backed word, one dictionary item, or a link lookup. */
sealed interface EntryTarget {
    /** [inflection]: set when reached from an inflected form (食べなかった -> 食べる). */
    data class Core(val id: Long, val inflection: Inflection? = null) : EntryTarget
    data class Dict(val dictId: String, val id: Long) : EntryTarget
    data class Lookup(val key: String) : EntryTarget
}

sealed interface EntryUiState {
    data object Loading : EntryUiState
    data object NotFound : EntryUiState
    data class Word(
        val core: CoreEntry,
        val sections: List<Pair<DictInfo, List<DictEntry>>>,
        val inflection: Inflection? = null,
        val baseForms: List<Candidate> = emptyList(),
    ) : EntryUiState
    data class Single(val dict: DictInfo, val entry: DictEntry) : EntryUiState
}

class EntryViewModel(private val repository: DictionaryRepository, target: EntryTarget) : ViewModel() {
    private val _state = MutableStateFlow<EntryUiState>(EntryUiState.Loading)
    val state: StateFlow<EntryUiState> = _state

    init {
        viewModelScope.launch { _state.value = load(target) }
    }

    private suspend fun load(target: EntryTarget): EntryUiState = when (target) {
        is EntryTarget.Core -> repository.entry(target.id)
            ?.let { EntryUiState.Word(it.core, it.definitions.filter { d -> d.second.isNotEmpty() }, target.inflection, it.baseForms) }
            ?: EntryUiState.NotFound
        is EntryTarget.Dict -> repository.dictEntry(target.dictId, target.id)
            ?.let { EntryUiState.Single(it.first, it.second) } ?: EntryUiState.NotFound
        is EntryTarget.Lookup -> {
            val r = repository.search(target.key)
            // Links may point at inflected forms; their dictionary form counts as a match.
            val word = r.words.firstOrNull { it.exact || it.inflection != null }
            val dictOnly = r.dictOnly.firstOrNull()
            when {
                word != null -> load(EntryTarget.Core(word.entryId, word.inflection))
                dictOnly != null -> load(EntryTarget.Dict(dictOnly.dictId, dictOnly.entryId))
                else -> EntryUiState.NotFound
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(
    viewModel: EntryViewModel,
    onBack: () -> Unit,
    onOpenDictEntry: (String, Long) -> Unit,
    onLookup: (String) -> Unit,
    onOpenEntry: (Candidate) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.entry_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                EntryUiState.Loading -> Unit
                EntryUiState.NotFound -> Text(stringResource(R.string.entry_no_definition))
                is EntryUiState.Word -> {
                    WordHeader(s.core, s.inflection)
                    if (s.sections.isEmpty()) {
                        Text(
                            stringResource(R.string.entry_no_definition),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (s.baseForms.isNotEmpty()) BaseForms(s.baseForms, onOpenEntry)
                    }
                    s.sections.forEachIndexed { i, (dict, entries) ->
                        DictSection(dict, entries, initiallyExpanded = i == 0) { href ->
                            followLink(dict.id, href, onOpenDictEntry, onLookup)
                        }
                    }
                }
                is EntryUiState.Single -> DictSection(s.dict, listOf(s.entry), initiallyExpanded = true) { href ->
                    followLink(s.dict.id, href, onOpenDictEntry, onLookup)
                }
            }
        }
    }
}

private fun followLink(dictId: String, href: String, onOpenDictEntry: (String, Long) -> Unit, onLookup: (String) -> Unit) {
    when {
        href.startsWith("#") -> href.substring(1).toLongOrNull()?.let { onOpenDictEntry(dictId, it) }
        href.startsWith("?") -> onLookup(href.substring(1))
    }
}

/** "可能是这些词的变形" with one tappable row per dictionary form. */
@Composable
private fun BaseForms(forms: List<Candidate>, onOpen: (Candidate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.entry_base_forms), style = MaterialTheme.typography.titleSmall)
        forms.forEach { c ->
            val reasons = c.inflection?.let { reasonText(it.reasons) }.orEmpty()
            Surface(
                onClick = { onOpen(c) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.headword, style = MaterialTheme.typography.titleMedium.ja())
                    if (c.reading != c.headword) {
                        Text("  " + c.reading, style = MaterialTheme.typography.bodyMedium.ja(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (reasons.isNotEmpty()) {
                        Text("  （$reasons）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WordHeader(e: CoreEntry, inflection: Inflection?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(e.headword, style = MaterialTheme.typography.displaySmall.ja())
        // 食べなかった ← 食べる（否定・过去）
        inflection?.let { inf ->
            val reasons = reasonText(inf.reasons)
            Text(
                if (reasons.isEmpty()) stringResource(R.string.entry_inflection, inf.from, e.headword)
                else stringResource(R.string.entry_inflection_reasons, inf.from, e.headword, reasons),
                style = MaterialTheme.typography.bodyMedium.ja(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // One line per distinct reading, each with its pitch numbers.
        e.forms.map { it.reading }.distinct().forEach { reading ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (reading != e.headword) {
                    Text(reading, style = MaterialTheme.typography.titleMedium.ja(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "  " + formatAccent(e.accents[reading]),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (e.isCommon) Chip(stringResource(R.string.entry_common), highlight = true)
            posLabels(e.pos).forEach { Chip(stringResource(it)) }
        }
        val others = e.forms.mapNotNull { it.kanji }.distinct().filter { it != e.headword }
        if (others.isNotEmpty()) {
            Text(
                stringResource(R.string.entry_other_forms, others.joinToString("・")),
                style = MaterialTheme.typography.bodyMedium.ja(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Chip(text: String, highlight: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium.ja())
    }
}

@Composable
private fun DictSection(dict: DictInfo, entries: List<DictEntry>, initiallyExpanded: Boolean, onLink: (String) -> Unit) {
    var expanded by rememberSaveable(dict.id) { mutableStateOf(initiallyExpanded) }
    val colors = BodyColors(
        accent = MaterialTheme.colorScheme.primary,
        muted = MaterialTheme.colorScheme.onSurfaceVariant,
        example = MaterialTheme.colorScheme.secondary,
        link = MaterialTheme.colorScheme.primary,
        labelBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    )
    val labelStyle = DebugOptions.labelStyle ?: DefaultLabelStyle
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Text(
                dict.title,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                style = MaterialTheme.typography.titleSmall.ja(),
                color = MaterialTheme.colorScheme.primary,
            )
            if (expanded) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    entries.forEach { entry ->
                        val body = remember(entry.id, colors, labelStyle) { renderBody(entry.body, colors, labelStyle, onLink) }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium.ja(), fontWeight = FontWeight.SemiBold)
                            Text(body, style = MaterialTheme.typography.bodyLarge.ja())
                        }
                    }
                }
            }
        }
    }
}

private fun posLabels(codes: List<String>): List<Int> = codes.mapNotNull { code ->
    when {
        code == "v1" || code == "v1-s" -> R.string.pos_v1
        code.startsWith("v5") -> R.string.pos_v5
        code == "vk" -> R.string.pos_vk
        code.startsWith("vs") -> R.string.pos_vs
        code == "vz" -> R.string.pos_vz
        code == "adj-i" || code == "adj-ix" -> R.string.pos_adj_i
        code == "adj-na" -> R.string.pos_adj_na
        code == "n" -> R.string.pos_n
        code.startsWith("adv") -> R.string.pos_adv
        code == "pn" -> R.string.pos_pn
        code == "exp" -> R.string.pos_exp
        code == "int" -> R.string.pos_int
        code == "conj" -> R.string.pos_conj
        code == "prt" -> R.string.pos_prt
        code.startsWith("aux") -> R.string.pos_aux
        code == "pref" -> R.string.pos_pref
        code == "suf" || code == "n-suf" -> R.string.pos_suf
        code == "ctr" -> R.string.pos_ctr
        code == "adj-pn" -> R.string.pos_adj_pn
        else -> null
    }
}.distinct()
