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
import app.jisho.core.data.SettingsRepository
import app.jisho.core.lemmatizer.LemmatizerMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {
    val lemmatizerMode: StateFlow<LemmatizerMode?> =
        settings.lemmatizerMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setLemmatizerMode(mode: LemmatizerMode) = viewModelScope.launch { settings.setLemmatizerMode(mode) }
}

/** M2 settings: the lemmatizer switch (dictionary management, backup and appearance come later). */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val mode by viewModel.lemmatizerMode.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(vertical = 16.dp)) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.settings_lemmatizer),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        )
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
