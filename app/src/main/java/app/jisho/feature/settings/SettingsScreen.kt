package app.jisho.feature.settings

import app.jisho.core.data.SummaryPrefs
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import app.jisho.core.data.EntryExpand
import app.jisho.core.data.ThemeMode
import app.jisho.feature.search.HandwritingViewModel
import app.jisho.feature.search.InkModelState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import app.jisho.core.data.SummaryPlace
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import app.jisho.feature.favorites.MessageEffect
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
    private val appInfo: AppInfoSource,
    private val appLanguage: AppLanguage? = null,
) : ViewModel() {
    /** null when the system can't set a per-app language (before Android 13). */
    val languageSupported: Boolean = appLanguage?.supported == true
    private val _language = MutableStateFlow(appLanguage?.current())
    /** The chosen interface language tag, or null to follow the system. */
    val language: StateFlow<String?> = _language

    fun setLanguage(tag: String?) {
        _language.value = tag
        appLanguage?.set(tag)
    }

    val entryExpand: StateFlow<EntryExpand> = settings.entryExpand.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryExpand.First)
    val theme: StateFlow<ThemeMode> = settings.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)
    val autoKeyboard: StateFlow<Boolean> = settings.autoKeyboard.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setEntryExpand(e: EntryExpand) = viewModelScope.launch { settings.setEntryExpand(e) }
    fun setTheme(t: ThemeMode) = viewModelScope.launch { settings.setTheme(t) }
    fun setAutoKeyboard(on: Boolean) = viewModelScope.launch { settings.setAutoKeyboard(on) }

    private val _info = MutableStateFlow<AppInfo?>(null)
    /** null until first read; read each time the page comes into view (sizes change). */
    val info: StateFlow<AppInfo?> = _info

    fun refreshInfo() = viewModelScope.launch { _info.value = runCatching { appInfo.read() }.getOrNull() }

    val lemmatizerMode: StateFlow<LemmatizerMode?> =
        settings.lemmatizerMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Imported dictionaries in the user's order; refreshed whenever the setting changes. */
    val dictionaries: StateFlow<List<DictStatus>> = settings.dictPrefs
        .map { repository.dictionaries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setLemmatizerMode(mode: LemmatizerMode) = viewModelScope.launch { settings.setLemmatizerMode(mode) }

    val summaryPrefs: StateFlow<SummaryPrefs> =
        settings.summaryPrefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryPrefs())

    /** Turns the Japanese ([chinese] false) or Chinese one-line meaning at [place] on or off. */
    fun setSummary(chinese: Boolean, place: SummaryPlace, on: Boolean) = viewModelScope.launch {
        val p = settings.summaryPrefs.first()
        fun Set<SummaryPlace>.with() = if (on) this + place else this - place
        settings.setSummaryPrefs(if (chinese) p.copy(chinese = p.chinese.with()) else p.copy(japanese = p.japanese.with()))
    }

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
fun SettingsScreen(
    viewModel: SettingsViewModel,
    backup: BackupViewModel,
    /** Shared with the search page's handwriting panel: model state and deletion. */
    handwriting: HandwritingViewModel,
    /** True while this page is in view: storage figures are read then. */
    active: Boolean,
    onReimport: () -> Unit,
) {
    val mode by viewModel.lemmatizerMode.collectAsStateWithLifecycle()
    val expand by viewModel.entryExpand.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val autoKeyboard by viewModel.autoKeyboard.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val handwritingState by handwriting.state.collectAsStateWithLifecycle()
    val model = handwritingState.model
    // Read on every visit, and again when the model is downloaded or deleted.
    LaunchedEffect(active, model) { if (active) viewModel.refreshInfo() }
    val dictionaries by viewModel.dictionaries.collectAsStateWithLifecycle()
    val summary by viewModel.summaryPrefs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(backup.messages, snackbar)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        // Three groups (design-spec 5): dictionaries and what is shown from them; app-wide
        // settings; the user's data and read-only information. Each setting is one card.
        GroupTitle(R.string.settings_group_dictionaries)
        SettingCard(R.string.settings_dictionaries, R.string.settings_dictionaries_desc) {
            dictionaries.forEachIndexed { i, d ->
                DictionaryRow(
                    d,
                    canMoveUp = i > 0,
                    canMoveDown = i < dictionaries.lastIndex,
                    onMove = { delta -> viewModel.move(i, delta) },
                    onEnabled = { viewModel.setEnabled(d.info.id, it) },
                )
            }
            CardButtons { FilledTonalButton(onClick = onReimport) { Text(stringResource(R.string.settings_reimport)) } }
        }
        SettingCard(R.string.settings_entry_expand) {
            Column(Modifier.selectableGroup()) {
                Choice(expand == EntryExpand.First, R.string.settings_entry_expand_first) { viewModel.setEntryExpand(EntryExpand.First) }
                Choice(expand == EntryExpand.All, R.string.settings_entry_expand_all) { viewModel.setEntryExpand(EntryExpand.All) }
                Choice(expand == EntryExpand.None, R.string.settings_entry_expand_none) { viewModel.setEntryExpand(EntryExpand.None) }
            }
        }
        SettingCard(R.string.settings_summary, R.string.settings_summary_desc) {
            SummaryTable(summary, viewModel::setSummary)
        }

        GroupTitle(R.string.settings_group_general)
        SettingCard(R.string.settings_theme) {
            Column(Modifier.selectableGroup()) {
                Choice(theme == ThemeMode.System, R.string.settings_theme_system) { viewModel.setTheme(ThemeMode.System) }
                Choice(theme == ThemeMode.Light, R.string.settings_theme_light) { viewModel.setTheme(ThemeMode.Light) }
                Choice(theme == ThemeMode.Dark, R.string.settings_theme_dark) { viewModel.setTheme(ThemeMode.Dark) }
            }
        }
        if (viewModel.languageSupported) SettingCard(R.string.settings_language) {
            Column(Modifier.selectableGroup()) {
                Choice(language == null, R.string.settings_language_system) { viewModel.setLanguage(null) }
                Choice(language == "zh-CN", R.string.settings_language_zh) { viewModel.setLanguage("zh-CN") }
                Choice(language == "ja", R.string.settings_language_ja) { viewModel.setLanguage("ja") }
                Choice(language == "en", R.string.settings_language_en) { viewModel.setLanguage("en") }
            }
        }
        SettingCard(R.string.settings_keyboard) {
            SwitchRow(R.string.settings_auto_keyboard, R.string.settings_auto_keyboard_desc, autoKeyboard, viewModel::setAutoKeyboard)
        }
        SettingCard(R.string.settings_lemmatizer) {
            Column(Modifier.selectableGroup()) {
                Option(mode == LemmatizerMode.Kuromoji, R.string.settings_lemmatizer_kuromoji, R.string.settings_lemmatizer_kuromoji_desc) {
                    viewModel.setLemmatizerMode(LemmatizerMode.Kuromoji)
                }
                Option(mode == LemmatizerMode.Rules, R.string.settings_lemmatizer_rules, R.string.settings_lemmatizer_rules_desc) {
                    viewModel.setLemmatizerMode(LemmatizerMode.Rules)
                }
            }
        }

        GroupTitle(R.string.settings_group_data)
        SettingCard(R.string.settings_backup, R.string.settings_backup_desc) { BackupSection(backup) }
        info?.let { AboutSection(it, model, onDeleteModel = handwriting::deleteModel) }
    }
    }
}

/**
 * One setting as a card: its title, an optional description and its controls together, so it
 * is clear what belongs to which setting. Titles are plain text in the body color; anything
 * tappable is a control (switch, radio, filled button), never colored text that looks like it.
 */
@Composable
internal fun SettingCard(title: Int, description: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = CARD_INSET))
            description?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = CARD_INSET, end = CARD_INSET, top = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/** A card's action buttons: filled, in one row, wrapping when the text is large. */
@Composable
internal fun CardButtons(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = CARD_INSET, end = CARD_INSET, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Left and right inset of text inside a [SettingCard]. */
internal val CARD_INSET = 16.dp

/** One choice of a radio group, title only. */
@Composable
private fun Choice(selected: Boolean, title: Int, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
    }
}

/** A title, a description and an on/off switch; the whole row toggles. */
@Composable
private fun SwitchRow(title: Int, description: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(start = CARD_INSET, end = CARD_INSET, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A first-level group heading above its cards. */
@Composable
private fun GroupTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
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
        Modifier.fillMaxWidth().padding(start = CARD_INSET, end = 8.dp, top = 2.dp, bottom = 2.dp),
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

/** Rows: where (search results, favorites, entry header); columns: Japanese, Chinese line. */
@Composable
private fun SummaryTable(p: SummaryPrefs, onChange: (chinese: Boolean, SummaryPlace, Boolean) -> Unit) {
    val column = Modifier.width(72.dp)
    Row(Modifier.fillMaxWidth().padding(start = CARD_INSET, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        for (label in listOf(R.string.settings_summary_ja, R.string.settings_summary_zh)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = column)
        }
    }
    val places = listOf(
        SummaryPlace.Search to R.string.settings_summary_search,
        SummaryPlace.Favorites to R.string.settings_summary_favorites,
        SummaryPlace.Header to R.string.settings_summary_header,
    )
    for ((place, label) in places) {
        Row(Modifier.fillMaxWidth().padding(start = CARD_INSET, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            for (chinese in listOf(false, true)) {
                val on = place in (if (chinese) p.chinese else p.japanese)
                Box(column, contentAlignment = Alignment.Center) {
                    Checkbox(checked = on, onCheckedChange = { onChange(chinese, place, it) })
                }
            }
        }
    }
}

@Composable
private fun Option(selected: Boolean, title: Int, description: Int, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 4.dp),
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
