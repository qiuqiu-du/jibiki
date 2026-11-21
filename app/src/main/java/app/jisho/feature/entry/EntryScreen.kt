package app.jisho.feature.entry

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.draw.clip
import app.jisho.feature.favorites.FolderPickerSheet
import app.jisho.core.designsystem.pageSwipe
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.jisho.core.designsystem.gaijiContent
import app.jisho.core.designsystem.badgeContent
import app.jisho.core.designsystem.DefaultLabelStyle
import app.jisho.DebugOptions
import app.jisho.core.userdata.Folder
import app.jisho.core.userdata.UserStore
import app.jisho.core.userdata.WordRef
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

/** The top of an entry page: the word, its readings with pitch accents, labels, other spellings. */
data class WordHead(
    val headword: String,
    val readings: List<String> = emptyList(),
    /** reading -> raw accent ("2", "0,2", "1+1") */
    val accents: Map<String, String> = emptyMap(),
    val common: Boolean = false,
    /** JMdict part-of-speech codes */
    val pos: List<String> = emptyList(),
    val otherForms: List<String> = emptyList(),
    /** Reached from an inflected form (食べなかった -> 食べる). */
    val inflection: Inflection? = null,
)

/**
 * Everything one entry page shows, whatever it came from: a JMdict word, one dictionary item,
 * or a word the packs don't have (a phrase, a new word). One shape drawn by one layout, so a
 * change to the page (another web tab, say) applies to every kind of word at once.
 */
data class EntryPage(
    /** How favorites and history store the word. */
    val word: WordRef,
    /** null for one dictionary item, whose title heads its own section. */
    val head: WordHead?,
    /** Dictionaries with something to show (empty: "no definition"). */
    val sections: List<Pair<DictInfo, List<DictEntry>>>,
    /** Spellings the web tabs try, best first (see [webSpellings]). */
    val webQuery: List<String>,
    /** For an undefined word: dictionary forms it may be an inflection of. */
    val baseForms: List<Candidate> = emptyList(),
) {
    companion object {
        fun of(core: CoreEntry, sections: List<Pair<DictInfo, List<DictEntry>>>, inflection: Inflection?, baseForms: List<Candidate>) = EntryPage(
            WordRef(core.headword, core.reading, "core:${core.id}"),
            WordHead(
                core.headword,
                core.forms.map { it.reading }.distinct(),
                core.accents,
                core.isCommon,
                core.pos,
                core.forms.mapNotNull { it.kanji }.distinct().filter { it != core.headword },
                inflection,
            ),
            sections.filter { it.second.isNotEmpty() },
            webSpellings(core.headword, core.forms.map { it.kanji to it.reading }),
            baseForms,
        )

        fun of(dict: DictInfo, entry: DictEntry): EntryPage {
            val word = titleWord(entry.title)
            val reading = titleReading(entry.title)
            return EntryPage(
                WordRef(word, reading.orEmpty(), "dict:${dict.id}:${entry.id}"),
                head = null,
                listOf(dict to listOf(entry)),
                listOfNotNull(word, reading).distinct(),
            )
        }

        /** A word the offline packs don't have; [reading] as saved before, else "". */
        fun unknown(word: String, reading: String) = EntryPage(
            WordRef(word, reading, "lookup:$word"),
            WordHead(word, listOf(reading).filter { it.isNotEmpty() }),
            emptyList(),
            listOf(word, reading).filter { it.isNotEmpty() }.distinct(),
        )
    }
}

sealed interface EntryUiState {
    data object Loading : EntryUiState
    /** A broken link or saved id (not a word that merely has no offline data: that is a page too). */
    data object NotFound : EntryUiState
    data class Ready(val page: EntryPage) : EntryUiState
}

class EntryViewModel(
    private val repository: DictionaryRepository,
    target: EntryTarget,
    /** Favorites and history; null in tests that only check what is shown. */
    private val user: UserStore? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<EntryUiState>(EntryUiState.Loading)
    val state: StateFlow<EntryUiState> = _state

    /** The word as favorites and history store it (null until loaded, or not found). */
    private val _word = MutableStateFlow<WordRef?>(null)

    /** Folders the word is in (empty: not a favorite). */
    private val _inFolders = MutableStateFlow<Set<Long>>(emptySet())
    val inFolders: StateFlow<Set<Long>> = _inFolders

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders

    init {
        viewModelScope.launch {
            val s = load(target)
            _state.value = s
            val word = (s as? EntryUiState.Ready)?.page?.word ?: return@launch
            _word.value = word
            user ?: return@launch
            user.recordHistory(word)
            // Follows changes made elsewhere too (folder pages, an import).
            user.version.collect {
                _inFolders.value = user.foldersOf(word)
                _folders.value = user.folders()
                lastUsed = user.lastUsedFolder()
            }
        }
    }

    private var lastUsed: Long? = null

    /**
     * Star tapped. A word not saved yet goes to the folder used last, and that folder's name is
     * returned (for "saved to …"); null means show the folder list instead: the word is saved
     * already, or there is no folder to save to yet.
     */
    fun star(): String? {
        val word = _word.value ?: return null
        if (_inFolders.value.isNotEmpty()) return null
        val folder = _folders.value.firstOrNull { it.id == lastUsed } ?: return null
        _inFolders.value = setOf(folder.id) // at once, before the write lands
        viewModelScope.launch { user?.addFavorite(word, folder.id) }
        return folder.name
    }

    fun toggleFolder(id: Long) {
        val word = _word.value ?: return
        val next = _inFolders.value.let { if (id in it) it - id else it + id }
        _inFolders.value = next
        viewModelScope.launch { user?.setFolders(word, next) }
    }

    fun createFolder(name: String) {
        val word = _word.value ?: return
        viewModelScope.launch {
            val id = user?.createFolder(name) ?: return@launch
            user.setFolders(word, _inFolders.value + id)
        }
    }

    private suspend fun load(target: EntryTarget): EntryUiState = when (target) {
        is EntryTarget.Core -> repository.entry(target.id)
            ?.let { EntryUiState.Ready(EntryPage.of(it.core, it.definitions, target.inflection, it.baseForms)) }
            ?: EntryUiState.NotFound
        is EntryTarget.Dict -> repository.dictEntry(target.dictId, target.id)
            ?.let { EntryUiState.Ready(EntryPage.of(it.first, it.second)) } ?: EntryUiState.NotFound
        is EntryTarget.Lookup -> {
            val r = repository.search(target.key)
            // Links may point at inflected forms; their dictionary form counts as a match.
            val word = r.words.firstOrNull { it.exact || it.inflection != null }
            val dictOnly = r.dictOnly.firstOrNull()
            when {
                word != null -> load(EntryTarget.Core(word.entryId, word.inflection))
                dictOnly != null -> load(EntryTarget.Dict(dictOnly.dictId, dictOnly.entryId))
                else -> target.key.trim().takeIf { it.isNotEmpty() }
                    ?.let { EntryUiState.Ready(EntryPage.unknown(it, user?.lookupReading(it).orEmpty())) }
                    ?: EntryUiState.NotFound
            }
        }
    }
}

@Composable
fun EntryScreen(
    viewModel: EntryViewModel,
    onBack: () -> Unit,
    onOpenDictEntry: (String, Long) -> Unit,
    onLookup: (String) -> Unit,
    onOpenEntry: (Candidate) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = (state as? EntryUiState.Ready)?.page
    val query = page?.webQuery
    val webViews = rememberWebViews()
    if (query != null) WarmUpWebView()
    // Offline, コトバンク, Weblio side by side: swipe sideways or tap the floating tabs.
    val pager = rememberPagerState { EntryTab.entries.size }
    val scope = rememberCoroutineScope()
    // The floating tabs step aside while reading down, and come back on scrolling up.
    var tabsShown by remember { mutableStateOf(true) }
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -SCROLL_SLOP) tabsShown = false else if (available.y > SCROLL_SLOP) tabsShown = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(pager.currentPage) { tabsShown = true }
    webViews.onScroll = { down -> tabsShown = !down }
    // Favorites: tap the star to save to the folder used last; tap again or hold it for the folder list.
    val inFolders by viewModel.inFolders.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val changeText = stringResource(R.string.favorites_change)
    val canSave = page != null
    Scaffold(
        topBar = {
            EntryTopBar(
                onBack,
                starred = inFolders.isNotEmpty(),
                starEnabled = canSave,
                onStar = {
                    val folder = viewModel.star()
                    if (folder != null) scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        val text = context.getString(R.string.favorites_added, folder)
                        val r = snackbar.showSnackbar(text, changeText, duration = SnackbarDuration.Short)
                        if (r == SnackbarResult.ActionPerformed) picking = true
                    } else picking = true
                },
                onStarLong = { if (canSave) picking = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (query != null) 64.dp else 0.dp)) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).nestedScroll(hideOnScroll).pageSwipe(pager, query != null)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = false) { page ->
                val tab = EntryTab.entries[page]
                if (tab == EntryTab.Offline || query == null) {
                    OfflineContent(state, onOpenDictEntry, onLookup, onOpenEntry, bottomSpace = if (query != null) 72.dp else 0.dp)
                } else {
                    // A site loads once its page is settled on (not while swiping past it).
                    WebPage(tab, query, webViews, open = pager.settledPage == page)
                }
            }
            if (query != null) {
                AnimatedVisibility(
                    tabsShown,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    FloatingTabs(EntryTab.entries[pager.currentPage]) { scope.launch { pager.animateScrollToPage(it.ordinal) } }
                }
            }
        }
    }
    if (picking) FolderSheet(viewModel, folders, inFolders) { picking = false }
}

private const val SCROLL_SLOP = 2f

@Composable
private fun FolderSheet(viewModel: EntryViewModel, folders: List<Folder>, inFolders: Set<Long>, onDismiss: () -> Unit) {
    FolderPickerSheet(folders, inFolders, onToggle = viewModel::toggleFolder, onCreate = viewModel::createFolder, onDismiss = onDismiss)
}

/** Top row: back on the left, the favorite star on the right (filled when the word is saved). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryTopBar(onBack: () -> Unit, starred: Boolean, starEnabled: Boolean, onStar: () -> Unit, onStarLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.entry_back),
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        // IconButton has no long press: the same 48 dp round target, built by hand.
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .combinedClickable(enabled = starEnabled, onLongClick = onStarLong, onClick = onStar),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (starred) Icons.Filled.Star else StarOutline,
                contentDescription = stringResource(if (starred) R.string.entry_unfavorite else R.string.entry_favorite),
                tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OfflineContent(
    state: EntryUiState,
    onOpenDictEntry: (String, Long) -> Unit,
    onLookup: (String) -> Unit,
    onOpenEntry: (Candidate) -> Unit,
    bottomSpace: Dp = 0.dp,
) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                EntryUiState.Loading -> Unit
                EntryUiState.NotFound -> Text(stringResource(R.string.entry_not_found))
                is EntryUiState.Ready -> {
                    val p = state.page
                    p.head?.let { WordHeader(it) }
                    if (p.sections.isEmpty()) {
                        Text(stringResource(R.string.entry_no_definition), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.baseForms.isNotEmpty()) BaseForms(p.baseForms, onOpenEntry)
                    }
                    p.sections.forEachIndexed { i, (dict, entries) ->
                        DictSection(dict, entries, initiallyExpanded = i == 0) { href ->
                            followLink(dict.id, href, onOpenDictEntry, onLookup)
                        }
                    }
                }
            }
            Spacer(Modifier.height(bottomSpace)) // room for the floating tabs
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
private fun WordHeader(h: WordHead) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(h.headword, style = MaterialTheme.typography.displaySmall.ja())
        // 食べなかった ← 食べる（否定・过去）
        h.inflection?.let { inf ->
            val reasons = reasonText(inf.reasons)
            Text(
                if (reasons.isEmpty()) stringResource(R.string.entry_inflection, inf.from, h.headword)
                else stringResource(R.string.entry_inflection_reasons, inf.from, h.headword, reasons),
                style = MaterialTheme.typography.bodyMedium.ja(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // One line per reading, each with its pitch numbers. With several readings every line
        // names its reading (so the lines line up); a lone reading equal to the headword (kana
        // words) shows just the numbers.
        h.readings.forEach { reading ->
            val named = h.readings.size > 1 || reading != h.headword
            val accent = formatAccent(h.accents[reading])
            if (!named && accent.isEmpty()) return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (named) {
                    Text(reading, style = MaterialTheme.typography.titleMedium.ja(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (accent.isNotEmpty()) Text(
                    (if (named) "  " else "") + accent,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val chips = posLabels(h.pos)
        if (h.common || chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (h.common) Chip(stringResource(R.string.entry_common), highlight = true)
                chips.forEach { Chip(stringResource(it)) }
            }
        }
        if (h.otherForms.isNotEmpty()) {
            Text(
                stringResource(R.string.entry_other_forms, h.otherForms.joinToString("・")),
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
                        val inline = remember(entry.id, body) { gaijiContent(entry.images) + badgeContent(body) }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium.ja(), fontWeight = FontWeight.SemiBold)
                            Text(body, style = MaterialTheme.typography.bodyLarge.ja(), inlineContent = inline)
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
