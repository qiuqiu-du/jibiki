package app.jisho.feature.favorites

import app.jisho.core.data.SummaryPlace
import app.jisho.core.data.SummaryPrefs
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jisho.R
import app.jisho.core.data.DictionaryRepository
import app.jisho.core.data.WordSummary
import app.jisho.core.database.AndroidWritableDb
import app.jisho.core.userdata.CsvEncoding
import app.jisho.core.userdata.ExportRow
import app.jisho.core.userdata.FavoriteSort
import app.jisho.core.userdata.Favorite
import app.jisho.core.userdata.FavoritesTransfer
import app.jisho.core.userdata.Folder
import app.jisho.core.userdata.UserStore
import app.jisho.core.userdata.WordRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** A message for the snackbar: a string resource and its arguments. */
data class Message(val res: Int, val args: List<Any> = emptyList(), val undo: (() -> Unit)? = null)

enum class ExportFormat(val extension: String, val mime: String) {
    Csv("csv", "text/csv"),
    Anki("apkg", "application/octet-stream"),
}

/** Reads and writes the files the user picks (export targets, import sources). */
class UserFiles(private val context: Context) {
    suspend fun write(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }

    suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    }

    /** Builds an Anki package in the app's cache. */
    fun anki(transfer: FavoritesTransfer, rows: List<ExportRow>, deck: String): ByteArray =
        transfer.anki(rows, deck, File(context.cacheDir, "anki-export.db")) { AndroidWritableDb(it) }

    companion object {
        fun exportName(format: ExportFormat) = "jisho-favorites-${LocalDate.now()}.${format.extension}"
    }
}

/** Shared export step: rows -> file, reporting how many words went out. */
private suspend fun export(
    files: UserFiles, transfer: FavoritesTransfer, rows: List<ExportRow>, format: ExportFormat, deck: String, uri: Uri,
): Message = runCatching {
    val bytes = when (format) {
        ExportFormat.Csv -> transfer.csv(rows)
        ExportFormat.Anki -> withContext(Dispatchers.IO) { files.anki(transfer, rows, deck) }
    }
    files.write(uri, bytes)
    Message(R.string.favorites_exported, listOf(rows.distinctBy { it.favorite.word.headword to it.favorite.word.reading }.size))
}.getOrElse { Message(R.string.favorites_file_failed) }

/** The folder list (favorites tab): folders, plus exporting everything and importing CSV. */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModel(
    private val store: UserStore,
    private val transfer: FavoritesTransfer,
    private val files: UserFiles,
) : ViewModel() {
    val folders: StateFlow<List<Folder>> = store.version.mapLatest { store.folders() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 4)
    val messages: SharedFlow<Message> = _messages

    fun create(name: String) { viewModelScope.launch { store.createFolder(name) } }
    fun rename(id: Long, name: String) { viewModelScope.launch { store.renameFolder(id, name) } }
    fun delete(id: Long) { viewModelScope.launch { store.deleteFolder(id) } }
    fun reorder(ids: List<Long>) { viewModelScope.launch { store.reorderFolders(ids) } }

    fun exportAll(format: ExportFormat, uri: Uri) {
        viewModelScope.launch { _messages.emit(export(files, transfer, transfer.rows(), format, DECK, uri)) }
    }

    fun importCsv(uri: Uri, encoding: CsvEncoding) {
        viewModelScope.launch {
            val msg = runCatching { transfer.importCsv(files.read(uri), encoding) }.fold(
                onSuccess = { Message(R.string.favorites_imported, listOf(it.added, it.skipped)) },
                onFailure = { Message(if (it is IllegalArgumentException) R.string.favorites_import_failed else R.string.favorites_file_failed) },
            )
            _messages.emit(msg)
        }
    }

    companion object {
        /** Anki deck for exports. */
        const val DECK = "辞書"
    }
}

/** One folder's words: sorting, selection, moving and removing. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderViewModel(
    private val folderId: Long,
    private val store: UserStore,
    private val repository: DictionaryRepository,
) : ViewModel() {
    private val _sort = MutableStateFlow(FavoriteSort.Added)
    val sort: StateFlow<FavoriteSort> = _sort

    val folders: StateFlow<List<Folder>> = store.version.mapLatest { store.folders() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var preloading: Job? = null

    /**
     * null while loading. A list comes out once the first screenful's meanings are known, so
     * every row is drawn at its final height (rows without a meaning take no blank line); the
     * rest load right after, before they can be scrolled to.
     */
    val words: StateFlow<List<Favorite>?> = combine(store.version, _sort) { _, s -> s }
        .mapLatest { sort ->
            store.favorites(folderId, sort).also { list ->
                // All in the background and in parallel, handed back once: one lookup at a time
                // came back to the UI thread 24 times while it was busy with the page transition.
                withContext(Dispatchers.IO) {
                    list.take(FIRST_SCREEN).map { async { summary(it.word) } }.awaitAll()
                }
                preloading?.cancel()
                preloading = viewModelScope.launch(Dispatchers.IO) { list.drop(FIRST_SCREEN).forEach { summary(it.word) } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selected = MutableStateFlow<Set<WordRef>>(emptySet())
    val selected: StateFlow<Set<WordRef>> = _selected

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 4)
    val messages: SharedFlow<Message> = _messages

    private val summaries = ConcurrentHashMap<String, WordSummary>()

    /** Which one-line meanings the rows show (settings), known before any is looked up. */
    fun lines(): SummaryPrefs = repository.summaryPrefs()

    private fun key(word: WordRef) = word.target + "|" + word.reading + "|" + lines().encode() // a settings change looks up again

    /** Already looked up: rows coming back (from an entry page) show it at once, with no loading frame. */
    fun cachedSummary(word: WordRef): WordSummary? = summaries[key(word)]

    /** Pitch accent and one-line meanings, looked up once per word. */
    suspend fun summary(word: WordRef): WordSummary =
        summaries[key(word)] ?: repository.summary(word.target, word.reading, SummaryPlace.Favorites).also { summaries[key(word)] = it }

    private companion object {
        /** Rows looked up before the list is drawn: a little more than a screen holds (about 13). */
        const val FIRST_SCREEN = 16
    }

    fun setSort(s: FavoriteSort) { _sort.value = s }
    fun toggle(word: WordRef) { _selected.value = _selected.value.let { if (word in it) it - word else it + word } }
    fun selectAll() { _selected.value = words.value.orEmpty().map { it.word }.toSet() }
    fun clearSelection() { _selected.value = emptySet() }

    fun removeSelected() {
        val words = _selected.value
        _selected.value = emptySet()
        remove(words)
    }

    /** Removes at once; the snackbar's 撤销 puts them back as they were. */
    fun remove(words: Set<WordRef>) {
        val removed = this.words.value.orEmpty().filter { it.word in words }
        if (removed.isEmpty()) return
        viewModelScope.launch {
            store.removeFavorites(folderId, words)
            _messages.emit(Message(R.string.favorites_removed, listOf(removed.size)) { viewModelScope.launch { store.restoreFavorites(removed) } })
        }
    }

    fun moveSelected(to: Folder, toName: String) {
        val words = _selected.value
        _selected.value = emptySet()
        viewModelScope.launch {
            store.moveFavorites(folderId, to.id, words)
            _messages.emit(Message(R.string.favorites_moved, listOf(words.size, toName)))
        }
    }

    fun createFolderAndMove(name: String) {
        val words = _selected.value
        _selected.value = emptySet()
        viewModelScope.launch {
            val id = store.createFolder(name)
            store.moveFavorites(folderId, id, words)
            _messages.emit(Message(R.string.favorites_moved, listOf(words.size, name)))
        }
    }
}
