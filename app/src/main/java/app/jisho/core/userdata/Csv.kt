package app.jisho.core.userdata

/** RFC 4180 CSV: fields with commas, quotes or line breaks are quoted, quotes doubled. */
object Csv {
    fun write(rows: List<List<String>>): String = buildString {
        rows.forEach { row ->
            append(row.joinToString(",") { field(it) })
            append("\r\n")
        }
    }

    private fun field(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Parses CSV text (a leading byte-order mark is ignored); blank lines are skipped. */
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        fun endCell() { row += cell.toString(); cell.clear() }
        fun endRow() {
            endCell()
            if (row.size > 1 || row[0].isNotEmpty()) rows += row
            row = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                quoted -> cell.append(c)
                c == ',' -> endCell()
                c == '\r' && text.getOrNull(i + 1) == '\n' -> { endRow(); i++ }
                c == '\n' || c == '\r' -> endRow()
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
