package app.jisho.core.datapack

import app.jisho.core.database.WritableDb
import java.io.File

/**
 * Turns the bundled core.db (open data: JMdict, Kanjium accents) into the one the app reads,
 * by merging what the imported packs carry (design-spec 3.6, tools/lib/pitch.py):
 * - `pitch`: accents aligned to JMdict. Per (entry, reading) the pack with the lowest
 *   `pitch_priority` wins (NHK, then 新明解, then 大辞林); Kanjium only fills the gaps.
 * - `entry_score`: better ranking scores (新明解's ＊＊/＊ basic words); search_key.rank,
 *   precomputed as score * 100 + spelling frequency, is rewritten for those words.
 * Packs without a pitch_priority in their meta are skipped. Reads stay as before: one
 * `pitch` table, one precomputed rank, so search and entry pages cost nothing extra.
 */
object CoreComposer {

    private class Overlay(val alias: String, val priority: Int, val source: String)

    /** [db]: a writable copy of the bundled core.db; [packs]: dict_*.db and pitch_*.db files. */
    fun compose(db: WritableDb, packs: List<File>) {
        // ATTACH can't run inside a transaction: attach first, write in one transaction.
        val overlays = packs.mapIndexedNotNull { i, file ->
            val alias = "p$i"
            db.exec("ATTACH DATABASE ? AS $alias", listOf(file.path))
            fun meta(key: String) = db.query("SELECT value FROM $alias.meta WHERE key = ?", listOf(key)) { it.string(0) }.firstOrNull()
            val priority = meta("pitch_priority")?.toIntOrNull()
            if (priority == null) { db.exec("DETACH DATABASE $alias"); null }
            else Overlay(alias, priority, meta("pitch_source") ?: file.nameWithoutExtension)
        }.sortedBy { it.priority }
        try {
            db.transaction {
                db.exec(
                    "CREATE TABLE pitch_merged (entry_id INTEGER NOT NULL, reading TEXT NOT NULL, accent TEXT NOT NULL, " +
                        "source TEXT NOT NULL, PRIMARY KEY (entry_id, reading)) WITHOUT ROWID",
                )
                for (o in overlays) {
                    db.exec("INSERT OR IGNORE INTO pitch_merged SELECT entry_id, reading, accent, ? FROM ${o.alias}.pitch", listOf(o.source))
                    db.exec(
                        "UPDATE entry SET score = MIN(score, (SELECT s.score FROM ${o.alias}.entry_score s WHERE s.entry_id = entry.id)) " +
                            "WHERE id IN (SELECT entry_id FROM ${o.alias}.entry_score)",
                    )
                }
                db.exec("INSERT OR IGNORE INTO pitch_merged SELECT entry_id, reading, accent, source FROM pitch")
                db.exec("DROP TABLE pitch")
                db.exec("ALTER TABLE pitch_merged RENAME TO pitch")
                // rank % 100 is the spelling's own frequency (always below 100).
                db.exec(
                    "UPDATE search_key SET rank = (SELECT e.score FROM entry e WHERE e.id = search_key.entry_id) * 100 + rank % 100 " +
                        "WHERE entry_id IN (SELECT id FROM entry WHERE score <> freq)",
                )
                db.exec("INSERT OR REPLACE INTO meta VALUES ('composed_from', ?)", listOf(overlays.joinToString(",") { it.source }))
            }
        } finally {
            overlays.forEach { db.exec("DETACH DATABASE ${it.alias}") }
        }
    }
}
