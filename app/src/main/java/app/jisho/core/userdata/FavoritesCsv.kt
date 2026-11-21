package app.jisho.core.userdata

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a favorite looks like in an export: [accent] and [definition] come from the dictionaries. */
data class ExportRow(val folder: String, val favorite: Favorite, val accent: String, val definition: String, val chinese: String = "")

/** One favorite read from a CSV: [folder] as named in the file; [target] blank when the file has none. */
data class ImportRow(val folder: String, val headword: String, val reading: String, val added: Long?, val target: String)

/**
 * Favorites as CSV (design-spec 6): one row per word and folder, readable in Excel (UTF-8 with
 * a byte-order mark) and importable again. Columns are found by their header names, so a file
 * edited elsewhere still imports as long as it has a headword column.
 */
object FavoritesCsv {
    val HEADER = listOf("folder", "headword", "reading", "accent", "definition", "chinese", "added", "target")
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun export(rows: List<ExportRow>, zone: ZoneId = ZoneId.systemDefault()): String =
        "\uFEFF" + Csv.write(
            listOf(HEADER) + rows.map { r ->
                listOf(
                    r.folder, r.favorite.word.headword, r.favorite.word.reading, r.accent, r.definition, r.chinese,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(r.favorite.added), zone).format(TIME), r.favorite.word.target,
                )
            },
        )

    /** Reads the rows; throws [IllegalArgumentException] if the file has no headword column. */
    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<ImportRow> {
        val rows = Csv.parse(text)
        val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyList()
        fun col(name: String) = header.indexOf(name)
        val headword = col("headword")
        require(headword >= 0) { "no headword column" }
        val folder = col("folder")
        val reading = col("reading")
        val added = col("added")
        val target = col("target")
        return rows.drop(1).mapNotNull { r ->
            fun at(i: Int) = if (i >= 0) r.getOrNull(i)?.trim().orEmpty() else ""
            val h = at(headword).ifEmpty { return@mapNotNull null }
            ImportRow(
                folder = at(folder),
                headword = h,
                reading = at(reading),
                added = at(added).takeIf { it.isNotEmpty() }?.let {
                    runCatching { LocalDateTime.parse(it, TIME).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
                },
                target = at(target).takeIf { isTarget(it) }.orEmpty(),
            )
        }
    }

    /** A target the app can open (anything else in the column is ignored). */
    fun isTarget(s: String) =
        Regex("core:\\d+").matches(s) || Regex("dict:[a-z0-9_]+:\\d+").matches(s) || Regex("lookup:.+").matches(s)
}
