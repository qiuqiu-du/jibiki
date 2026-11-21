package app.jisho.core.userdata

import app.jisho.core.data.DictionaryRepository
import app.jisho.core.database.WritableDb
import app.jisho.core.designsystem.formatAccent
import java.io.File

/** Result of a favorites import: [added] new links, [skipped] already there, [folders] touched. */
data class ImportResult(val added: Int, val skipped: Int, val folders: Int)

/**
 * Exports favorites as CSV or Anki packages and imports CSV files (design-spec 6), filling in
 * what lives in the dictionaries (pitch accent, first definition sentence) on export and
 * matching imported words to entries.
 */
class FavoritesTransfer(
    private val store: UserStore,
    private val repository: DictionaryRepository,
) {
    /** The rows for [folderIds] (all if null), limited to [words] when given. */
    suspend fun rows(folderIds: Set<Long>? = null, words: Set<WordRef>? = null): List<ExportRow> {
        val names = store.folders().associate { it.id to it.name }
        return store.allFavorites(folderIds).filter { words == null || it.word in words }.map { fav ->
            // Exports ignore the display settings: every installed dictionary, both lines.
            val s = repository.summary(fav.word.target, fav.word.reading, place = null)
            ExportRow(names[fav.folderId].orEmpty(), fav, s.accent.orEmpty(), s.definition, s.chinese)
        }
    }

    fun csv(rows: List<ExportRow>): ByteArray = FavoritesCsv.export(rows).toByteArray()

    /** An Anki deck, one card per word (a word in several folders becomes one card). */
    fun anki(rows: List<ExportRow>, deckName: String, work: File, openDb: (File) -> WritableDb): ByteArray {
        val notes = rows.distinctBy { it.favorite.word.headword to it.favorite.word.reading }.map { r ->
            val w = r.favorite.word
            val reading = listOfNotNull(
                w.reading.takeIf { it.isNotEmpty() && it != w.headword }?.let { "<span class=reading>${html(it)}</span>" },
                formatAccent(r.accent).takeIf { it.isNotEmpty() }?.let { "<span class=accent>$it</span>" },
            ).joinToString(" ")
            val back = listOf(reading, html(r.definition), html(r.chinese)).filter { it.isNotEmpty() }.joinToString("<br>")
            AnkiNote(html(w.headword), back, "${w.headword}|${w.reading}")
        }
        return AnkiPackage.build(deckName, notes, work, openDb)
    }

    /**
     * Imports a CSV (ours, or any with a headword column) into the folders it names, creating
     * missing ones; rows without a usable target are matched to dictionary entries.
     */
    suspend fun importCsv(bytes: ByteArray, encoding: CsvEncoding = CsvEncoding.Utf8): ImportResult {
        val rows = FavoritesCsv.parse(TextEncoding.decode(bytes, encoding))
        val now = System.currentTimeMillis()
        val items = rows.map { r ->
            val folder = r.folder // "" (no folder column): the store's unnamed folder
            val target = r.target.ifEmpty { repository.resolve(r.headword, r.reading) }
            val reading = r.reading.ifEmpty { readingOf(target, r.headword) }
            folder to Favorite(0, WordRef(r.headword, reading, target), r.added ?: now)
        }
        val added = store.importFavorites(items)
        return ImportResult(added, items.size - added, items.map { it.first }.distinct().size)
    }

    /** A reading for imported words that came without one (it's part of a favorite's identity). */
    private suspend fun readingOf(target: String, headword: String): String {
        val id = target.removePrefix("core:").toLongOrNull() ?: return ""
        return repository.entry(id)?.core?.forms?.firstOrNull { it.kanji == headword || it.reading == headword }?.reading ?: ""
    }

    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
