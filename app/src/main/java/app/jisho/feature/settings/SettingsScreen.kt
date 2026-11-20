package app.jisho.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.jisho.R
import app.jisho.core.data.DictPrefs
import app.jisho.core.data.DictionaryList
import kotlinx.coroutines.flow.first
import app.jisho.core.data.DictStatus
import app.jisho.core.data.SettingsRepository
import app.jisho.core.designsystem.ja
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import kotlinx.coroutines.flow.map
import app.jisho.core.lemmatizer.LemmatizerMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val repository: DictionaryList,
) : ViewModel() {
    val lemmatizerMode: StateFlow<LemmatizerMode?> =
        settings.lemmatizerMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Imported dictionaries in the user's order; refreshed whenever the setting changes. */
    val dictionaries: StateFlow<List<DictStatus>> = settings.dictPrefs
        .map { repository.dictionaries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setLemmatizerMode(mode: LemmatizerMode) = viewModelScope.launch { settings.setLemmatizerMode(mode) }

    /** Moves the dictionary at [index] by [delta] places (-1 up, +1 down). */
    fun move(index: Int, delta: Int) = update { list ->
        val to = index + delta
        if (to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(index)) }
    }

    fun setEnabled(id: String, enabled: Boolean) = update { list ->
        list.map { if (it.info.id == id) it.copy(enabled = enabled) else it }
    }

    private fun update(change: (List<DictStatus>) -> List<DictStatus>) = viewModelScope.launch {
        val list = change(repository.dictionaries(settings.dictPrefs.first()))
        settings.setDictPrefs(DictPrefs(list.map { it.info.id }, list.filter { !it.enabled }.map { it.info.id }.toSet()))
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onReimport: () -> Unit) {
    val mode by viewModel.lemmatizerMode.collectAsStateWithLifecycle()
    val dictionaries by viewModel.dictionaries.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        SectionTitle(R.string.settings_dictionaries)
        Text(
            stringResource(R.string.settings_dictionaries_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        dictionaries.forEachIndexed { i, d ->
            DictionaryRow(
                d,
                canMoveUp = i > 0,
                canMoveDown = i < dictionaries.lastIndex,
                onMove = { delta -> viewModel.move(i, delta) },
                onEnabled = { viewModel.setEnabled(d.info.id, it) },
            )
        }
        TextButton(onClick = onReimport, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.settings_reimport))
        }
        SectionTitle(R.string.settings_lemmatizer)
        Column(Modifier.selectableGroup()) {
            Option(mode == LemmatizerMode.Kuromoji, R.string.settings_lemmatizer_kuromoji, R.string.settings_lemmatizer_kuromoji_desc) {
                viewModel.setLemmatizerMode(LemmatizerMode.Kuromoji)
            }
            Option(mode == LemmatizerMode.Rules, R.string.settings_lemmatizer_rules, R.string.settings_lemmatizer_rules_desc) {
                viewModel.setLemmatizerMode(LemmatizerMode.Rules)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Title, up/down buttons and an on/off switch. */
@Composable
private fun DictionaryRow(
    d: DictStatus,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            d.info.title,
            style = MaterialTheme.typography.bodyLarge.ja(),
            color = if (d.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.settings_move_up))
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.settings_move_down))
        }
        Switch(checked = d.enabled, onCheckedChange = onEnabled, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun Option(selected: Boolean, title: Int, description: Int, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.padding(top = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
