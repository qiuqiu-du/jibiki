package app.jisho.core.userdata

import app.jisho.core.database.WritableDb
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One Anki card: [front] the word, [back] reading, pitch accent and definition (HTML); [key] keeps re-exports from duplicating. */
data class AnkiNote(val front: String, val back: String, val key: String)

/**
 * Builds an Anki package (.apkg, design-spec 6): a zip holding `collection.anki2`, a SQLite
 * collection in Anki's classic (schema 11) format, with one deck and one two-field note type,
 * plus an empty `media` list. Anki 2.1 and later import it as is. The note type and deck ids
 * are fixed, so importing a newer export updates the same deck and cards (matched by [AnkiNote.key]).
 */
object AnkiPackage {
    private const val MODEL_ID = 1_732_000_000_001L
    private const val DECK_ID = 1_732_000_000_002L

    /** [openDb] creates the collection file (Android SQLite in the app, JDBC in tests); [work] is a scratch file. */
    fun build(deckName: String, notes: List<AnkiNote>, work: File, openDb: (File) -> WritableDb, now: Long = System.currentTimeMillis()): ByteArray {
        work.delete()
        openDb(work).use { db ->
            db.transaction {
                SCHEMA.forEach { db.exec(it) }
                val sec = now / 1000
                db.exec(
                    "INSERT INTO col VALUES (1, ?, ?, ?, 11, 0, 0, 0, ?, ?, ?, ?, '{}')",
                    listOf(sec, now, now, CONF, model(now), decks(deckName, sec), DCONF),
                )
                notes.forEachIndexed { i, n ->
                    val id = now + i
                    db.exec(
                        "INSERT INTO notes VALUES (?, ?, ?, ?, -1, '', ?, ?, ?, 0, '')",
                        listOf(id, guid(n.key), MODEL_ID, sec, n.front + "\u001f" + n.back, stripHtml(n.front), checksum(n.front)),
                    )
                    db.exec(
                        "INSERT INTO cards VALUES (?, ?, ?, 0, ?, -1, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                        listOf(id, id, DECK_ID, sec, i + 1),
                    )
                }
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("collection.anki2")); zip.write(work.readBytes()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("media")); zip.write("{}".toByteArray()); zip.closeEntry()
        }
        work.delete()
        File(work.path + "-journal").delete()
        return out.toByteArray()
    }

    private fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), "")

    /** Anki's duplicate check: the first 8 hex digits of the SHA-1 of the first field, as a number. */
    private fun checksum(front: String): Long =
        MessageDigest.getInstance("SHA-1").digest(stripHtml(front).toByteArray()).take(4)
            .fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    /** Stable per word, so a re-import updates the note instead of adding a copy. */
    private fun guid(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(("jisho:" + key).toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private const val CSS = ".card{font-family:\\\"Noto Sans CJK JP\\\",\\\"Hiragino Sans\\\",sans-serif;font-size:22px;text-align:center}" +
        ".back{font-size:18px;text-align:left}.reading{font-size:20px}.accent{color:#3949ab}"

    private fun model(now: Long) = """{"$MODEL_ID":{"id":$MODEL_ID,"name":"辞書 単語","type":0,"mod":${now / 1000},"usn":-1,""" +
        """"sortf":0,"did":$DECK_ID,"tmpls":[{"name":"Card 1","ord":0,"qfmt":"{{Front}}",""" +
        """"afmt":"{{FrontSide}}<hr id=answer><div class=back>{{Back}}</div>","did":null,"bqfmt":"","bafmt":""}],""" +
        """"flds":[{"name":"Front","ord":0,"sticky":false,"rtl":false,"font":"Arial","size":20,"media":[]},""" +
        """{"name":"Back","ord":1,"sticky":false,"rtl":false,"font":"Arial","size":20,"media":[]}],""" +
        """"css":"$CSS","latexPre":"\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n",""" +
        """"latexPost":"\\end{document}","tags":[],"vers":[],"req":[[0,"any",[0]]]}}"""

    private fun deck(id: Long, name: String, sec: Long) = """"$id":{"id":$id,"name":${json(name)},"desc":"","mod":$sec,"usn":-1,""" +
        """"collapsed":false,"newToday":[0,0],"revToday":[0,0],"lrnToday":[0,0],"timeToday":[0,0],"dyn":0,"conf":1,""" +
        """"extendNew":10,"extendRev":50}"""

    private fun decks(name: String, sec: Long) = "{" + deck(1, "Default", sec) + "," + deck(DECK_ID, name, sec) + "}"

    private const val CONF = """{"nextPos":1,"estTimes":true,"activeDecks":[1],"sortType":"noteFld","timeLim":0,""" +
        """"sortBackwards":false,"addToCur":true,"curDeck":1,"newBury":true,"newSpread":0,"dueCounts":true,""" +
        """"curModel":"$MODEL_ID","collapseTime":1200}"""

    private const val DCONF = """{"1":{"id":1,"name":"Default","mod":0,"usn":0,"maxTaken":60,"autoplay":true,"timer":0,""" +
        """"replayq":true,"dyn":false,"new":{"delays":[1,10],"ints":[1,4,7],"initialFactor":2500,"order":1,"perDay":20,"bury":true,"separate":true},""" +
        """"lapse":{"delays":[10],"mult":0,"minInt":1,"leechFails":8,"leechAction":0},""" +
        """"rev":{"perDay":200,"ease4":1.3,"fuzz":0.05,"minSpace":1,"ivlFct":1,"maxIvl":36500,"bury":true,"hardFactor":1.2}}}"""

    private val SCHEMA = listOf(
        "CREATE TABLE col (id integer primary key, crt integer not null, mod integer not null, scm integer not null, ver integer not null, " +
            "dty integer not null, usn integer not null, ls integer not null, conf text not null, models text not null, " +
            "decks text not null, dconf text not null, tags text not null)",
        "CREATE TABLE notes (id integer primary key, guid text not null, mid integer not null, mod integer not null, usn integer not null, " +
            "tags text not null, flds text not null, sfld integer not null, csum integer not null, flags integer not null, data text not null)",
        "CREATE TABLE cards (id integer primary key, nid integer not null, did integer not null, ord integer not null, mod integer not null, " +
            "usn integer not null, type integer not null, queue integer not null, due integer not null, ivl integer not null, " +
            "factor integer not null, reps integer not null, lapses integer not null, left integer not null, odue integer not null, " +
            "odid integer not null, flags integer not null, data text not null)",
        "CREATE TABLE revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null, " +
            "ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null)",
        "CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null)",
        "CREATE INDEX ix_notes_usn on notes (usn)",
        "CREATE INDEX ix_cards_usn on cards (usn)",
        "CREATE INDEX ix_revlog_usn on revlog (usn)",
        "CREATE INDEX ix_cards_nid on cards (nid)",
        "CREATE INDEX ix_cards_sched on cards (did, queue, due)",
        "CREATE INDEX ix_revlog_cid on revlog (cid)",
        "CREATE INDEX ix_notes_csum on notes (csum)",
    )
}
